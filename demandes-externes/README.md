# Demandes externes

**Ce dossier accueille les fiches déposées par les CONSOMMATEURS de la plateforme** — les projets
qui construisent sur `core`, `api` ou `events` sans en écrire une ligne. Bogues constatés, écarts
entre ce que le DSL donne à lire et ce qu'il fait, manques qui obligent à un contournement.

C'est le seul endroit prévu pour cela. Une fiche vivant dans le dépôt du consommateur n'est lue
que par le consommateur : elle décrit un défaut de la plateforme à des gens qui ne peuvent pas le
corriger, pendant que ceux qui le peuvent ne savent pas qu'elle existe. Les sept premières fiches
ci-dessous ont passé leurs premières semaines dans `palliad/docs/bugs/`, exactement dans cette
situation.

## Ce qu'une fiche doit porter

L'en-tête, tel que les fiches existantes l'écrivent — il permet de trier sans ouvrir :

```
**À l'attention de :** garganttua-api
**Émis par :** <projet> (contexte utile : v3, AOT pur, natif…)
**Date :** AAAA-MM-JJ
**Version constatée :** garganttua-api `3.0.0-ALPHAxx`
**Gravité :** <et surtout POURQUOI — l'effet, pas l'étiquette>
```

Puis, dans l'ordre : le **symptôme** tel qu'on le rencontre, la **cause** avec le fichier et les
lignes quand elle a été lue dans les sources, l'**attendu**, et ce que la fiche **ne demande pas**
— cette dernière section évite qu'un correctif dépasse la demande et casse d'autres consommateurs.

Une distinction que les fiches tiennent et qu'il faut garder : **mesuré** (reproduit en HTTP,
avant/après) n'est pas **lu** (établi par lecture des sources, sans exécution). Les deux sont
recevables, ils n'engagent pas autant.

> **Les chemins de sources sont ceux de ce dépôt.** Une fiche qui cite
> `api/core/src/main/java/…` se relit ici telle quelle — ce qui n'était pas le cas chez le
> consommateur, où le chemin ne désignait rien.

## Ce que ce dossier n'est pas

Une file de tickets, ni un engagement de traitement. Rien ici n'est priorisé par le fait d'y être :
une fiche décrit ce qui a été constaté et ce qui aiderait, la décision reste à la plateforme.
Quand une demande est traitée, le geste utile est de le dire **dans la fiche** — version qui
corrige, ou refus motivé — plutôt que de l'effacer : le consommateur qui l'a écrite viendra
vérifier, et un dossier vide ne lui apprend rien.

## Demandes de la plateforme vers un consommateur

Ce dossier est prévu pour l'autre sens, et il le reste. Mais quand la plateforme a besoin d'une
mesure que seul un déploiement peut produire, la demande se pose là où vit la conversation — sinon
elle n'est pas lue, ce qui est exactement l'argument que ce README fait pour les fiches des
consommateurs. La direction est alors annoncée dans l'en-tête.

| Fiche | Adressée à | En attente de |
|---|---|---|
| [mesure-demandee-compteur-de-resolutions](mesure-demandee-compteur-de-resolutions.md) | autonom | le compteur `reflection.resolveAddresses` d'une requête réelle sous ALPHA17 — le seul facteur qui manque pour expliquer, ou écarter, le plancher de ~40 ms |

## Fiches ouvertes

Trois fiches de palliad encore ouvertes, déposées les 2026-11-05 et 06 : deux **mesurées** sur la capacité d'un serveur
(ALPHA33, natif et JVM), une évolution bloquante pour l'hébergement HDS.

| Fiche | À l'attention de | En une ligne |
|---|---|---|
| [le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage](le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md) | core, api | résoudre les membres une fois par type, pas à chaque appel ; la sonde `HotPathProbe` se dispute ses compteurs |
| [un-cache-du-cadre-local-et-distribue](un-cache-du-cadre-local-et-distribue.md) | core, api | `ICache` local et Redis, invalidé par les écritures du pipeline |
| [authentification-a-deux-facteurs](authentification-a-deux-facteurs.md) | api | une étape entre l'authentification et l'émission du jeton, et une réutilisation qui tient compte du niveau |

