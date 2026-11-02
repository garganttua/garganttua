# Le DAO PostgreSQL ne pose aucun index, n'a pas de starter AOT, et n'indexe pas la géographie

**À l'attention de :** garganttua-api (`dao-postgresql`, `starters`)
**Émis par :** palliad (v3, AOT pur, binaire natif GraalVM en production, aujourd'hui sur MongoDB)
**Date :** 2026-11-02
**Version constatée :** garganttua-api `3.0.0-ALPHA28`
**Gravité :** bloquant pour passer un consommateur de MongoDB à PostgreSQL. Trois manques, d'effets différents :

1. **Unicité.** Sans index unique, une unicité déclarée par `entity().index(champ, true, …)` ne tient plus sous
   concurrence. Les doublons passent en silence. Ils ne se voient qu'en aval, sous forme de double facturation, de
   tâche de nuit exécutée deux fois ou de message relayé deux fois.
2. **Natif.** Le connecteur n'a pas de starter AOT. Un consommateur en natif pur ne peut donc pas le monter
   sans recomposer lui-même ce que `starter-aot-mongo-javalin` assemble, et sans savoir si le pilote JDBC et
   HikariCP survivent à l'image.
3. **Géographie.** Elle se limite à `$geoWithin`, en planaire et sans index GiST. Une recherche de
   proximité est donc impossible, et une recherche par zone parcourt toute la table.

## Contexte

Palliad envisage d'offrir PostgreSQL à côté de MongoDB, dans chacune de ses distributions :

- cloud et on-prem ;
- mono-nœud et cluster.

Les deux bases seraient au choix de l'exploitant. Un établissement de santé qui installe la version on-prem a
souvent une équipe qui exploite déjà PostgreSQL, et pas MongoDB.

Palliad ne touche à aucune base directement pour ses domaines : il passe par `IDao`. La bascule se ferait
donc en construisant un `PgDao` là où il construit aujourd'hui un `MongoDao`. Il ne réécrirait pas de requêtes.

La couverture actuelle du DAO est remarquable : 403 scénarios différentiels contre MongoDB, un filtre commun
par `ProtocolFilterJson`, des regex PCRE traduites, et `$text`. Les trois points ci-dessous sont ce qui manque
pour que ce choix soit offert sans réserve.

## A — Les index déclarés par le DSL ne sont pas posés, et un doublon n'est pas un conflit

### Symptôme

**Lu**, non exécuté sur PostgreSQL. Palliad déclare 31 index par `entity().index(...)` :

- **18 index uniques.** Exemples :
  - `TaskClaims.claimKey` : revendication d'une tâche périodique par un seul nœud ;
  - `relay-messages.dedupKey` ;
  - les clés métier des 7 domaines du registre de facturation, et `priceKey` ;
  - `contract-types.code`, `contract-options.code`, `subscriptions.livingKey` ;
  - `tenants.organizationKey` ;
  - `report-editions.editionKey`, `report-definitions.code`, et `tenantId` de l'état de projection ;
  - `team-favorite-pathologies.code`.
- **13 index ordinaires.** Exemples : `latitude`, `source`, `sharedTenantIds` (une liste), `status`,
  `definitionUuid`.

Sur MongoDB, ces index sont posés et un doublon rend 409. Sur PostgreSQL, aucun n'est posé.

L'unicité vérifiée par le cadre (lecture, puis écriture) ne suffit pas sous concurrence. **Mesuré** sur MongoDB
en ALPHA23, `LedgerStore`, 30 écritures concurrentes de la même clé :

| Configuration | Doublons |
| --- | --- |
| `unicity()` seul | 30 sur 30 |
| Avec l'index unique | 0 sur 30 |

Voir aussi `aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md`. Sur PostgreSQL, on retomberait dans le
premier cas, pour les 18 index uniques à la fois.

### Cause

**Lu.**

1. Le DAO ne lit jamais les index déclarés. `PgDao.registerDomain`
   (`api/dao/dao-postgresql/src/main/java/com/garganttua/dao/postgresql/PgDao.java`, l. 97-122) construit la
   table par `PgSchemaModel.of(...)` et la fait exister par `PgSchemaManager.ensure`. Aucune des deux ne lit
   les `EntityIndexRule` du domaine (`api/commons/.../entity/EntityIndexRule.java`).
2. Aucun `CREATE INDEX` n'est émis : `PgSchemaExpectation` ne produit que les tables, les colonnes et
   `CREATE EXTENSION postgis`.
3. Un refus d'unicité ne serait pas reconnu. `PgWriter` (l. 152-154) écrit en
   `INSERT … ON CONFLICT (<uuid>) DO UPDATE`, sans traduire une violation d'une AUTRE contrainte.
   - Une `PSQLException` de SQLState `23505` sortirait donc en erreur serveur (500).
   - `MongoDao.translateWriteFailure` (`api/dao/dao-mongodb/.../MongoDao.java`, l. 327-342) rend, lui,
     `ApiException.conflict` : un 409.

### Attendu

1. `registerDomain` pose les index des `EntityIndexRule` du domaine, comme `MongoIndexManager`.
   - **Portée `tenant`** : index composé `(tenantId, champ)`, même règle que `MongoIndexSpec.of` (l. 90-96).
   - **Index unique** : `CREATE UNIQUE INDEX … WHERE <champ> IS NOT NULL`. C'est l'équivalent du filtre
     partiel de `MongoIndexSpec.partialFilter` : l'unicité ne porte que sur les lignes où le champ est
     renseigné.
   - **Champ aplati** (`a__b`) : indexer la colonne aplatie.
   - **Champ liste** (table enfant `<table>__<champ>`) : indexer la colonne de valeur de la table enfant.
     L'unicité sur une liste peut être refusée au démarrage, avec un message clair : palliad n'en déclare pas.
   - Index nommé d'après `EntityIndexRule.name()`, en restant sous la limite de 63 caractères de PostgreSQL.
2. **Le même contrat de démarrage que Mongo** (`mongodb.index.auto`) :
   - un index existant de même définition ne change rien ;
   - une définition différente est signalée ;
   - en mode strict, un index unique impossible à poser sur des doublons existants fait échouer le
     démarrage. Palliad s'en sert avant chaque déploiement de migration.
3. **Un `23505` est traduit en `ApiException.conflict`** (409), avec le nom de la contrainte dans le
   message. Palliad rattrape ce conflit pour relire par la clé : c'est son idempotence sous concurrence.
4. **Un `23505` sur un `INSERT` ne laisse pas la connexion en transaction avortée.** Les tables enfants
   s'écrivent dans la même transaction.

## B — Un starter AOT pour PostgreSQL

### Symptôme

`api/starters/` contient `starter-aot-mongo-javalin` et `starter-jvm-mongo-javalin`, mais pas d'équivalent pour
PostgreSQL. Il n'y a que `starter-postgresql`, qui fait l'auto-configuration JVM (`PostgresAutoConfiguration`,
`GARGANTTUA_POSTGRESQL_URL`).

Palliad est construit en AOT pur : `garganttua-aot-native-feature`, aucune réflexion à l'exécution, binaire
natif statique musl en production. Il ne peut pas monter PostgreSQL sans recomposer le starter à la main.

Il ne sait pas non plus si ces éléments passent l'analyse `native-image` :

- le pilote `org.postgresql:postgresql` (chargement du `Driver` par `ServiceLoader`, SSL, SCRAM) ;
- HikariCP (proxys générés par Javassist en JVM) ;
- les décodeurs `PgJdbcDecoder` (JSONB, géométrie lue en GeoJSON).

Rien de cela n'a été essayé par le cadre en natif, à notre connaissance.

### Attendu

1. Un `garganttua-api-starter-aot-postgres-javalin`, de même forme que `starter-aot-mongo-javalin` :
   - `garganttua-aot-reflection` ;
   - `garganttua-aot-annotation-scanner` ;
   - `starter-bootstrap` ;
   - `starter-postgresql` ;
   - `starter-javalin`.
2. **Une épreuve native dans le dépôt du cadre** :
   - binaire construit, connexion à un PostgreSQL 17 avec PostGIS ;
   - écriture et lecture d'une entité avec un champ aplati, une liste (table enfant), un champ JSONB et une
     géométrie ;
   - un `$geoWithin` et un `$regex` ;
   - un doublon d'index unique qui rend 409.

   Il nous faut savoir si ce chemin est éprouvé en natif, comme MongoDB l'est.
3. **Les métadonnées natives nécessaires livrées par le starter** : `reflect-config`, `resource-config`, et
   les classes à initialiser à la construction pour le pilote et le pool. Aujourd'hui, c'est le consommateur
   qui les découvre au premier appel en production.
4. **La connexion TLS** (`sslmode=verify-full` dans l'URL, certificat racine fourni par fichier) éprouvée en
   natif. Un hébergeur de santé (HDS) impose le chiffrement en transit.

## C — Index géographique et PostGIS

### Symptôme

**Lu.** Côté géographie, PostgreSQL fait moins que MongoDB.

1. **Pas d'index sur une géométrie.** `IndexKind.geo` existe dans le DSL
   (`api/commons/.../annotations/IndexKind.java`) et devient un index `2dsphere` sous MongoDB. Sous
   PostgreSQL, aucun index GiST n'est posé (voir A) : un `$geoWithin` parcourt toute la table.
2. **Containment planaire.** `$geoWithin` / `$geoWithinSphere` se traduisent par `ST_Within` en SRID 4326
   (`PgScalarPredicates`, l. 31-32 et 130-140). Le commentaire le dit : c'est un test planaire en degrés, là
   où le `2dsphere` de MongoDB teste sur la sphère. Pour un grand polygone (une région, un département), les
   deux bases peuvent donc rendre des lignes différentes.
3. **Aucune proximité.** `$near` / `$nearSphere` (avec `$maxDistance`) et `$geoIntersects` ne sont pas
   traduits (`PgFilterTranslator`, l. 42). MongoDB ne les traduit pas non plus aujourd'hui, mais c'est ce
   que PostGIS sait le mieux faire.

### Ce que palliad en ferait

- **Annuaire des établissements de santé** (`healthcare-organizations`, environ 33 000 lignes avec les
  cabinets libéraux) :
  - la carte filtre aujourd'hui par une boîte de latitude/longitude à plat (`$gte`/`$lte` sur deux
    colonnes `double`) ;
  - la recherche « EMSP autour de moi » filtre ensuite dans le navigateur.

  Avec un point PostGIS indexé et `$near`, les deux se font en base, triées par distance.
- **Régions et départements** : polygones GeoJSON, pour rattacher un lieu à son territoire (`$geoIntersects`).

### Attendu

1. `IndexKind.geo` sur un champ géométrie (GeoJSON) → `CREATE INDEX … USING GIST (<colonne>)`. Avec la
   portée `tenant`, l'index peut rester sur la seule géométrie : un index GiST composé demande `btree_gist`.
2. **`$geoWithinSphere` (ou `$geoWithin`, au choix du cadre) évalué sur la sphère**, en `geography` ou par
   `ST_Covers` sur `geography`, pour retrouver le résultat du `2dsphere`. Le cadre choisit, mais la
   divergence ne doit pas rester silencieuse.
3. **`$near` / `$nearSphere` avec `$maxDistance`**, en `ST_DWithin` sur `geography` et `ORDER BY <->`. Les
   résultats sont triés par distance, comme MongoDB le fait pour `$near`. Si le tri par distance n'entre pas
   dans le contrat de `find`, le filtre de rayon seul suffit déjà.
4. **`$geoIntersects`**, en `ST_Intersects`.
5. **Une façon de déclarer un point à partir de deux colonnes plates** (latitude, longitude). Par exemple :
   `IndexKind.geo` sur un couple de champs → index d'expression
   `GIST (ST_SetSRID(ST_MakePoint(lon, lat), 4326))`, que `$near` sait viser. Faute de quoi, palliad
   migrera ses lignes vers un champ GeoJSON, ce qui reste possible.
6. **`IndexKind.text`** : un index GIN sur le `tsvector` que `$text` interroge (`PgTextSearch`). Il relève
   du même point A, et nous le signalons pour que la règle soit complète.

## Ce que cette fiche ne demande pas

- **Pas de changement de MongoDB.** `$near` et `$geoIntersects` y sont absents aussi. Les ajouter des deux
  côtés garderait la parité différentielle, mais palliad n'a besoin que du côté PostgreSQL pour avancer.
- **Pas de migration de données** MongoDB → PostgreSQL par le cadre. Palliad copiera ses collections
  lui-même, par `IDao.find` puis `IDao.save`.
- **Pas de mode `SchemaMode` qui supprime ou retype une colonne.** Le contrat actuel (ajouter, jamais
  détruire) nous convient.
- **Pas d'unicité sur un champ liste.** Un refus clair au démarrage suffit.
- **Pas de support d'un type géographique maison.** Les polygones de palliad (`GeoPolygon`, qui n'est pas
  un type `org.geojson`) seront convertis de notre côté.

