# Le DAO MongoDB ne stocke pas un champ GeoJSON en GeoJSON : `$near` et l'index 2dsphere sont inutilisables

**À l'attention de :** garganttua-api (`dao-mongodb`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM en production, MongoDB 4.4 et 7.0)
**Date :** 2026-11-02
**Version constatée :** garganttua-api `3.0.0-ALPHA29`
**Gravité :** bloquant pour l'usage que la réponse à la fiche précédente recommande. Un champ `org.geojson` déclaré
`IndexKind.geo` fonctionne sur PostgreSQL, mais sur MongoDB l'index 2dsphere ne se pose pas et toute écriture d'un
document portant ce champ est refusée par le serveur. Un même DTO ne peut donc pas servir les deux bases.

## Contexte

La réponse à `le-dao-postgresql-ne-pose-aucun-index-ni-starter-aot-ni-index-geographique.md` recommande à palliad de
« déclarer les points GeoJSON en `IndexKind.geo` pour `$near` », sur les deux bases. Elle précise que, côté MongoDB,
`$near` / `$geoIntersects` sont « traduits, non exécutés contre un vrai `mongod` : seule la traduction est testée ».

palliad s'apprêtait à rétablir un `org.geojson.Point location` sur ses établissements de santé, environ
33 000 lignes en production. Ce champ existait du temps de Spring Data MongoDB et portait l'index 2dsphere. La lecture
des sources montre que l'écriture et la relecture du champ ne produisent pas de GeoJSON.

## Cause

**Lu** dans les sources d'ALPHA29, non exécuté.

1. **Écriture.** `MongoDocumentWriter.toStorable`
   (`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDocumentWriter.java`, l. 101-129) ne
   reconnaît comme natives que les valeurs des paquets `java.*` / `javax.*` / `jdk.*` / `org.bson.*` (`isNativeBson`,
   l. 169-180). Un `org.geojson.Point` part donc dans `pojoToDocument`, parcouru champ par champ. Le document stocké
   ressemblerait à `{coordinates: {longitude, latitude, altitude: NaN, additionalElements: []}, properties: {}}`, et
   non à `{type: "Point", coordinates: [lng, lat]}`.
   - Sur un tel champ, MongoDB refuse de construire un index 2dsphere.
   - Une fois l'index en place, il refuse toute insertion ou mise à jour (« Can't extract geo keys »).
2. **Lecture.** `MongoDocumentReader.mapValue` (l. 144-159) mappe un `Document` sur le type déclaré, champ par champ.
   Un GeoJSON véritable (`coordinates` est un tableau) ne redevient pas un `Point`.
3. **Le filtre, lui, sait faire.** `MongoFilterConverter` (l. 107-128) sérialise déjà la géométrie d'un filtre par
   Jackson (`GEO_MAPPER.writeValueAsString`), qui produit le GeoJSON attendu. Il manque la même conversion dans le
   writer et le reader.

## Attendu

1. **Écriture et relecture en GeoJSON.** Un champ dont le type est un `org.geojson.GeoJsonObject` (`Point`,
   `Polygon`, `MultiPolygon`…) s'écrit en GeoJSON et se relit en GeoJSON, par la même voie Jackson que le filtre.
2. **Une épreuve contre un vrai `mongod`**, comme celle faite sur PostgreSQL :
   - index `IndexKind.geo` posé (2dsphere) ;
   - écriture et relecture d'un `Point` à l'identique ;
   - `$near` avec `$maxDistance` trié par distance ;
   - `$geoWithin` et `$geoIntersects`.
3. **La même chose en natif** : les classes `org.geojson` sont résolues par Jackson. À confirmer avec les métadonnées
   que le starter AOT MongoDB livre.
4. **Une lecture paginée avec `$near`.** MongoDB refuse `$near` dans un `countDocuments` (« $near is not allowed
   inside of a $match aggregation expression »). Si la lecture paginée compte avec le même filtre, elle échouera.
   Le cadre peut compter avec `$geoWithin` / `$centerSphere` à rayon égal, ou documenter qu'une lecture `$near` n'est
   pas paginée.

## Ce que la fiche ne demande pas

- Rien sur PostgreSQL, où le même champ est déjà une géométrie PostGIS.
- Pas de conversion automatique des documents déjà stockés : palliad remplit le champ lui-même par script.

## Réponse de la plateforme — 2026-11-02

**Corrigé, les quatre points, et éprouvé contre de vrais `mongod` 4.4 et 7.0, puis en natif.** Sur `main`,
à paraître dans `3.0.0-ALPHA30`.

**D'abord, la part qui nous revient.** La réponse à la fiche PostgreSQL vous a conseillé de déclarer
`IndexKind.geo` « sur les deux bases », en disant que seule la traduction était testée côté MongoDB. Le conseil
n'aurait pas dû être donné sans l'épreuve : c'est elle qui manquait, et votre lecture des sources a trouvé ce
qu'elle aurait trouvé. Votre cause est exacte en tout point.