Les fiches précédentes, déposées par palliad et autonom, ont été traitées — voir ci-dessous.

Ce n'est pas une invitation à se taire : le dossier existe pour ce que les consommateurs
constatent, et il est fait pour se remplir de nouveau. Deux points explicitement laissés ouverts
attendent d'ailleurs un retour, et sont nommés dans leur fiche : le plancher de ~40 ms, à
re-mesurer chez autonom après ALPHA17, et le champ non déclaré à la mise à jour, non rapporté dans
les en-têtes de champs écartés.

## Fiches répondues

Traitées le 2026-09-04, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA17`**. La réponse est
écrite au bas de chaque fiche : ce qui a été fait, et — quand la lecture des sources l'a établi —
en quoi la cause différait de celle que la fiche supposait. Elles restent ici pour que celui qui les
a écrites puisse vérifier.

| Fiche | Émise par | Réponse |
|---|---|---|
| [resolution-par-requete-et-exceptions-sur-le-chemin-chaud](resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md) | autonom | Corrigée, les trois pistes. 8 à 9 fois moins cher sur le chemin de résolution isolé (1 395–1 670 ns → 173–178 ns). Deux sites levaient, pas un : le second est dans le repli AOT, donc sur VOTRE déploiement. La mémo porte la FORME de la classe, pas les adresses — celles-ci dépendent de l'adresse de base. Le chemin est désormais instrumenté et le rapport tombe à l'arrêt de la JVM. Les ~40 ms n'ont pas été reproduites ici : **à re-mesurer chez vous**. |
| [toute-exception-de-crochet-rend-500](toute-exception-de-crochet-rend-500.md) | palliad | Corrigée — mais pas là où la fiche le pensait : le `switch` vide était du code mort, le 500 venait du `-> 500` de l'étape. Un statut choisi par le consommateur (`ApiException.badRequest(...)`) l'emporte désormais sur celui de l'étape. |
| [crochets-de-suppression-ne-tirent-jamais](crochets-de-suppression-ne-tirent-jamais.md) | palliad | Corrigée, les deux défauts. Le second était plus large que mesuré : les crochets liés à l'entité étaient cassés sur TOUTES les opérations, pas seulement la suppression. |
| [afterget-ne-tire-que-sur-readone](afterget-ne-tire-que-sur-readone.md) | autonom | Corrigée dans le sens de la demande 1. Ce n'était pas un choix de contrat : `READ_ALL` exigeait un `?mode=full` qu'une lecture ordinaire n'envoie pas, ce qui sautait aussi l'injection. L'exemption des lectures internes, que la fiche signalait, est maintenant explicite — elle tenait par accident. |
| [mandatory-teste-la-nullite-et-seulement-a-la-creation](mandatory-teste-la-nullite-et-seulement-a-la-creation.md) | palliad | Corrigée en option déclarative (`MandatoryPolicy.nonBlank`), défaut inchangé. Rectification : la contrainte tourne DÉJÀ à la mise à jour — sur l'entité fusionnée, où le champ n'est jamais nul. Le partage absent / null / vide du tableau de la fiche est implémenté ligne pour ligne. Rupture : `IEntityDefinition.mandatories()`. |
| [operation-request-caller-reconstruit](operation-request-caller-reconstruit.md) | palliad | Corrigée telle que demandée, en trois lignes : la vérification publiait déjà l'appelant réconcilié, personne ne le lisait. Plus large que la fiche : cinq suppliers passent par `request.caller()`. |
| [champs-ecartes-silencieusement](champs-ecartes-silencieusement.md) | palliad | Corrigée : les deux en-têtes, avec les noms de DTO, toujours présents, statut inchangé à 200. Exigence 1 tenue à la création, **partielle à la mise à jour** — le champ non déclaré n'est pas rapporté, et la fiche dit pourquoi. |
| [attempt-authentication-avale-les-exceptions](attempt-authentication-avale-les-exceptions.md) | palliad | Corrigée — mais notre première réponse était trop affirmative, et la fiche porte la **rectification** : le `catch` ne voyait que les erreurs de fourniture, pas la stratégie qui lève elle-même, laquelle est pourtant ce que le titre désigne. Vraie depuis ALPHA19. |
| [apiexception-de-cas-dusage-rendue-en-200-corps-0](apiexception-de-cas-dusage-rendue-en-200-corps-0.md) | autonom | Corrigée. Leur supposition était juste : le binder CAPTURE l'exception au lieu de la lever, `single()` rendait `null`, et le `0` était le code de sortie du workflow servi comme corps. Demande de `4xx` par défaut **non retenue** (500 reste le défaut d'une `ApiException` nue, `ApiException.badRequest(...)` donne le 400), avec la raison écrite dans la fiche. A permis de trouver le même défaut à trois autres endroits, dont un qui persistait des entités NON sécurisées. |
| [mutex-redis-au-classpath-tue-lapi](mutex-redis-au-classpath-tue-lapi.md) | palliad | Corrigée : deux `@Expression(name = "syncRedis")` de même arité, la variante `String` perd son annotation. Leur seconde demande — refuser un doublon à l'indexation — n'est tenue qu'à moitié : refus à l'**enregistrement**, sur les trois chemins, avec un message nommant la paire ; la vérification à la compilation vit dans l'annotation processor, hors réacteur, et la fiche le dit. Le contrôle a immédiatement trouvé un doublon chez nous : deux `setRequestArg` aux sémantiques divergentes, celle qui gagnait dépendant de l'ordre de scan. |
| [synchronisation-des-ecritures-casse-lupsert-de-demarrage](synchronisation-des-ecritures-casse-lupsert-de-demarrage.md) | palliad | Corrigée, et **le défaut était plus large que la fiche** : pas le démarrage, TOUTE écriture synchronisée. `InterruptibleLeaseMutex` exécute le bloc sur un autre thread pour appliquer le bail, et les contextes ambiants sont des `ScopedValue` qui ne traversent pas. Nos quinze tests étaient verts parce que notre faux verrou restait sur le thread appelant. Il exécute désormais ailleurs, et un test tourne contre le vrai mutex de core. |
| [synchronisation-multi-instances-absente-de-lapi](synchronisation-multi-instances-absente-de-lapi.md) | palliad | Traitée dans la forme A demandée : `apiBuilder.synchronization(...)` / `IDomainBuilder.synchronization(...)`, clé `<préfixe>:<tenant>:<uuid>`, bail obligatoire, aucune dépendance de `api` vers `mutex`, défaut strictement inchangé. Sur un point nous n'avons pas suivi : verrou non obtenu = **409**, pas de dégradation silencieuse. Leurs trois obstacles étaient inexacts — aucune modification de core n'a été nécessaire — et la fiche documente le piège qui les attendait : `sync` de core, branché en enveloppe d'étage, prend le verrou et n'exécute rien. |
| [authenticator-authorities-decoratif](authenticator-authorities-decoratif.md) | palliad | Corrigée par l'option 1 : la déclaration est honorée en retombée (la stratégie garde la main, une liste vide reste autoritative), et un champ illisible se signale au lieu de rendre un jeton vide. |
| [prefixe-http-non-configurable](prefixe-http-non-configurable.md) | autonom | Corrigée, purement additive : `new JavalinInterface(app, "/api")`. Les cinq critères d'acceptation sont tenus, `completePath` tranché dans votre sens. Un angle mort à connaître : `.interfasse(IClass)` instancie sans argument, le préfixe passe par `.interfasse(ISupplierBuilder)`. |

## Ruptures d'API à la montée en `3.0.0-ALPHA17`

Le dossier n'est pas une file de tickets, mais une rupture se prévient. Trois signatures de
`garganttua-api-commons` changent ; seul le cadre les implémente aujourd'hui, et rien n'a été trouvé
qui les appelle hors de lui — c'est écrit ici pour que vous le vérifiiez plutôt que de le découvrir
à la compilation.

| Élément | Avant | Après |
|---|---|---|
| `IEntityDefinition.mandatories()` (et `IDomain.getMandatoryFields()`) | `List<ObjectAddress>` | `List<Pair<ObjectAddress, MandatoryPolicy>>` |
| `IEntityUpdater.update(...)` | rend l'entité | rend un `EntityWriteOutcome` (entité + champs appliqués/écartés) |
| `IEntityCreator.create(...)` | rend l'entité | rend un `EntityWriteOutcome` |

Tout le reste est additif.

## Fiches répondues — `3.0.0-ALPHA24`

Traitées le 2026-09-23, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA24`**. Les deux fiches
sont de palliad, arrivées ensemble et liées : la seconde posait un préalable que la première
attendait.