## Réponse de la plateforme — 2026-11-02

**Les trois volets sont traités. A et C sont tenus tels qu'écrits, à deux points près, motivés plus
bas. B est éprouvé en natif dans le dépôt du cadre. L'épreuve a trouvé quatre défauts du cadre,
dont un qui touche aussi MongoDB.** Sur `main`, à paraître dans `3.0.0-ALPHA29`.

### A — Index déclarés, 409, contrat de démarrage — **MESURÉ** (PostgreSQL 17 réel)

`PgDao.registerDomain` lit les `EntityIndexRule` du domaine, traduit leurs champs d'entité en champs de
DTO (comme `MongoDao`) et les pose par `PgIndexManager` (`api/dao/dao-postgresql/.../PgIndexManager.java`,
`PgIndexPlanner.java`, `PgIndexSpec.java`) :

| Déclaration | Index posé |
|---|---|
| `standard`, portée `tenant` | `btree ("tenantId", champ)` — même règle que `MongoIndexSpec.of` |
| `standard`, unique | `CREATE UNIQUE INDEX … WHERE champ IS NOT NULL` — l'équivalent du filtre partiel Mongo |
| champ aplati (`address.city`) | la colonne aplatie (`address__city`) |
| champ liste (`sharedTenantIds`), ou champ d'un élément (`lines.code`) | la colonne de valeur de la table enfant, seule (une ligne enfant n'a pas de tenant) |
| `geo` sur une géométrie | `gist ((champ)::geography)` — voir C |
| `text` sur une colonne texte | `gin (to_tsvector(…))`, l'expression même que `$text` interroge — voir C |

