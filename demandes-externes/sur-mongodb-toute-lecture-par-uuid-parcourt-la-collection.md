# Sur MongoDB, toute lecture par uuid parcourt la collection entière

**À l'attention de :** garganttua-api (`core`, `dao-mongodb`)
**Émis par :** palliad (v3, AOT pur, binaire natif GraalVM 25 G1 + PGO, MongoDB 7.0 et 4.4)
**Date :** 2026-11-11
**Version constatée :** garganttua `3.0.0-ALPHA34`, construite en local sur `21dc694`
**Gravité :** HAUTE, et elle croît avec les données. Chaque `readOne`, chaque contrôle d'existence et chaque lecture par
uuid d'un consommateur parcourt tous les documents de la collection : le coût d'une requête unitaire est proportionnel au
volume de la base, pas au résultat. Rien ne le signale. Le défaut est invisible sur une base de développement (quelques
dizaines de documents) et sur PostgreSQL (la clé primaire est la colonne uuid) ; il ne se voit que sur MongoDB, à volume.

## Symptôme

**Mesuré** (banc de charge de palliad, base MongoDB 7.0 de 20 030 activités, 3 207 patients) :

- `db.activities.find({uuid: <u>}).explain("executionStats")` : **20 030 documents examinés** pour 1 rendu ;
- même document, 20 lectures chacune : **15,2 ms** par `uuid`, **1,05 ms** par `_id` ;
- une liste d'une équipe (`{tenantId: <t>, category: …}`, 2 549 rendus) : **20 237 documents examinés** ;
- index présents sur `activities` : `{_id: 1}` et `{sharedTenantIds: 1}` (ce dernier déclaré par le consommateur). Aucun
  sur `uuid`, aucun sur `tenantId`. Même chose sur `users`, `patients`, `authorizations`, `intervention-requests`, en
  développement comme en production.

Sur le banc, un écrivain de palliad qui relit une activité par uuid à chaque ligne de journal fait passer la médiane des
requêtes de 49 à 76 ms à 100 utilisateurs, et le CPU de MongoDB de 32 à 62 % ; sur PostgreSQL, le même code ne coûte rien
de visible.

## Cause

**Lu** dans les sources :

1. Le DAO écrit l'uuid deux fois : sous son propre champ ET sous `_id`
   (`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDocumentWriter.java`, l. 57-63 ; le commentaire le
   dit : « The uuid is kept under its own field name so uuid-keyed filters (readOne / readAll by uuid) still match — _id is
   an added projection »).
2. Les lectures du cadre adressent l'entité par le champ **`uuid`**, jamais par `_id` :
   `api/core/src/main/java/com/garganttua/api/core/repository/RepositoryFilterTools.java`, `createUuidFilter`
   (l. 196-220 : `Filter.eq(<champ uuid>, uuid)`) ; `Repository.doesExist` → `findOneByField(… uuid())` (l. 155-157,
   198).
3. Le convertisseur de filtres (`MongoFilterConverter`) ne réécrit pas le champ uuid en `_id` : la requête part sur
   `{uuid: …}`.
4. Le DAO ne crée que les index que le domaine déclare : `MongoDao.ensureIndexes` → `MongoIndexSpec.plan(entityDefinition
   .indexes(), …)` (`MongoDao.java` l. 135-149, `MongoIndexSpec.java` l. 78-87). Ni le champ uuid ni le champ tenant n'y
   sont ajoutés.

L'index `_id`, posé par MongoDB, n'est donc utilisé par aucune lecture du cadre. Le filtre de tenant
(`RepositoryFilterTools.buildTenantFilter`, l. 266-285), ajouté à chaque lecture d'un appelant non super-tenant, n'a pas
d'index non plus.

## Attendu

- **Sur MongoDB, l'uuid d'un domaine est indexé par le cadre, toujours** : un index UNIQUE sur le champ uuid, posé à
  l'enregistrement du domaine, sans que le consommateur ait à le déclarer — comme sur PostgreSQL, où la clé primaire le
  garantit. L'uuid est l'identifiant de toutes les lectures du cadre ; une collection sans cet index n'est pas un état
  normal. (Équivalent acceptable : que toute lecture par uuid soit réécrite en `_id`, qui porte déjà l'uuid et l'index.)
- Le champ de tenant, que le cadre ajoute lui-même à chaque lecture multi-tenant, est indexé de même.
- Un test qui le garde : `explain` d'un `readOne` et d'un `readAll` filtré par tenant, sur le DAO MongoDB, qui n'est pas un
  `COLLSCAN`.

