# Un DTO allégé ne restreint pas la lecture MongoDB, et `fields=` ne compense pas toujours son coût de remise en forme

**À l'attention de :** garganttua-api (`dao-mongodb`, `core`)
**Émis par :** palliad (v3, AOT pur, binaire natif GraalVM en production, MongoDB 4.4)
**Date :** 2026-10-15
**Version constatée :** garganttua-api `3.0.0-ALPHA24`
**Gravité :** performance, pas correction. Un domaine allégé posé sur une collection riche lit chaque document
entier, et le seul moyen de restreindre la lecture (`fields=`) ajoute un coût qui grandit avec le nombre de champs
demandés : selon le volume et la machine, la réponse en sort plus rapide ou **plus lente** (plus lente en production,
mesuré). Le consommateur n'a donc pas de levier fiable côté base.

## Symptôme

Palliad déclare deux domaines sur la même collection `healthcare-organizations` : le domaine complet
(`HealthcareOrganizationDto`, documents de 1,2 Ko en moyenne avec services et structure interne) et un jumeau en
lecture seule pour les cartes, `care-center-summaries`, dont le DTO ne déclare que 12 champs. L'idée du jumeau
était que la lecture en base ne porte que sur ces 12 champs.

**Mesuré** (profileur MongoDB niveau 2, 66 documents, même filtre) :

| Lecture | Projection reçue par MongoDB | Octets renvoyés par MongoDB |
| --- | --- | --- |
| `GET /healthcare-organizations` (DTO complet) | aucune | 65 840 |
| `GET /care-center-summaries` (DTO de 12 champs) | aucune | 65 840 |
| `GET /care-center-summaries?fields=<les 12 champs>` | les 12 champs | 16 024 |

La sélection explicite fait bien lire 76 % de moins. Mais la réponse HTTP, elle, ralentit en natif.

**Mesuré** (même binaire, mêmes données, en administrateur, appels alternés et appariés) :

| Contexte | Sans `fields=` | Avec `fields=` | Écart |
| --- | --- | --- | --- |
| Natif, prod, 2 848 lignes, 40 paires | 239 ms | 264 ms | **+11,7 %**, plus lent dans 36 paires sur 40 |
| Natif, prod, 32 807 lignes, 20 paires | 2 450 ms | 2 771 ms | +10,4 % (VM bruitée, quartiles −17 % / +26 %) |
| JVM, poste local, 2 090 lignes | 85 ms | 69 ms | −19 % |

Pour situer : sur les 32 807 lignes, la requête MongoDB seule prend 145 ms et la lecture des documents environ
0,5 s ; le reste des 2,4 s est le coût par ligne du cadre (décodage, DTO → entité, contrôles, JSON).

## Cause

**Lu** dans les sources, non instrumenté :

1. Sans `fields=`, `MongoDao.find(pageable, filter, sort)` appelle la variante à projection avec
   `Optional.empty()` (`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDao.java`, l. 119-122) ;
   `applyProjection` (l. 193) ne fait alors rien : MongoDB renvoie le document entier, et `MongoDocumentReader`
   n'en recopie qu'ensuite les champs du DTO. Le DTO ne sert pas à construire la projection.
2. Avec `fields=`, `READ_ALL.gs` (l. 39-40) pousse bien la projection au DAO via `effectiveDaoProjection`
   (`api/core/src/main/java/com/garganttua/api/core/expression/CrudExpressions.java`, l. 168), puis remet en
   forme le résultat par `applyProjection` → `projectFields` (l. 213) : chaque entité devient une
   `LinkedHashMap`, chaque champ demandé y étant relu par `REFLECTION.getFieldValue` (l. 239). Ce travail est
   fait même quand les champs demandés sont exactement tous ceux de l'entité, où il ne change rien à la réponse.
   Ce coût est payé par ligne et par champ : voir la mesure complémentaire ci-dessous, qui le chiffre sans
   pouvoir le séparer de l'écriture JSON de ces mêmes champs.