Le nom est `<table>__<nom de la règle>`, borné à 63 octets. **Écart avec votre demande, voulu :** le
préfixe de table. PostgreSQL nomme un index par schéma, dans l'espace des tables. Deux domaines
déclarant `code` unique dériveraient le même nom, et `CREATE INDEX IF NOT EXISTS` sauterait le second
en silence. Le test `namesDoNotCollideAcrossTables` le fige.

**Contrat de démarrage, celui de Mongo :** `postgresql.index.auto` = `create` (défaut) / `none` /
`strict`, `true`/`false` acceptés. Un index présent sous le nom déclaré ne change rien ; une définition
différente est un WARN qui montre les deux, l'index restant tel quel ; un index qui couvre déjà les
mêmes clés sous un autre nom est gardé. Des doublons existants : en `create`, WARN avec une requête
`SELECT … GROUP BY … HAVING count(*) > 1` prête à coller ; en `strict`, refus de démarrer avec la même
requête — le test exécute la requête copiée du message et retrouve le groupe. Chaque index est créé
dans sa propre transaction, sous verrou consultatif : deux instances qui démarrent ensemble n'en
créent qu'un, et un index refusé n'emporte pas les autres.

**Sous `postgresql.schema.auto: false` (mode VALIDATE), aucun DDL**, comme le mode le promet : un
index manquant est signalé avec son `CREATE INDEX` exact — WARN en `create`, refus en `strict`, ce qui
couvre votre usage avant un déploiement de migration.

