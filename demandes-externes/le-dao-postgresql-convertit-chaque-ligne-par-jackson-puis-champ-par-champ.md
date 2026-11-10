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

## Réponse de la plateforme — 2026-11-10

**Traitée. La conversion se fait désormais en une passe, mais la cause n'était pas celle que la fiche supposait.** Sur la
branche `perf/token-verification`, à paraître dans `3.0.0-ALPHA34`. Le schéma, les colonnes et les index sont inchangés,
de même que les filtres, la pagination et le tri.

### La cause, mesurée

La ligne principale n'est pas un JSONB lu en arbre : c'est un ensemble de colonnes à plat. Le coût venait d'ailleurs.
Nous l'avons profilé sur la forme de vos activités : 10 valeurs de 3 sous-valeurs, 3 révisions de 4 changements,
500 lectures.

- **`PgRowMapper`, à chaque cellule de chaque ligne et de chaque élément de liste :**
  - le chemin de champs était résolu par nom (`PgBeans.leaf`, un `computeIfAbsent` par segment) ;
  - deux `LinkedHashMap` étaient créées, avec des listes de chaînes pour clés, hachées à chaque accès ;
  - le chemin était reparcouru pour poser la valeur, et la présence vérifiée en découpant des sous-listes.

  C'est le `PgBeans.leaf`, le `HashMap.resize` et l'`ArrayList.grow` de votre profil.
- **`PgChildLoader.attach`** cherchait, pour chaque élément et chaque sous-liste, un bit de présence dans une de ces
  tables (8 % du CPU à lui seul).
- **Chaque instanciation** recréait les objets de réflexion du constructeur (`getDeclaredConstructor`).
- **Le `TextBuffer.contentsAsString` de Jackson** venait de la lecture des agrégats JSON des listes (ALPHA31), pas
  d'une colonne JSONB. Un parseur générique y crée un contexte par tableau et recopie chaque chaîne.

### Ce qui est fait

- **Un plan par table et par liste de colonnes** (`PgRowPlan`), calculé une fois. Pour chaque cellule, il connaît la
  chaîne de champs, le type cible et les bits de présence qui la gardent. Une ligne n'est plus que deux tableaux
  décodés dans l'ordre, puis posés le long de champs déjà résolus. Les règles ne changent pas : NULL veut dire absent,
  et la présence décide de l'existence d'un POJO.
- **Les bits de présence des sous-listes** se lisent par indice dans le plan de la table, et non plus par chemin.
- **Les constructeurs** sont résolus une fois par classe.
- **Les agrégats se lisent sans Jackson**, par un lecteur écrit pour leur seule forme (`PgAggregateText`) : tableaux
  de textes, de `null` et de tableaux. Une chaîne sans échappement y coûte un `substring`.
- **Un texte vers un champ `String`** se lit directement, sans la cascade de questions sur le type du champ.
- Pour les vraies colonnes JSONB (champs `Object`, maps à clé complexe), le lecteur Jackson est **mis en cache par
  type**, comme vous le proposiez. C'est un cache de structure, local, qui ne s'invalide jamais.

### Mesuré

Mêmes données, JVM, PostgreSQL embarqué sur la même machine, lecture des 500 activités :

| | Avant | Après |
|---|---:|---:|
| **CPU Java** par lecture | 22,9 ms | **10,2 ms** (−55 %) |
| Temps mural | 72 ms | 60 ms |

Le temps mural baisse moins, parce qu'ici la base, sur la même machine, calcule ses agrégats pendant 50 ms. Le chiffre
qui répond à votre fiche est le CPU Java, celui qui décide de la capacité d'un serveur d'application quand la base
est ailleurs.

### Ce que nous n'avons pas fait

- **Lire le JSONB en mode binaire du pilote.** Le texte des agrégats n'est plus la partie chère.
- **Supprimer Jackson du DAO.** Les colonnes JSONB et les clés en ont encore besoin.

Votre banc 4 dira ce qui reste de l'écart avec MongoDB ; nous attendons vos chiffres.