### Mesure complémentaire — natif, poste local (2026-10-15)

**Mesuré** : binaire natif identique à la production (`palliad` 1.4.34), copie des données de production, appels en
administrateur, variantes **entrelacées** au hasard tour par tour (12 tours France, 30 tours BFC), même filtre de zone.

| `care-center-summaries` | France, 32 807 lignes | BFC, 2 848 lignes | JSON (France) |
| --- | --- | --- | --- |
| sans `fields=` | 991 ms | 76,0 ms | 7 935 Ko |
| `fields=` 1 champ | 277 ms | 46,9 ms | 835 Ko |
| `fields=` 3 champs | 411 ms | 54,0 ms | 2 634 Ko |
| `fields=` 6 champs | 555 ms | 66,4 ms | 4 439 Ko |
| `fields=` 12 champs (réponse identique à « sans ») | **821 ms (−17 %)** | **89,1 ms (+17 %)** | 7 935 Ko |
| MongoDB seul (pilote Python), sans / avec projection 12 champs | 455 → 121 ms | 42,7 → 19,3 ms | |

Ce que cela établit, et ce que cela n'établit pas :

- Le temps croît **linéairement** avec le nombre de champs demandés : ≈ 1,4 µs **par champ et par ligne**, la même
  pente sur les deux zones (France : 49 ms par champ sur 32 807 lignes ; BFC : 3,8 ms sur 2 848). C'est compatible
  avec la relecture réflexive de `projectFields`, mais la pente inclut aussi l'écriture JSON de chaque champ : la part
  propre à `projectFields` n'est **pas isolée** (pas de profileur sur l'image native).
- Le côté base gagne nettement avec la projection (−73 % France, −55 % BFC).
- Le bilan net de `fields=` **n'est pas systématiquement négatif** : −17 % sur la France, +17 % sur la BFC en local ;
  +10 à +12 % sur les deux en production, où MongoDB partage la machine. Il dépend de l'équilibre entre ce que la base
  économise (proportionnel à la taille des documents) et ce que coûte la remise en forme (proportionnel au nombre de
  champs demandés).

## Attendu

