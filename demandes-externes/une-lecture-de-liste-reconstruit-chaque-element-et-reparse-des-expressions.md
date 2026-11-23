# Une lecture de liste reconstruit chaque élément, et re-parse des expressions par champ

**À l'attention de :** garganttua-api (`core/repository`, `dao-mongodb`, `core/expression`) et garganttua-core
(`script`, `runtime`, `reflection`, `aot`, `expression`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM G1 et JVM HotSpot, MongoDB)
**Date :** 2026-11-23
**Version constatée :** garganttua `3.0.0-ALPHA37`
**Gravité :** structurelle, performance. Une lecture de liste a un coût **par élément** entièrement porté par le CADRE,
loin devant la base. Sur les grosses listes (une carte, un export) c'est le facteur limitant ; c'est le levier le plus
large sur la capacité de tout consommateur. **Cette fiche consolide et mesure** ce que décrivaient séparément
`le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md` et `le-dto-ne-restreint-pas-la-lecture-mongodb.md`,
et ajoute un coût que ni l'un ni l'autre ne citait : le **parsing d'expressions ANTLR dans le chemin chaud**.

## Symptôme (MESURÉ)

Lecture carte d'un domaine « jumeau léger » (`care-center-summaries`, 12 champs) sur **32 803 lignes**, binaire natif de
production (G1), base de 62 840 centres, viewport `latitude/longitude` borné — **433 ms par requête**. Le chronométrage
par étape du pipeline (sonde du cadre, `-Dgarganttua.perf.probe=true`) attribue **≈ 87 %** à une seule ligne du workflow
`READ_ALL` : `page <- getEntitiesPage(...)` (**378 ms**). Le reste est petit : sérialisation ~63 ms, vérification du
jeton ~18 ms (jeton réutilisé), toutes les autres lignes < 1 ms. **Le coût est O(éléments)** : la même lecture sur une
région (4 888 lignes) tombe à ~100 ms, sur une ville (960) à ~36 ms.

Profil JFR (JVM, échantillonnage de pile) de la SEULE lecture, répartition **DANS** `getEntitiesPage` :

| Composant (inclusif) | Part | Haut de pile (exclusif) | Part |
|---|---:|---|---:|
| décodage document→DTO (`MongoDocumentReader`) | **19,5 %** | JDK tables de hachage / chaînes | **27 %** |
| mapper DTO→entité (`garganttua-mapper`) | **18,9 %** | **parsing ANTLR** (`ParserATNSimulator.*`) | **~26 %** |
| pilote MongoDB (requête + BSON) | **15 %** | JDK réflexion/invoke (`MethodHandle…set/get`) | **10 %** |
| JSON (Jackson, réponse) | **8,2 %** | pilote Mongo 5,8 % · réflexion cadre 5,5 % · Jackson 5 % | — |

Décodage et mapper pèsent **autant l'un que l'autre**, devant la requête base (15 %). Méthodes les plus chaudes :
`ParserATNSimulator.closure_` 5,9 %, `MethodHandleObjectFieldAccessorImpl.set` 4,9 %, `HashMap.getNode` 4,2 %,
`…set/get` 2,8 %, `MongoDocumentReader.applyField` 1,4 %.

## Cause — quatre coûts par élément/champ