| Fiche | Émise par | Réponse |
|---|---|---|
| [la-portee-dentityunicity-est-perdue](la-portee-dentityunicity-est-perdue.md) | palliad | Corrigée, littéralement comme la fiche l'écrit — première du dossier dans ce cas. Le scanner passe désormais la portée déclarée. Les trois défauts qu'elle demandait de ne pas toucher n'ont pas bougé. Repli tranché et documenté : une adresse imbriquée non résoluble se lit comme le défaut de l'ANNOTATION (`tenant`). Son §5.3 traité au passage : `@EntityUnicities` et `@EntityMandatories`, déclarées, documentées actives et lues nulle part, sont **supprimées**. |
| [aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas](aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md) | palliad | Traitée en entier, préalable compris. Leur §3.0 est **confirmé et plus large** : méthodes et constructeurs perdaient aussi leurs annotations ; la perte, en revanche, ne touchait que la vue d'ensemble (une recherche par nom retombait déjà sur la classe vivante). Au-delà de la demande, le générateur émet désormais des descripteurs pour les membres **privés**, ce qui rend les vues complètes et supprime la panne de démarrage due à une constante `public static final`. Défaut trouvé en vérifiant le leur : une classe imbriquée `@Reflected` était enregistrée sous son nom pointé et cherchée sous son nom binaire — son descripteur n'était jamais trouvé. `@EntityIndexed` livrée dans leur forme, index posés au `registerDomain`, `mongodb.index.auto` (`create` / `none` / `strict`), démarrage qui réussit avec un WARN nommant la requête d'agrégation des doublons. Une rectification : leur `partialFilterExpression` `{$exists,$ne:null}` est **refusée par mongod** (erreur 67) ; `{$type: […]}` donne le même effet. Mesure rejouée : 30 doublons sur 30 tours, **0 après**. Le natif n'a PAS été mesuré, et la fiche le dit. |