1. **Projection par défaut d'après le DTO.** Sans sélection demandée, `MongoDao.find` projette sur les champs que
   le DTO déclare (traduits en champs de document, plus `uuid` et les compositions, comme `applyProjection` le fait
   déjà pour une sélection). C'est sûr par construction : `MongoDocumentReader` ne recopie de toute façon que ces
   champs, la réponse est donc identique. Pour un DTO complet la projection ne retire rien ; pour un DTO allégé
   posé sur une collection riche, elle divise la lecture. Esquisse :

   ```java
   Optional<List<String>> effective = projection != null && projection.isPresent() && !projection.get().isEmpty()
           ? projection
           : Optional.of(this.config.dtoFieldNames()); // calculé une fois, au montage du DAO
   applyProjection(iterable, effective);
   ```

   Un réglage par domaine pour revenir à la lecture complète serait prudent, si un consommateur dépend d'un champ
   lu hors DTO (nous n'en connaissons pas).

2. **Une sélection qui ne retire rien ne coûte rien.** (Indépendante du doute ci-dessus : quelle que soit sa part
   exacte, une remise en forme qui ne change pas la réponse n'a pas à être payée.) Quand l'ensemble demandé égale l'ensemble des champs de
   l'entité (comparé une fois par requête), sauter `projectFields` : la projection en base reste, l'entité sort
   telle quelle. Pour une sélection partielle, lire les champs par les accesseurs AOT déjà générés plutôt que par
   la réflexion générique, ou sérialiser l'entité en ignorant les champs non demandés.

### Critères d'acceptation proposés

- [ ] Sans `fields=`, le profileur MongoDB montre une projection égale aux champs du DTO (+ `uuid`, compositions),
      sur un read-all et un read-one.
- [ ] Réponses identiques avant et après, champ pour champ, compositions résolues et crochets `afterGet` compris.
- [ ] Un DTO allégé sur une collection riche lit au moins 70 % d'octets de moins (cas palliad : 65 840 → environ
      16 000 sur 66 documents).
- [ ] Avec `fields=` couvrant toute l'entité, aucune table intermédiaire construite, et le temps natif n'est pas
      plus long que sans `fields=`.
- [ ] `MongoDaoRoundTripTest`, `MongoDaoPagingAndFilterValuesTest`, `MongoDaoCompositionTest` verts, plus un test
      sur un DTO allégé.

## Ce que la fiche ne demande pas

- Aucun changement de contrat HTTP : `fields=` garde son sens, sa validation (champ inconnu → 400) et sa forme de
  réponse.
- Pas de projection côté PostgreSQL : nous ne l'utilisons pas. La question reste de savoir si `dao-postgresql`
  sélectionne déjà les colonnes du DTO.
- Pas d'index géographique : la lenteur mesurée n'est pas dans la requête (145 ms sur 2,4 s).

## Questions ouvertes

- Le filtre peut-il porter sur un champ que le DTO ne déclare pas ? Palliad filtre `care-center-summaries` sur
  `categoryLabel`, retiré du DTO allégé, et cela fonctionne (407 lignes, comme le domaine complet) : comportement
  garanti, ou de fait ? La demande 1 ne doit pas le casser — la projection ne touche que les champs RENVOYÉS, pas
  ceux du filtre.
- Version cible : une `ALPHA25`, ou un correctif sur `ALPHA24` ?

## Côté palliad, en attendant

Palliad n'envoie pas `fields=` sur sa carte (essayé et annulé le 2026-10-15 : plus lent en natif). Trace
consommateur : `palliad/docs/patches/garganttua-dto-projection.md`.

---

## Réponse de la plateforme — 2026-10-15

**Demande 1 tenue telle qu'écrite. Demande 2 tenue sur le coût, refusée sur la forme** — et la
raison du refus est dans la réponse au contrat HTTP que vous excluez vous-mêmes. Sur `main`, à
paraître dans `3.0.0-ALPHA25`.

### 1. Le DTO est la projection par défaut — **MESURÉ**

`MongoDao.registerDomain` calcule une fois la liste des champs que le DTO mappe (champs d'instance,
hiérarchie comprise, `static` et `transient` exclus — exactement ce que `MongoDocumentReader` lit).
Une lecture sans sélection la pousse en projection, plus l'uuid et les compositions, comme une
sélection explicite. Le chemin est le même pour le read-all et le read-one : les deux passent par
`Repository.queryDtoContext` → `find` à trois arguments, qui délègue à la variante projetante.

Banc sur un vrai `mongod`, profileur niveau 2, votre forme réduite — 66 documents d'environ 1,3 Ko
(huit services imbriqués), un DTO de trois champs
(`api/dao/dao-mongodb/src/test/java/com/garganttua/dao/mongodb/MongoDaoDtoProjectionTest.java`) :

| Lecture | Projection reçue par MongoDB | `responseLength` |
|---|---|---|
| avant (ou `readWholeDocuments()`) | aucune | 85 606 octets |
| après | `{uuid, name, city}` | **5 848 octets** (−93 %) |

Le test compare aussi les deux réponses DTO par DTO : identiques. Votre « sûr par construction »
tient, et c'est lui qui nous a dispensés d'un réglage par défaut prudent.

**Le réglage de retour que vous suggériez existe, par domaine :** `new MongoDao(db, "x").readWholeDocuments()`.
Il n'a pas de clé de configuration : un domaine construit par le starter n'en a pas besoin tant que
personne ne lit les documents bruts à travers le DAO, et nous n'en connaissons pas plus que vous.

**Rupture à connaître.** `MongoDaoProjectionTest.noProjectionNoPushdown` affirmait l'inverse — « sans
projection, `.projection()` n'est jamais appelé ». C'était le contrat écrit ; il est remplacé par
`noProjectionProjectsTheDto`, et la ligne est annoncée dans le README du DAO.

### 2. `fields=` plus lent en natif : la cause était plus précise que la réflexion

Votre hypothèse était juste dans son genre, pas dans son grain. Ce n'est pas la lecture réflexive
en soi : c'est que `REFLECTION.getFieldValue(entity, field)` **résolvait le champ à nouveau** pour
chaque ligne et chaque champ — `provider.getClass`, `FieldResolver.fieldByFieldName`, un
`FieldAccessor` neuf, et leurs journaux paramétrés — alors que le champ venait d'être résolu, une
fois, quatre lignes plus haut pour la validation.

`projectFields` construit désormais les accesseurs **une fois par requête** et ne fait plus que les
lire par ligne. Mesuré en JVM sur votre volumétrie (32 807 lignes × 12 champs `String`, six tours) :

| | Avant | Après |
|---|---|---|
| lecture des champs projetés | 170 – 370 ms | **15 – 20 ms** |

**Non mesuré en natif.** Nous n'avons pas de banc natif ici ; le chemin supprimé est celui qui paie
le plus en natif (pas de JIT pour l'inliner), mais c'est une inférence. Les +10–12 % sont à
re-mesurer chez vous, avec et sans `fields=`, comme vous les avez mesurés.

### Ce qui n'est pas fait : sauter `projectFields` quand la sélection couvre l'entité

Refusé, parce que ce ne serait **pas** sans effet sur la réponse. Sans `fields=`, la réponse est
l'entité sérialisée par Jackson, qui suit les accesseurs publics ; avec `fields=`, c'est une table
des champs demandés, lus directement. Les deux diffèrent dès qu'un champ n'a pas d'accesseur (il
apparaît dans la table, pas dans l'entité), qu'un accesseur est calculé (l'inverse), ou qu'un champ
est nul. Rendre l'entité « parce que tout est demandé » changerait la forme de la réponse pour un
client qui demande tout — exactement ce que votre « aucun changement de contrat HTTP » exclut. Le
point 2 rend de toute façon ce raccourci inutile : la remise en forme ne coûte plus que la table.

« Lire par les accesseurs AOT déjà générés » : c'est ce que fait maintenant `FieldAccessor` quand le
descripteur existe — la différence était la résolution répétée, pas le moteur de lecture.

### Vos questions ouvertes

- **Filtrer sur un champ que le DTO ne déclare pas : garanti.** Le filtre est traduit en champs de
  document sans jamais consulter le DTO, et une projection ne restreint que ce qui revient. C'était
  vrai de fait ; c'est maintenant épinglé — `filterOnUnmappedFieldStillMatches` filtre sur
  `categoryLabel`, absent du DTO, et retrouve ses 22 documents sur 66.
- **Version :** `ALPHA25`. Il n'y a pas de branche de correctifs sur les ALPHA.
- **PostgreSQL — LU :** `PgReader.selection` ne sélectionne que les colonnes de la table, et la
  table est dérivée du DTO. Le `SELECT` y est donc déjà restreint au DTO par construction.

### Critères d'acceptation

- [x] Projection égale aux champs du DTO (+ `uuid`, compositions) sans `fields=` — profileur, read-all ;
      le read-one emprunte le même `find` (lu, pas profilé séparément).
- [x] Réponses identiques avant/après, champ pour champ — sur le DAO. **Les crochets `afterGet` ne sont
      pas éprouvés** dans ce banc : ils s'appliquent à l'entité issue du DTO, qui est la même.
- [x] Au moins 70 % d'octets en moins pour un DTO allégé — 93 % ici.
- [ ] `fields=` couvrant toute l'entité sans table intermédiaire — **refusé**, voir plus haut ; le
      temps natif est à re-mesurer chez vous.
- [x] `MongoDaoRoundTripTest`, `MongoDaoPagingAndFilterValuesTest`, `MongoDaoCompositionTest` verts
      (113 tests du module, 0 échec), plus le banc sur DTO allégé.