**`23505` → `ApiException.conflict` (409)**, avec le nom de l'index et le `DETAIL` de PostgreSQL. La connexion n'est pas laissée en transaction avortée : la
transaction de l'écriture est annulée. Le test le vérifie sur une connexion UNIQUE réutilisée — un
pool d'une connexion sous charge.

**Votre mesure, rejouée :** 30 écritures concurrentes de la même clé, même tenant → **1 ligne, 29
réponses 409** (`PgIndexTest.concurrentWritersOneWins`).

**Unicité sur une liste — votre « refus clair au démarrage », dans la politique de mode.** Elle est
refusée avec la raison (« une ligne par élément : l'index comparerait les éléments d'UNE entité, pas
deux entités »), mais c'est un WARN en `create` et un refus en `strict`. Une déclaration que la base ne
peut pas tenir suit la même règle qu'un index refusé sur données : Mongo ne fait pas tomber un service
pour une contrainte qu'il ne tenait pas déjà.

### B — Starter AOT et épreuve native — **MESURÉ** (GraalVM 25.0.3, PostgreSQL 17 + PostGIS 3.5, TLS)

- `garganttua-api-starter-aot-postgres-javalin` : `aot-reflection`, `aot-annotation-scanner`,
  `starter-bootstrap`, `starter-postgresql`, `starter-javalin`, de même forme que le starter Mongo.
