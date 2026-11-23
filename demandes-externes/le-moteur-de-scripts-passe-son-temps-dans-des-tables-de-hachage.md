# Le moteur de scripts passe son temps dans des tables de hachage

**À l'attention de :** garganttua-core (`script`, `runtime`, `reflection`, `aot`) et garganttua-api (`dao-mongodb`)
**Émis par :** palliad (v3, AOT pur, natif G1 + PGO et JVM HotSpot)
**Date :** 2026-11-05
**Version constatée :** garganttua `3.0.0-ALPHA33`
**Gravité :** structurelle. Le cadre est le premier consommateur de processeur d'une requête, loin devant la base de
données. C'est le levier le plus large sur la capacité de tous les consommateurs, pas seulement de palliad.

> **Consolidée (2026-11-23).** Pour le cas des LECTURES DE LISTE, voir la fiche globale
> `une-lecture-de-liste-reconstruit-chaque-element-et-reparse-des-expressions.md` : elle mesure (sonde + JFR) le coût
> par élément et ajoute un poste que celle-ci ne citait pas — le **parsing d'expressions ANTLR** (~26 % du CPU d'une
> lecture). La présente fiche reste la référence pour le chemin d'AUTHENTIFICATION, où la résolution de membre domine.

## Symptôme (mesuré)

JVM HotSpot, 4 vCPU / 2 Go, MongoDB, parcours réels de palliad, Java Flight Recorder (23 735 échantillons) :

| Part du processeur, appels imbriqués compris | |
|---|---|
| sous `com.garganttua.core.runtime.RuntimeStep` | **84 %** |
| sous le pilote MongoDB | 11 % |
| sous Jetty / Javalin | 10 % |
| sous Jackson | 4 % |

Le temps **propre** (le cadre en haut de pile) se concentre dans des structures du JDK : 47 % dans les collections et
les chaînes. Premiers appelants dans le cadre, par méthode chaude :

| Méthode chaude | Part des échantillons | Premiers appelants garganttua |
|---|---|---|
| `HashMap.getNode` | 5,5 % | `MongoDocumentReader.readRawValue` (31 %), `IKeyBsonBridge.isKeyDocument` |
| `ConcurrentHashMap.computeIfAbsent` | 4,6 % | `HotPathProbe.end` (voir la dernière section), `MemberLookup.getField`, `MemberLookup.getMethods`, `DirectAddresses$ShapeKey.hashCode` |
| `MethodHandleObjectFieldAccessorImpl.set` | 3,7 % | `AOTField.set` (100 %) |
| `String.equals` | 2,1 % | `MongoDocumentReader.readRawValue` (51 %) |
| `ConcurrentHashMap.get` | 2,0 % | `AOTRegistry.get` (74 %), `PropertyProvider.getProperty` |
| `HashMap.putVal` | 3,8 % | `MongoDao.find`, `MethodResolver.methodByMethod`, `ObjectAddress.detectLoop` |

## Cause (lu dans les piles, à confirmer)

Trois familles de coût par appel, là où un coût par **type** suffirait :
- **résolution des membres** (`MemberLookup`, `DirectAddresses`, `AOTRegistry`) : une recherche par nom et un
  `computeIfAbsent` à chaque appel, avec le hachage d'une clé composite, au lieu d'une poignée résolue une fois et
  gardée par le pas de script ou par le type ;
- **affectation de champ** par `MethodHandle` générique (`AOTField.set`) plutôt que par l'accesseur direct que le
  processeur AOT sait engendrer ;
- **lecture d'un document MongoDB** (`readRawValue`) : recherche des clés par chaîne, champ par champ.

## Attendu

Mettre en cache, par pas de script et par type, ce qui ne dépend que du type : membres résolus, poignées d'accès,
convertisseurs de champs. Pour la lecture BSON, un lecteur par type qui connaît l'ordre et le nom de ses champs.

**Ces caches restent locaux, et c'est voulu.** Ils ne dépendent que du code compilé et des types. Ils sont donc
identiques sur tous les nœuds d'une même version, et ne s'invalident jamais pendant la vie du processus. Un cache
distribué (Redis) n'apporterait que le coût d'un aller-retour réseau, là où l'on cherche à gagner des microsecondes.
Les caches de **données** (autorisations, autorités, configuration), eux, doivent être distribués : voir
`un-cache-du-cadre-local-et-distribue.md`.

## Ce que la fiche ne demande pas