## Ruptures d'API à la montée en `3.0.0-ALPHA24`

| Élément | Avant | Après |
|---|---|---|
| `@EntityUnicity` **nue** (sans `scope`) | produisait une unicité `system` | produit une unicité `tenant`, son défaut déclaré — **la contrainte se resserre par locataire** |
| `@EntityUnicities`, `@EntityMandatories` | déclarées, jamais lues | supprimées |

Le reste est additif. `IEntityDefinition.indexes()` a une implémentation par défaut, donc un
implémenteur extérieur ne casse pas — il déclare zéro index.

## Fiches répondues — `3.0.0-ALPHA25`

Traitées le 2026-10-15, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA25`**. Trois envois de
palliad : deux fiches et un retour de mesure sur une fiche d'ALPHA24.

| Fiche | Émise par | Réponse |
|---|---|---|
| [le-dto-ne-restreint-pas-la-lecture-mongodb](le-dto-ne-restreint-pas-la-lecture-mongodb.md) | palliad | Demande 1 tenue : le DTO est la projection par défaut, mesurée au profileur sur un vrai `mongod` — 85 606 → 5 848 octets, réponse identique ; `readWholeDocuments()` pour revenir en arrière par domaine. Demande 2 : la lenteur de `fields=` venait de la résolution du champ refaite à chaque ligne et chaque champ, sortie de la boucle (JVM, 32 807 × 12 : 170–370 → 15–20 ms) ; **le natif reste à re-mesurer chez eux**. Le raccourci « sélection = entité entière » est refusé : la réponse en changerait de forme. |
| [entityindexed-ne-fait-rien-quand-les-domaines-sont-declares-par-le-dsl](entityindexed-ne-fait-rien-quand-les-domaines-sont-declares-par-le-dsl.md) | palliad | Corrigée, élargie à toute la famille : sous détection automatique, les annotations d'entité sont lues quel que soit le style d'assemblage — balayage ou `apiBuilder.domain(...)`. Le DSL l'emporte sur id / uuid / tenantId, une déclaration faite deux fois compte une fois, crochets compris. Sans détection automatique, aucune annotation n'est lue, et la documentation le dit. |
| [aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas](aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md) — retour du 2026-09-24 | palliad | Leur §3 confirmé et corrigé : la fusion des écritures internes écrivait les champs `static`, donc une constante — en JVM comme en AOT. Notre suggestion de lever leur règle était prématurée, et la réponse le dit. ALPHA23 : jamais publiée, confirmé. |

## Changements de comportement à la montée en `3.0.0-ALPHA25`

| Élément | Avant | Après |
|---|---|---|
| Domaine déclaré par le DSL, api en `autoDetect(true)` | ses annotations d'entité étaient ignorées | elles sont lues : une `@EntityUnicity`, `@EntityMandatory`, `@EntityIndexed` ou un crochet annoté qui dormait sur la classe **s'applique** |
| `MongoDao.find` sans sélection | lisait le document entier | projette sur les champs du DTO — réponse inchangée ; `readWholeDocuments()` pour l'ancien comportement |

Aucune signature publique ne change ; `MongoDao.readWholeDocuments()` et le constructeur à trois
arguments d'`EntityAnnotationScanner` sont additifs.

## Fiches répondues — `3.0.0-ALPHA29`

Traitée le 2026-11-02, corrigée sur `main`, **à paraître dans `3.0.0-ALPHA29`**.

| Fiche | Émise par | Réponse |
|---|---|---|
| [le-dao-postgresql-ne-pose-aucun-index-ni-starter-aot-ni-index-geographique](le-dao-postgresql-ne-pose-aucun-index-ni-starter-aot-ni-index-geographique.md) | palliad | Les trois volets. **A** : index déclarés posés par `PgDao` (`postgresql.index.auto`, contrat de Mongo), `23505` → 409 ; leur mesure rejouée, 30 écrivains → 1 ligne, 29 × 409. Un écart voulu : les noms d'index sont préfixés par la table (PostgreSQL les nomme par schéma). **B** : `starter-aot-postgres-javalin`, et une épreuve native dans le dépôt (`api/e2e/native-postgres`, GraalVM 25, PostGIS, TLS `verify-full`) : 12/12, 4 connexions chiffrées sur 4. Elle a trouvé quatre métadonnées natives que le cadre ne livrait pas, et un défaut qui touche aussi MongoDB : `Repository` perdait l'ordre des lectures triées. **C** : GiST sur `geography`, `$geoWithin` sur la sphère (changement de comportement), `$near` trié, `$geoIntersects`, GIN pour `$text`. Non fait : le point déclaré par deux colonnes plates, avec la raison. |

## Changements de comportement à la montée en `3.0.0-ALPHA29`

| Élément | Avant | Après |
|---|---|---|
| Lectures triées (tous DAO) | ordre du hachage des uuid | ordre rendu par le DAO |
| `$geoWithin` / `$geoWithinSphere` (PostgreSQL) | `ST_Within` planaire | `ST_CoveredBy` sur la sphère |
| `$text` (PostgreSQL), domaine déclarant un index texte | toutes les chaînes de l'entité | le champ déclaré seul |
| Index déclarés (PostgreSQL) | ignorés | posés au démarrage, unicité tenue par la base |

## Fiches répondues — `3.0.0-ALPHA30`

Traitées le 2026-11-02, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA30`**. Deux fiches de palliad,
arrivées ensemble.