- `api/e2e/native-postgres` : une application sur ce starter, construite par `native-image`
  (profil `-Pnative-e2e`, hors du build normal), et `run-e2e.sh` qui monte PostgreSQL 17 + PostGIS
  avec TLS derrière une autorité de test, lance le binaire en `sslmode=verify-full&sslrootcert=…`
  (authentification SCRAM, le défaut de l'image) et le conduit en HTTP (`e2e_scenario.py`).

Résultat, binaire natif (79 Mo, construit en 3 à 4 min), rejoué deux fois de suite, **12 vérifications
sur 12** :

| Vérification | Résultat |
|---|---|
| création (201), relecture (200) | ✓ |
| champ aplati, liste (table enfant), valeur non typée (JSONB), point GeoJSON (PostGIS), relus à l'identique | ✓ |
| `$regex` (PCRE traduit) | ✓ |
| `$geoWithin` (sphère) | ✓ |
| `$near` : plus proche d'abord, `$maxDistance` en mètres | ✓ |
| code en double → **409**, refusé par l'index unique de la base | ✓ |
| connexions de l'application, vues du serveur (`pg_stat_ssl`) | **4 en TLS, 0 en clair** |
| index posés au démarrage, en `strict` | `btree (code) WHERE code IS NOT NULL`, `gist ((location)::geography)` |

La même application et le même scénario passent sur la JVM.

**Ce que le natif exigeait et que le cadre ne livrait pas — quatre défauts, corrigés dans le cadre :**

1. **`application.yaml` n'était pas embarqué.** `GarganttuaConfig` (`api-commons`) le lit depuis le
   classpath ; native-image n'embarque aucune ressource par défaut. L'image démarrait avec une
   configuration VIDE — aucun `@Entity` découvert — pendant que tout ce qui venait de l'environnement
   marchait, ce qui cachait la cause. `api-commons` livre désormais `resource-config.json`
   (`application.ya?ml`, `application.properties`). C'était la ligne `application\.yaml` de votre
   `resource-config.json`.
2. **Les enveloppes de réponse de `binding-javalin`** (`ErrorEnvelope`, `StatusEnvelope`) répondaient
   `{}` : toute erreur arrivait sans sa raison. Livrées dans le `reflect-config.json` du binding.
3. **`Page`** répondait `{}` à toute lecture paginée. Livrée dans celui d'`api-commons`.
4. **Les types GeoJSON** (`org.geojson.*`, épinglés par `binding-jackson`) : un corps portant une
   géométrie était refusé en 400. Livrés dans le `reflect-config.json` de `binding-jackson`.

Les points 2 et 3 sont deux entrées de votre `reflect-config.json` : vous pouvez les retirer après la
montée. **Rien n'a été nécessaire pour le pilote JDBC, HikariCP ni les décodeurs du DAO** : aucune
`reflect-config`, aucune classe à initialiser à la construction. Le pilote et le pool portent leurs
propres métadonnées, et l'épreuve le confirme.

**Non mesuré : un binaire statique musl.** L'épreuve produit un binaire dynamique (glibc). Votre
production est en `--static --libc=musl` ; le chemin TLS du pilote y repose sur les mêmes classes Java,
mais nous ne l'avons pas construit ici.

**Un défaut trouvé en chemin, qui touche AUSSI MongoDB.** `Repository` (`api-core`) rangeait les DTO lus
dans des `HashMap` indexées par uuid avant d'en faire des entités : **l'ordre rendu par le DAO était
perdu** — pour un `$near`, mais aussi pour tout `?sort=…` sur plus d'une entité, sur PostgreSQL comme
sur MongoDB. La JVM nous l'a caché par hasard ; le natif l'a montré. Corrigé (`LinkedHashMap`), figé
par `RepositoryTest.keepsTheDaoOrder` (40 entités, uuids aléatoires), qui échouait avant le correctif.
Si palliad trie côté client par prudence, ce n'est plus nécessaire.

### C — Géographie et PostGIS — **MESURÉ** (PostGIS 3.5)

1. **`IndexKind.geo` → `gist ((champ)::geography)`.** Avec la portée `tenant`, l'index reste sur la
   géométrie seule (un GiST composé demanderait `btree_gist`), comme vous le proposiez.
2. **`$geoWithin` et `$geoWithinSphere` sont évalués sur la sphère** : `ST_CoveredBy` sur `geography`.
   C'est un **changement de comportement** : jusqu'à ALPHA28, `ST_Within` planaire en degrés. Nous avons
   pris les deux opérateurs, comme MongoDB où `Sphere` est un alias. Le test le prouve sur un cas qui
   diverge : un point à 50,5° N sous un bord de polygone sur le 50ᵉ parallèle, de −30° à +30° —
   dehors en planaire, dedans sur la sphère (le grand cercle monte à ~53,4° N). L'écart n'est donc plus
   silencieux : il n'existe plus.
3. **`$near` / `$nearSphere` avec `$maxDistance` / `$minDistance`** (mètres) : `ST_DWithin` sur
   `geography`, et **tri du plus proche au plus loin** quand la lecture ne demande pas d'autre tri
   (`ORDER BY … <-> point`, servi par l'index en KNN). Un tri explicite l'emporte, comme sur MongoDB.
   Refusé, avec les raisons de MongoDB : sous `$or` / `$nor`, deux fois, ou sur un champ tenu dans une
   collection. Forme JSON de MongoDB : `{"$near": {"$geometry": {Point}, "$maxDistance": m}}`.
4. **`$geoIntersects`** : `ST_Intersects` sur `geography` ; `{$geometry: …}` ou GeoJSON nu.
5. **Un point déclaré par deux colonnes plates — non fait.** Il faudrait qu'une `EntityIndexRule` porte
   deux champs, et le DSL comme `@EntityIndexed` n'en portent qu'un : c'est une évolution du contrat
   commun, pour un besoin que vous savez couvrir en migrant vers un champ GeoJSON. Nous préférons ne pas
   la faire pour un seul consommateur ; redemandez-la si la migration coûte plus que prévu.
6. **`IndexKind.text` → GIN**, et un changement de sens à connaître : **quand un domaine déclare un index
   texte, `$text` ne cherche plus que dans ce champ**, ce que fait MongoDB, dont l'index texte ne couvre
   que les champs déclarés. Sans déclaration, rien ne change : `$text` cherche dans toutes les chaînes de
   l'entité, comme un index `$**`. La requête écrit exactement l'expression de l'index ; le test vérifie
   au plan d'exécution que l'index la sert. Un seul index texte par domaine, comme MongoDB : un second
   est refusé selon la politique de mode.

Index servis — vérifié au plan d'exécution (`EXPLAIN`, balayage séquentiel désactivé) pour `$near`,
`$geoWithin` et `$text`. Un index déjà posé est reconnu comme déclaré au redémarrage, y compris les
clés-expressions que le catalogue réécrit à sa manière (`::regconfig`, parenthèses).

**Côté MongoDB, au-delà de la demande** : `$near`, `$nearSphere` et `$geoIntersects` y sont traduits aussi
(`Filters.nearSphere`, `Filters.geoIntersects`), pour qu'un filtre accepté par une base le soit par
l'autre. Non exécutés contre un vrai `mongod` : seule la traduction est testée.

### Ruptures et changements à la montée

| Élément | Avant | Après |
|---|---|---|
| `$geoWithin` / `$geoWithinSphere` (PostgreSQL) | `ST_Within` planaire | `ST_CoveredBy` sur la sphère |
| `$text` (PostgreSQL), domaine déclarant un index texte | toutes les chaînes de l'entité | le champ déclaré seul |
| Lectures triées (tous DAO) | ordre du hachage des uuid | ordre du DAO |
| Unicité déclarée sur PostgreSQL | non tenue | tenue par la base, 409 |

Additif : `PgDao(…, PgIndexMode)`, `postgresql.index.auto`, `GeoNear`,
`Filter.geolocNear/geolocNearSphere/geolocIntersects`, le starter AOT.

### Côté palliad, après la montée

1. Monter `garganttua.version` et reconstruire avec `clean`.
2. Déclarer les points GeoJSON en `IndexKind.geo` pour `$near`.
3. Remplacer dans `palliad-store-postgresql` l'assemblage manuel par
   `garganttua-api-starter-aot-postgres-javalin`, sauf si le second pool que vous évitez est encore un
   obstacle : le starter ouvre UN pool partagé par tous les domaines.
4. Retirer de votre `resource-config.json` la ligne `application\.yaml`, et de votre `reflect-config.json`
   les entrées `ErrorEnvelope`, `StatusEnvelope` et `Page`. Garder `ExpressionContext` / `Expressions` :
   l'épreuve ne les a pas sollicitées, nous ne pouvons pas dire qu'elles sont devenues inutiles.