- Ne pas renoncer à l'AOT pur ni réintroduire la réflexion d'exécution.
- Ne pas changer la sémantique des scripts.

## Note sur la mesure : `HotPathProbe` se dispute ses compteurs

Avec `-Dgarganttua.perf.probe=true`, le débit tombe de 2 à 10 % à 1 et 2 vCPU, mais de **25 à 30 % à 4 vCPU**. Les
libellés sont construits par concaténation à chaque appel (`"step.total:" + stepName`), même sonde éteinte, et chaque
`end` passe par `COUNTERS.computeIfAbsent`. Des compteurs résolus une fois par pas de script éviteraient la
contention et feraient de la sonde un outil utilisable en charge. Le collecteur de palliad
(`PerfObservability.record`) a le même défaut, et il sera corrigé côté palliad.

## Réponse — 2026-11-23, sur `main`, à paraître dans `3.0.0-ALPHA37`

### Ce qui est fait

1. **La sonde ne se dispute plus ses compteurs.** `HotPathProbe.site(label)` résout un point de mesure une fois, et
   `Site.end(t)` enregistre l'appel sans rien construire ni chercher.
   - Les 15 points de mesure du cadre sont convertis. Ceux de `RuntimeStep` sont résolus à la construction du pas, ce
     qui supprime la concaténation `"step.total:" + stepName` à chaque exécution, **sonde éteinte comprise**.
   - Sonde éteinte, tous les points de mesure sont le même objet, qui ne fait rien.
   - `reset()` remet les compteurs à zéro au lieu de les retirer, et le rapport ignore les compteurs à zéro : le rendu
     ne change pas.
   - `HotPathProbe.end(String, long)` reste disponible pour votre collecteur.
2. **La résolution par nom à chaque appel**, le chemin de l'authentification.
   - *Avant :* `getFieldValue(objet, "nom")` refaisait à chaque appel toute la chaîne : le `IClass`, un `ObjectQuery`
     neuf (avec `initWellKnownClasses`), un `ObjectAddress` neuf (avec la détection de boucle), `MemberLookup` et un
     accesseur neuf.
   - *Après :* `FieldDelegate` garde l'accesseur résolu pour chaque couple (type, nom) et (type, adresse). Une
     résolution qui échoue n'est pas gardée : elle échoue à nouveau, avec la même exception.
   - Le profil JFR de la vérification attribuait 42 % de son temps à ce chemin.
3. **Les arguments de journal calculés pour rien.** L'en-tête de `RuntimeStep` est construit une fois, et
   `ObjectAddress` ne calcule plus un `Arrays.toString` pour un `debug` éteint.
4. **L'écriture de champ et le lecteur MongoDB** sont traités avec la fiche consolidée
   `une-lecture-de-liste-reconstruit-chaque-element-et-reparse-des-expressions.md` : ouverture des champs une fois, un
   seul `Document.get` par champ.

### La mesure

`SecurityPipelinePerfTest` (api-core, dépôt en mémoire, JVM). Les médianes sont prises sur 5 passages par version,
parce qu'un seul passage varie de ±50 % à cette échelle :

| | `3.0.0-ALPHA37` livré | après | |
|---|---:|---:|---:|
| vérification, 3 autorités | 15,3 µs | **8,5 µs** | −44 % |
| vérification, 219 autorités | 15,5 µs | **8,8 µs** | −43 % |
| vérification sans état | 24,8 µs | **18,5 µs** | −25 % |
| vérification avec relecture du jeton stocké | 13,8–15,0 µs | 13,2–14,3 µs | dans le bruit |
| authentification (bcrypt) | 4,2 ms | 4,0 ms | dans le bruit |

La contention de la sonde à 4 vCPU n'a pas été remesurée ici, faute d'un banc concurrent sonde allumée. La cause est
retirée par construction : plus de `computeIfAbsent` par appel, des `LongAdder` par point de mesure. Votre banc à
4 vCPU dira le reste.

### Ce qui reste

- **`AOTField.set`/`get` par `MethodHandle` générique** : la `VarHandle`, à prouver en E2E natif. C'est le même
  chantier que dans la fiche consolidée.
- **Les méthodes résolues par nom** (`MethodResolver`, `MemberLookup.getMethod(s)`) passent encore par une table à
  chaque appel. Ce passage n'a gardé que les champs, que le profil désignait.