| Fiche | Émise par | Réponse |
|---|---|---|
| [le-dao-mongodb-ne-stocke-pas-un-champ-geojson-en-geojson](le-dao-mongodb-ne-stocke-pas-un-champ-geojson-en-geojson.md) | palliad | Corrigée, et la réponse reconnaît que notre conseil précédent (« `IndexKind.geo` sur les deux bases ») n'aurait pas dû être donné sans épreuve. Géométries écrites, relues et filtrées en GeoJSON par `GeoJsonCodec` (module neuf `binding-geojson`), **sans Jackson** — le DAO MongoDB n'en dépend plus, PostgreSQL l'utilise aussi. Éprouvé contre `mongod` 4.4 et 7.0, puis en natif (`api/e2e/native-mongo`, 13/13 sur les deux). `$near` compté en `$centerSphere` ; un second défaut trouvé : la clé `_id` des pages écrasait l'ordre de `$near`. |
| [le-dao-postgresql-reecrit-toutes-les-listes-a-chaque-ecriture](le-dao-postgresql-reecrit-toutes-les-listes-a-chaque-ecriture.md) | palliad | Traitée par leur option 1 : une empreinte par collection, calculée sur les lignes, sur la ligne principale. Ajout : des déclencheurs effacent l'empreinte à toute écriture des tables enfants hors du DAO (SQL, migration, instance ALPHA29 en montée progressive), pour qu'elle ne mente jamais. Mesuré au DAO : 12,9 → 4,2 ms pour un nom changé ; +1,3 ms quand toutes les listes changent. |

