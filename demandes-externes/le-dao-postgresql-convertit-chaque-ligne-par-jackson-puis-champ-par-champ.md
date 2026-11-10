# Le DAO PostgreSQL convertit chaque ligne par Jackson, puis champ par champ

**À l'attention de :** garganttua-api (`dao-postgresql`)
**Émis par :** palliad (v3, AOT pur, natif G1 + PGO et JVM HotSpot)
**Date :** 2026-11-05
**Version constatée :** garganttua-api `3.0.0-ALPHA33`, avec l'aller-retour unique par lecture d'ALPHA31 et plus.
**Gravité :** c'est tout l'écart de capacité entre PostgreSQL et MongoDB : 25 à 40 % de débit en moins par serveur
(palliad, Bancs 4 et 5). La base de données n'y est pour presque rien. palliad doit passer sur PostgreSQL pour
l'hébergement HDS de janvier 2027.

## Symptôme (mesuré)

Même code, mêmes données (33 090 établissements, 20 016 activités), JVM HotSpot 2 vCPU / 2 Go sous les parcours réels.
Java Flight Recorder, part du processeur appels imbriqués compris :

| | MongoDB | PostgreSQL |
|---|---|---|
| sous la couche d'accès (`com.garganttua.dao.`) | 16,5 % | **26,0 %** |
| dont le pilote de la base | 10,8 % (`com.mongodb`, `org.bson`) | **2,2 %** (`org.postgresql`) |
| JSON (Jackson) | 4,8 % | **8,0 %** |

Méthodes chaudes propres à PostgreSQL : `TextBuffer.contentsAsString` (Jackson), `com.garganttua.dao.postgresql.PgBeans.leaf`,
`ArrayList.grow`, `HashMap.resize`. Sur le binaire natif, l'étape `readAll` coûte 21,1 ms par requête sur PostgreSQL
contre 12,2 sur MongoDB, et le cas d'usage `activities/metrics` 20,2 contre 7,8.

## Cause (lu dans les noms des méthodes chaudes, à confirmer)

Le pilote PostgreSQL rend la colonne JSONB en **texte**. Jackson la lit en arbre de `Map`/`List`, puis `PgBeans` reconstruit
l'objet du DTO **feuille par feuille**. Chaque ligne passe par deux représentations intermédiaires et des copies de
chaînes. Côté MongoDB, le pilote rend directement des documents BSON lus par le `MongoDocumentReader`.

## Attendu

Une conversion en une passe : Jackson directement vers le type du DTO (`ObjectReader` mis en cache par type, flux
de jetons sur le texte JSONB), sans arbre intermédiaire ni reconstruction champ par champ. Ou, à défaut, lire la
colonne en binaire (`jsonb` en mode binaire du pilote) et éviter la copie `String`.

Le lecteur Jackson par type (`ObjectReader`) est un cache **de structure**, local par nature : il ne dépend que du type du
DTO, il est identique sur tous les nœuds et il ne s'invalide jamais. Il n'a pas à être distribué. Aucune **donnée** n'est
à mettre en cache ici.

## Ce que la fiche ne demande pas

- Ne pas changer le schéma des tables ni la colonne JSONB, ni les index déclarés (ALPHA29).
- Ne pas toucher au comportement des filtres, de la pagination ni du tri.