### 1. Écriture et relecture en GeoJSON — sans Jackson

Pas « par la même voie Jackson que le filtre » : le filtre ne passe plus par Jackson non plus. Une géométrie est
un `type` et des tableaux de nombres ; un module neuf, `garganttua-api-binding-geojson`, porte `GeoJsonCodec`, qui
fait l'aller-retour sans bibliothèque JSON ni réflexion. Il sert les deux DAO :

- **MongoDB** : `MongoDocumentWriter` écrit un `org.geojson.GeoJsonObject` en `{type, coordinates}`, y compris
  dans une liste ou une map ; `MongoDocumentReader` relit un sous-document GeoJSON dans son type déclaré ;
  `MongoFilterConverter` écrit ses géométries de la même façon. **Le DAO MongoDB n'utilise plus Jackson.**
- **PostgreSQL** : la valeur liée à `ST_GeomFromGeoJSON` et la relecture de `ST_AsGeoJSON` passent par le même
  convertisseur. Une géométrie s'écrit donc à l'identique sur les deux bases.

Les sept géométries de la RFC 7946 sont prises ; un `Feature` ou une `FeatureCollection` est refusé avec la raison
(ses `properties` ne sont pas une géométrie) ; `crs` et `bbox` ne sont pas stockés ; l'altitude est gardée.

**Les documents déjà stockés champ par champ restent lisibles** : sans clé `type`, le lecteur retombe sur l'ancien
décodage. Mais l'index `2dsphere` refuse de se construire tant qu'il en reste un dans la collection : en
`mongodb.index.auto: create`, c'est un WARN et l'application démarre sans l'index ; en `strict`, un refus.
Votre script de remplissage doit donc passer avant (ou réécrire les documents existants : un `save` les met en
GeoJSON).

### 2. Épreuve contre un vrai `mongod` — **MESURÉ**, 4.4 et 7.0

`MongoDaoGeoTest` (`api/dao/dao-mongodb/src/test/…`), joué contre le `mongod` 7.0 de la suite et rejoué contre
un `mongo:4.4` (`-Dgarganttua.test.mongodb.uri=…`, ajouté pour l'occasion) : 125 tests sur 125 dans les deux cas.

| Vérification | 4.4 | 7.0 |
|---|---|---|
| point stocké `{type: "Point", coordinates: [lng, lat]}` ; index `2dsphere` posés en `strict` | ✓ | ✓ |
| `Point`, `Polygon`, `List<Point>` relus à l'identique | ✓ | ✓ |
| un document à l'ancienne forme relu | ✓ | ✓ |
| `$near` : plus proche d'abord, `$maxDistance` / `$minDistance` en mètres | ✓ | ✓ |
| `$geoWithin` ; `$geoIntersects` (les zones contenant un point) | ✓ | ✓ |
| un tri explicite l'emporte sur la distance | ✓ | ✓ |

### 3. Natif — **MESURÉ**

`api/e2e/native-mongo` : l'application de l'épreuve PostgreSQL sur `starter-aot-mongo-javalin`, construite par
`native-image` et conduite en HTTP par le même scénario, contre `mongo:7.0` puis `mongo:4.4` : **13 vérifications
sur 13 dans les deux cas** (création, 409, aller-retour de chaque forme, `$regex`, `$geoWithin`, `$near` trié et
borné, `$near` paginé). Le serveur montre l'index `{"location": "2dsphere"}` et le point stocké
`{"type":"Point","coordinates":[2.3522,48.8566]}`. Le stockage d'une géométrie n'a plus besoin d'aucune
métadonnée de réflexion.

### 4. Lecture paginée avec `$near` — votre hypothèse était juste, et il y avait un second défaut

- **Le compte** : une lecture paginée compte avec son filtre, et MongoDB refuse `$near` dans `countDocuments`.
  `MongoDao.count` le réécrit en l'anneau qu'il sélectionne : le champ présent, `$geoWithin` un `$centerSphere` de
  `$maxDistance`, pas dans un `$centerSphere` de `$minDistance` (rayon en radians, au rayon terrestre de MongoDB,
  6 378 100 m). Le test vérifie que le compte égale le nombre de lignes lues, sur les deux versions.
- **L'ordre de la page** : le DAO ajoute une clé `_id` au tri d'une lecture paginée, pour des pages stables — elle
  remplaçait l'ordre de distance de `$near`. Une lecture paginée sans tri explicite qui porte un `$near` n'a plus
  cette clé. Notre premier test passait par hasard (les deux ordres donnaient la même page) ; il compare désormais
  des pages où ils diffèrent.

### Côté palliad

Monter en ALPHA30, reconstruire avec `clean`, passer le script de remplissage du champ `location` (en GeoJSON, ou
par un `save`), puis déclarer `IndexKind.geo`. Rien à ajouter en natif.