**1. Parsing d'expressions ANTLR dans le chemin chaud (MESURÉ ~26 % du temps propre ; site exact à CONFIRMER).**
`org.antlr.v4.runtime.atn.ParserATNSimulator` (le moteur de prédiction d'ANTLR4) domine le haut de pile pendant une
lecture qui ne fait que répéter la MÊME requête. Un chemin par élément ou par champ **re-parse une expression** du
langage de garganttua au lieu de réutiliser un AST déjà compilé. C'est le poste unitaire le plus gros et il n'est cité
par aucune fiche existante.

**2. Lecture BSON par clé-chaîne (LU).** `MongoDocumentReader.documentToDto` (l. 56) itère les champs déclarés du type
et, pour chacun, `applyField` → `readRawValue` (l. 107) cherche la clé **par chaîne** dans le `Document`
(`doc.containsKey`/`doc.get` → `HashMap.getNode` + `String.equals`), champ par champ, élément par élément
(`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDocumentReader.java`).

**3. Affectation par `MethodHandle` générique (LU + MESURÉ).** `field.set` → `MethodHandleObjectFieldAccessorImpl.set`
(~10 % du temps propre, `set` 4,9 % + `get` 2,8 %), là où le processeur AOT sait engendrer un accesseur direct.

**4. Résolution de membre/adresse par appel (LU ; PETIT ici, GROS ailleurs).** `ObjectQuery.findAllRecursively`
(`api/core`… → `core/reflection/query/ObjectQuery.java:167`) → `MemberLookup.getField` + `DirectAddresses.resolve`
(clé `ShapeKey` reconstruite et hachée par appel). Sur une lecture de liste ce coût est **faible** (le décodage itère
les champs déclarés, le mapper pré-résout ses accesseurs) — mesuré ~25 ms. Il est **dominant sur la vérification du
jeton** (voir `la-verification-du-jeton-coute-la-moitie-de-chaque-requete.md`), pas sur la lecture.

## Attendu

Que le coût **par type** soit payé **une fois par type**, pas une fois par élément/champ :

- **un AST d'expression mis en cache** (par script / par type) : ne jamais re-parser une expression dans le chemin
  chaud — c'est le plus gros gain mesuré (~1/4 du CPU de la lecture) ;
- **un lecteur BSON par type** qui connaît l'ordre et le nom de ses champs (fin des recherches de clé par chaîne) ;
- **un accesseur direct** engendré par l'AOT au lieu du `MethodHandle` générique ;
- une **poignée de membre résolue une fois** et gardée (le point 4, surtout pour l'auth).

Ces caches ne dépendent que du code compilé et des types : **locaux et immortels**, sans invalidation
(voir `un-cache-du-cadre-local-et-distribue.md`, niveau L0). Gain attendu, cumulé, sur cette lecture : la **majorité**
du temps par élément ; et sur TOUT consommateur du cadre.

## Ce que la fiche ne demande PAS

- Ne pas renoncer à l'AOT pur ni réintroduire la réflexion d'exécution.
- Ne pas changer la sémantique des scripts ni du langage d'expression.
- Ne pas forcer une projection ni cacher les **données** (c'est O(éléments) qui coûte, pas la base ; réduire le
  NOMBRE d'éléments reste au consommateur — ex. agrégation au dézoom d'une carte). Le cache de **tuiles** d'ALPHA37 ne
  règle rien ici : il re-paie décodage + mapper + sérialisation à chaque hit (mesuré par palliad, banc « cache de
  tuiles »).
- Ne pas confondre avec le coût PAR REQUÊTE de la vérification du jeton (fiche dédiée) : lui ne grandit pas avec le
  nombre de lignes.

## Priorité suggérée (la décision reste à la plateforme)

1. **AST d'expression en cache** — ~26 % du CPU d'une lecture, et de toute évaluation d'expression. Le plus rentable, et
   nouveau.
2. **Lecteur BSON par type + accesseur direct** — ~1/3 du CPU de la lecture (points 2 et 3).
3. **Poignée de membre par type** (point 4) — surtout pour l'auth.

Cette fiche **remplace**, pour le cas des lectures, `le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md`
(qui reste valable pour le chemin d'authentification) et complète `le-dto-ne-restreint-pas-la-lecture-mongodb.md`.

## Réponse — 2026-11-23, sur `main`, à paraître dans `3.0.0-ALPHA37`

### 1. Le parsing ANTLR n'est pas dans la lecture : il est dans le démarrage

Relu dans vos propres échantillons (`docs/benchmark/charge/resultats-banc-9b-samples.txt`, 852 échantillons) :

- les **249** échantillons ANTLR sont **tous** sur le thread `main`, entre 18:44:26,9 et 18:44:30,0 ;
- leur pile est `ScriptCompiler.parseStatements` ← `ScriptContext.compile` ← la construction des workflows des
  domaines. C'est le démarrage de l'application ;
- **aucun** n'est sous `getEntitiesPage` (289 échantillons).

L'enregistrement part du lancement de la JVM (`-XX:StartFlightRecording` au démarrage, `dumponexit`), et les parts par
famille ont été calculées sur tout l'enregistrement. Le temps de compilation des scripts au démarrage a donc été compté
comme du temps de lecture.

Le cadre ne reparse rien par requête sur ce chemin :
- le workflow d'un domaine est précompilé une fois (`DomainWorkflowAssembler` : `precompile(true)`) ;
- ses `include()` sont compilés à la construction (`warmUpIncludedScripts`), puis lus dans un cache ;
- notre reproduction (30 000 lignes de 12 champs, vrai MongoDB, à travers `READ_ALL`) ne montre **aucun** échantillon
  ANTLR pendant la lecture.

Pour la prochaine mesure : démarrez l'enregistrement après la chauffe (`jcmd <pid> JFR.start …`), ou filtrez sur les
threads de requête.

Vos échantillons, recalculés sur la seule lecture :

| Où | Part de la lecture |
|---|---:|
| mappeur DTO → entité (`Mapper.map`) | 44,6 % |
| DAO MongoDB (`MongoDao.find`), dont décodage document → DTO 24,6 % | 39,8 % |
| écriture et lecture de champ par réflexion (`MethodHandleObjectFieldAccessorImpl.set`/`get`), en temps propre | 22,5 % |
| `HashMap.getNode` / `putVal` / `resize`, en temps propre | 22,9 % |
| ANTLR | 0 % |

### 2. Ce qui est corrigé : le coût par élément et par champ

Rien n'a changé dans ce que fait une lecture. Seul ce qui ne dépend que du type est désormais calculé une fois :

- **`FieldAccessor`** (toute lecture ou écriture de champ du cadre, mappeur compris).
  - *Avant :* à chaque accès, un `FieldAccessManager` neuf refaisait `setAccessible(true)` (vérifications de sécurité
    comprises), relisait les modificateurs, revérifiait le nom contre l'adresse et émettait trois ou quatre journaux.
  - *Après :* le champ d'une adresse simple est résolu à la construction et ouvert une fois. Les erreurs sont les mêmes,
    levées au même moment.
- **`Mapper.map`**, appelé une fois par élément.
  - *Avant :* un `UUID.randomUUID()` (un tirage `SecureRandom`) était fait à chaque appel puis jeté, puisqu'une session
    parente existe toujours dans une requête. Le libellé d'observabilité était construit même sans observateur, et la
    configuration de mapping cherchée deux fois.
  - *Après :* l'identifiant n'est tiré que s'il sert, le libellé n'est construit que si quelqu'un observe, et la
    configuration est cherchée une fois.
- **`Repository`, avec un seul DTO.**
  - *Avant :* une `LinkedHashMap` et une `ArrayList` par ligne pour « fusionner » des DTO, et l'uuid de chaque DTO lu par
    son nom à travers toute la réflexion.
  - *Après :* chaque DTO va directement au mappeur, et le champ uuid est résolu une fois.
- **`MongoDocumentReader`.**
  - *Avant :* à chaque document, parcours de la hiérarchie, filtre `static`/`transient` et `setAccessible`. Puis, par
    champ, `containsKey` suivi de `get`.
  - *Après :* la liste des champs est calculée une fois par classe, et chaque champ ne coûte qu'un `Document.get`. Les
    cas « absent », « présent à `null` » et « uuid lu dans `_id` » se comportent comme avant.

### 3. La mesure

`ListReadBenchmarkTest` (module `api/bench/cache-benchmark`, profil `-Pbench`) : 30 000 lignes de 12 champs, vrai
MongoDB 7, lues à travers `READ_ALL`, en JVM.

| | Médiane | Par ligne |
|---|---:|---:|
| `3.0.0-ALPHA37` tel que livré | 198–211 ms | 6,6–7,0 µs |
| après | **134 ms** | **4,5 µs** |

C'est **−33 %**. La moitié des échantillons de la lecture est maintenant dans le pilote MongoDB : décodage BSON et
construction des `Document`.

### 4. Ce qui reste, et pourquoi pas dans ce passage

- **L'accès direct aux champs privés en AOT** : `AOTField.set`/`get` passent encore par `java.lang.reflect.Field`. La
  piste est une `VarHandle`, qui écrit le champ lui-même, donc avec la même sémantique, là où un setter changerait la
  sémantique. Elle touche le natif : elle doit passer l'E2E natif avant d'être livrée.
- **Un décodeur BSON par type**, qui remplirait le DTO directement depuis le flux BSON, sans passer par `Document`. Il
  retirerait le `Document.get` qui reste par champ et la construction du `Document` par le pilote. C'est un gros
  chantier ; nous le mesurerons après le précédent.
- **Le point 4 de la fiche** (poignée de membre résolue une fois) est traité dans
  `le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md`.