- **`AOTRegistry.get`** : il reste une recherche par nom de classe à chaque `IClass.getClass`. Une `ClassValue` la
  retirerait ; elle est à valider en natif avec le point précédent.

## Réponse, suite — 2026-11-23, sur `main`, dans `3.0.0-ALPHA37` : le cache L0 et le reste des tables de hachage

### Le cache L0 a maintenant un contrat

Le niveau L0 de `un-cache-du-cadre-local-et-distribue.md` (la structure : membres résolus, accesseurs, invocateurs,
lecteurs par type) n'était qu'un ensemble de tables, chacune écrite à sa façon. Il a maintenant un contrat, dans
`garganttua-commons` :

- **`StructureCache<K, V>`** : une valeur calculée une fois par clé et gardée pour la vie du processus.
  - **Un succès coûte une lecture, sans verrou.** Les `computeIfAbsent` appelés à chaque accès, que les threads se
    disputaient sous charge, ne servent plus qu'en cas d'absence.
  - **Un échec n'est jamais gardé** : l'appel suivant échoue de la même façon.
  - **Une valeur ne peut pas être nulle**, et des premiers appels concurrents reçoivent la même valeur.
  - **`StructureCache.sizes()`** donne le nombre d'entrées de chaque cache, par nom : c'est la seule chose à surveiller
    dans un cache qui ne rétrécit jamais.
- **`ClassStructureCache<V>`**, quand la clé est la classe Java elle-même. Il repose sur une `ClassValue`, qui ne
  hache rien. Micro-banc contre `ConcurrentHashMap.computeIfAbsent` : 2,4 contre 7 ns en JVM, 8 contre 11 à 15 ns en
  natif.
- **Ce n'est pas un `ICache`** : un cache L0 n'expire pas, ne s'invalide pas et ne quitte pas le processus. Le
  distribuer coûterait un aller-retour là où l'on gagne des nanosecondes.

### Les tables qui passent dessus

| Cache L0 | Avant | Maintenant |
|---|---|---|
| `IClass` d'une classe Java, JVM (`RuntimeClass`) | `computeIfAbsent` à chaque `getType()`, `getDeclaringClass()` et `getClass()` | `ClassStructureCache` |
| `IClass` d'une classe Java, AOT (descripteurs générés) | recherche par nom dans `AOTRegistry` à chaque appel | `ClassStructureCache`. Seuls les descripteurs générés y sont gardés : ils ne sont jamais remplacés, alors qu'un descripteur synthétisé doit pouvoir l'être |
| champs lus par nom ou par adresse (`FieldDelegate`) | résolution complète à chaque appel (premier passage : deux tables) | `StructureCache`, une seule clé (type, nom) |
| méthodes invoquées par nom (`MethodDelegate`) | résolution complète à chaque appel | `StructureCache` par forme d'appel (type, nom, retour, types d'arguments) |
| membres et formes d'adresse (`MemberLookup`, `DirectAddresses`) | `computeIfAbsent` à chaque appel | `StructureCache` |
| champs d'un DTO MongoDB (`MongoDocumentReader`) | parcours de la hiérarchie à chaque document | `StructureCache` |
| configuration du mappeur | une recherche dans une table par élément | le dernier couple (source, destination) gardé, protégé par un numéro de version : une configuration enregistrée entre-temps n'est jamais ignorée |

### Ce qui le prouve

- `StructureCacheTest` : calcul unique, échec jamais gardé, valeur nulle refusée, 16 threads qui reçoivent la même
  valeur, tailles par nom, `ClassValue` avec valeur nulle permise.
- `MethodCallCacheTest` : surcharge choisie selon le type des arguments, redéfinition dans une sous-classe, méthode
  absente qui échoue à chaque appel.
- `LastMappingTest` : deux couples en alternance, configuration enregistrée entre deux mappings.
- Les suites existantes de la réflexion, du mappeur, de l'injection, du runtime, des scripts, d'api-core et des deux
  DAO.
- **L'E2E natif MongoDB** (binaire AOT pur, GraalVM 25) : toutes ses vérifications passent sur ces changements.

### Ce qui n'est pas fait, et pourquoi

- **La `VarHandle` pour les champs privés en AOT** est écartée : elle coûte 6 fois plus que `Field` en natif (mesures
  dans la fiche consolidée).
- **`Document.get` par champ** n'existe plus que pour les DTO à compositions : les autres sont décodés directement
  (même fiche).