## Changements de comportement à la montée en `3.0.0-ALPHA30`

| Élément | Avant | Après |
|---|---|---|
| Champ `org.geojson` sur MongoDB | écrit champ par champ, aucun index `2dsphere` possible | écrit en GeoJSON ; l'ancienne forme se relit, mais bloque l'index tant qu'elle reste dans la collection |
| Lecture MongoDB paginée avec `$near` | ordonnée par `_id`, compte refusé par le serveur | ordonnée par distance, comptée |
| Écriture PostgreSQL d'une entité à collections | toutes les collections réécrites | seules celles qui changent ; colonnes `_digest__*` et déclencheurs ajoutés en `CREATE` |
| `garganttua-api-binding-jackson` | portait `geojson-jackson` | le reçoit de `garganttua-api-binding-geojson` |

## Fiches répondues — `3.0.0-ALPHA34`

Traitées le 2026-11-10, sur la branche `perf/token-verification`, **à paraître dans `3.0.0-ALPHA34`** : les deux fiches de performance et les six du journal d'accès HDS.

| Fiche | Émise par | Réponse |
|---|---|---|
| [la-verification-du-jeton-coute-la-moitie-de-chaque-requete](la-verification-du-jeton-coute-la-moitie-de-chaque-requete.md) | palliad | Cause confirmée : l'opération `authenticate` complète du domaine du jeton à chaque requête, dont la recherche du principal était elle-même un `readAll` imbriqué, et la même ligne stockée lue trois fois. Le passage par ces pipelines reste le **défaut** ; deux réglages du DSL, `authenticateOnVerify` et `lookupOnVerify`, choisissent séparément un chemin `DIRECT` : stratégies exécutées dans la requête, avec les mêmes contrôles dans le même ordre, et principal lu dans le dépôt, puisque c'est la ligne déjà lue. En `DIRECT` : 3 lectures → 1, 2 workflows imbriqués → 0, vérification 2,9 → 0,2–0,8 ms au banc JVM. Révocation identique partout, sans cache. |
| [le-dao-postgresql-convertit-chaque-ligne-par-jackson-puis-champ-par-champ](le-dao-postgresql-convertit-chaque-ligne-par-jackson-puis-champ-par-champ.md) | palliad | Traitée, sur une autre cause que celle supposée : la ligne principale n'est pas un JSONB, mais chaque cellule résolvait son chemin de champs par nom, dans des maps à clés de listes, et les agrégats passaient par un parseur générique. Un plan par table, calculé une fois (`PgRowPlan`), des bits de présence lus par indice et un lecteur d'agrégats sans Jackson : CPU Java d'une lecture de 500 activités 22,9 → 10,2 ms (−55 %). Lecteur Jackson mis en cache par type pour les vraies colonnes JSONB. |
| [le-connecteur-api-abandonne-des-evenements-quand-sa-file-est-pleine](le-connecteur-api-abandonne-des-evenements-quand-sa-file-est-pleine.md) | palliad | `overflow` `drop` (défaut) ou `block` avec `overflowTimeout`, `queueCapacity`, `droppedEvents()` ; un événement jeté garde son numéro. Pas de `SPILL`, avec la raison. |
| [les-evenements-du-connecteur-api-ne-sont-pas-numerotes](les-evenements-du-connecteur-api-ne-sont-pas-numerotes.md) | palliad | `eventId` et `source` sont ceux du `JourneyStep` de l'enveloppe ; `bootId` + `seq` posés avant la file ; à l'arrêt, file vidée puis `endOfSequence`. |
| [kafka-et-bigqueue-acquittent-avant-la-reussite-de-la-route](kafka-et-bigqueue-acquittent-avant-la-reussite-de-la-route.md) | palliad | Qualité de service par souscription dans le DSL (`AT_MOST_ONCE` / `AT_LEAST_ONCE`, refus au démarrage d'une promesse intenable) ; « succès » = en lieu sûr ; `attempts` avant la file d'erreur ; Kafka et BigQueue acquittés après la route. Deux défauts trouvés : `produce` avalait l'échec d'une publication, BigQueue exigeait log4j 1.x. |
| [levenement-dapi-ne-porte-ni-adresse-ip-ni-session](levenement-dapi-ne-porte-ni-adresse-ip-ni-session.md) | palliad | `IEvent.getOrigin()` : adresse et agent posés par le transport, session (uuid de l'autorisation vérifiée) par `VERIFY_AUTHORIZATION` ; `server.trustedProxies` pour `X-Forwarded-For`, lu depuis la droite, sans DNS. |
| [un-observateur-du-flux-ne-peut-pas-faire-echouer-loperation](un-observateur-du-flux-ne-peut-pas-faire-echouer-loperation.md) | palliad | Observateur bloquant (`GlobalObservers.addBlockingObserver`) ; l'opération rend 503 sans sa donnée ; connecteur `api` `delivery=sync`. |
| [le-verrou-de-route-ne-se-cle-pas-sur-le-message](le-verrou-de-route-ne-se-cle-pas-sur-le-message.md) | palliad | `lockObject` `$.chemin` évalué sur chaque message, dans l'enveloppe quand le flux est encapsulé ; valeur absente : le verrou de la route. |
| [en-natif-deux-invokeauthenticate-empechent-lapi-de-monter](en-natif-deux-invokeauthenticate-empechent-lapi-de-monter.md) | palliad | Bogue de la branche ALPHA34 locale, reproduit sur leur binaire natif puis corrigé : la méthode interne renommée, la résolution qui préfère la signature identique à une signature seulement assignable, et un garde-fou de compilation contre toute méthode qui porte le nom et l'arité d'une expression. |

## Changements de comportement à la montée en `3.0.0-ALPHA34`

| Élément | Avant | Après |
|---|---|---|
| Vérification d'un jeton stockable avec `checkStoredOnVerify` | la ligne stockée lue deux fois en dehors de l'opération imbriquée | lue une fois |
| `.authorization().authenticateOnVerify(…)`, `.lookupOnVerify(…)` | n'existaient pas | nouveaux, `PIPELINE` par défaut : le comportement d'avant |
| Connecteur `api`, arrêt propre | la file restante était abandonnée | vidée, puis `endOfSequence` |
| Connecteur `api`, contenu de chaque événement | — | `bootId`, `seq`, `origin` en plus |
| Issue rendue à un `IAcknowledgingConsumer` d'un message déposé en file d'erreur | `false` (rendu au transport, relivré) | `true` (en lieu sûr) |
| `produce` vers les destinations d'une route, une publication en échec | journalisée, route réussie, message perdu | la route échoue (essais, file d'erreur, acquittement) |
| `garganttua-events-connector-bus` | exigeait log4j 1.x sur le classpath | apporte `log4j-over-slf4j` |
| Opération dont un observateur bloquant n'a pas pu enregistrer l'événement | (n'existait pas) | 503, sans la donnée |