## Ce que la fiche ne demande pas

- Elle ne choisit pas la manière (index sur le champ uuid, ou filtre réécrit en `_id`) : c'est à l'équipe du cadre de
  trancher, pourvu qu'aucune lecture par uuid ne parcoure plus la collection. Le mode `mongodb.index.auto=none` doit rester
  respecté.
- Elle ne demande rien pour PostgreSQL, où la clé primaire couvre déjà la lecture par uuid.
- Palliad peut déclarer ces index domaine par domaine (`entity().index("uuid", …)`), comme il le fait déjà pour
  `healthcare-organizations`. Ce serait un contournement à répéter sur chaque domaine de chaque consommateur ; la fiche
  demande que le cadre le fasse une fois.

## Reproduire

1. Une application garganttua v3 sur MongoDB, un domaine quelconque, quelques milliers de documents.
2. `db.<domaine>.getIndexes()` : seul `_id`.
3. `db.<domaine>.find({uuid: "<un uuid>"}).explain("executionStats").executionStats.totalDocsExamined` : la taille de la
   collection. Un `GET /<domaine>/<uuid>` envoie cette requête-là.

## Réponse — 2026-11-11, à paraître dans `3.0.0-ALPHA34`

**Cause confirmée**, telle que la fiche la lit : les lectures du cadre filtrent sur le champ uuid, que rien n'indexait.

### Ce qui est fait

1. **L'uuid est indexé par le cadre, sans déclaration** (`MongoIndexSpec.implicit`, `MongoIndexManager.ensureImplicit`).
   - Index `gg_uuid_system_standard_unique`, unique, sur le champ du document qui porte l'uuid.
   - Nous avons gardé le filtre `{uuid: …}` plutôt que de le réécrire en `_id` : la réécriture devrait suivre chaque
     chemin qui adresse l'uuid, dont les filtres que les consommateurs écrivent eux-mêmes.
   - L'index est **sparse**, pas partiel comme les index uniques déclarés. Le planificateur de MongoDB n'utilise pas un
     index partiel pour une égalité simple `{uuid: valeur}` : nous l'avons constaté, `explain` restait un `COLLSCAN`.
     Avec l'index sparse, c'est un `IXSCAN`.
2. **Le tenant est indexé de même**, sur MongoDB (`gg_<tenant>_system_standard_idx`) et, par symétrie, sur PostgreSQL
   (`<table>__gg_<tenant>_system_standard_idx`). Pour une entité tenant, dont le tenant est l'uuid, seul l'index de
   l'uuid est posé.
3. **Les règles des index déclarés s'appliquent.**
   - Même mode : `mongodb.index.auto=none` ne pose rien.
   - Purement additif : rien n'est supprimé ni recréé.
   - Un index qui couvre déjà les mêmes clés suffit, qu'il soit déclaré par le domaine ou posé par un déploiement, comme
     votre index de `healthcare-organizations` : rien n'est créé, rien n'est signalé.
   - Une collection qui porte deux fois le même uuid refuse l'index : `create` avertit et démarre, `strict` refuse de
     démarrer.
4. **Le test demandé** (`MongoDaoIndexTest.FrameworkIndexes`, sur un vrai `mongod`) : `explain` de `{uuid: …}` et du
   filtre de tenant, ni l'un ni l'autre un `COLLSCAN`. Sur PostgreSQL, `PgIndexTest.FrameworkTenantIndex` vérifie que le
   plan d'une lecture par tenant passe par l'index.

À la première montée en ALPHA34, MongoDB construit ces index sur les collections existantes. Sur vos volumes (20 000
documents), c'est l'affaire de quelques secondes, et MongoDB 4.2+ les construit sans bloquer les écritures.

### Constat en plus, non corrigé

Les index **uniques déclarés** (un e-mail, un login) sont partiels : `partialFilterExpression` sur les types non nuls,
pour accepter, comme le contrôle du cadre, plusieurs documents sans valeur. Ils garantissent bien l'unicité, mais pour la
même raison que ci-dessus, **une lecture par égalité sur ce champ ne s'en sert pas** et parcourt la collection. Les
rendre sparse changerait le contrat : un sparse unique refuse un second `null` explicite. Si une recherche par login vous
coûte, la parade est un index non unique déclaré à côté (`entity().index("login")`). Dites-le nous si vous voulez qu'on
en fasse une fiche.
