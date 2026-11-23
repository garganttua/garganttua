# Des caches du cadre, à plusieurs niveaux (structure, requête, distribué, interface)

**À l'attention de :** garganttua-core (un module `cache` / `cache-redis`, à côté de `mutex` / `mutex-redis`), garganttua-api (déclaration DSL, invalidation par le pipeline) et garganttua-api interface-rest (niveau HTTP).
**Émis par :** palliad (v3, AOT pur ; distributions mono-nœud ET cluster, Redis déjà présent en cluster pour les verrous).
**Date :** 2026-11-05, **élargie le 2026-11-23** (d'un seul cache distribué à une pile à plusieurs niveaux).
**Version constatée :** garganttua `3.0.0-ALPHA36` (rien n'a été ajouté depuis ALPHA33 ; aucun `ICache` / `CacheSpec` / module `cache` dans les sources).
**Gravité :** le niveau **L2** est le préalable de `la-verification-du-jeton-coute-la-moitie-de-chaque-requete.md` (coût **mesuré** par requête). Sans cache sûr en cluster, il n'y a que deux issues : refaire à chaque requête un travail qui ne change presque jamais, ou mettre un cache local qui laisse passer un jeton révoqué sur les autres nœuds.

## Le besoin, et pourquoi « un cache » ne suffit pas

Le cadre recalcule ou relit à **plusieurs endroits** du chemin d'une requête ; chacun a une **clé**, une **portée** et une **stratégie d'invalidation** différentes. Un cache unique ne peut pas servir les cinq : une mémoïsation de réflexion ne s'invalide jamais et doit rester locale ; une autorisation de jeton s'invalide à la seconde et doit valoir sur tous les nœuds ; une réponse HTTP publique se cache le mieux hors du cadre, dans un CDN ou le proxy.

D'où une **pile de caches**, du plus bas (près du code) au plus haut (près du client). Les niveaux **L2 à L4** partagent une même abstraction (`ICache` + invalidation, décrite plus bas) ; **L0–L1** sont locaux ; **L5** vit au niveau HTTP et ne cache aucune donnée.

| Niveau | Où (couche) | Clé | Portée | Invalidation | État |
|---|---|---|---|---|---|
| **L0 — Structure** | core : réflexion, scripts, mapping, lecteurs BSON | type / code | **locale**, durée du processus | jamais (identique par version) | **existe** (`ScriptCompilationCache`, `ResolutionMemo`, `*AccessManager`, `CachedMappingConfiguration`) |
| **L1 — Par requête** | api : pipeline | adresse / uuid dans *une* requête | **locale**, durée de la requête | meurt avec la requête | partiel — voir `resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md` |
| **L2 — Identité & autorisations** | api : authentification | jeton / appelant | **distribuée-cohérente** | immédiate à la révocation / au retrait de droit | **demandé, pas fait** |
| **L3 — Lecture de domaine (opt-in)** | api : dépôt (le « cached repository ») | entité d'un domaine déclaré « en cache » | **distribuée-cohérente** | par le pipeline CRUD, **avant** de répondre | **demandé, pas fait** |
| **L4 — Résultat de requête / agrégat** | api : opération | requête + filtre | locale ou distribuée, **TTL court** | TTL seul (tolère un léger retard) | idée (ex. `activities/metrics`, listes lourdes) |
| **L5 — Interface HTTP** | api : interface-rest + CDN / proxy | URL + version du contenu | **côté client / CDN / Caddy** | l'`ETag` change avec le contenu | **demandé, pas fait** (le « cached interface ») |

## L2–L4 — l'abstraction partagée : `ICache` + invalidation

Une abstraction de cache du cadre, avec deux implémentations interchangeables et un contrat d'invalidation. **Un seul module, trois usages** (identité, lecture de domaine, résultat de requête).

1. **`ICache<K, V>`** : `get`, `put`, `invalidate(key)`, `invalidateAll()`, une durée de vie maximale par entrée, une taille bornée, et un compteur de succès et d'échecs lisible par l'application.
2. **Une implémentation locale**, en mémoire, pour un seul nœud. C'est le défaut quand rien d'autre n'est configuré.
3. **Une implémentation distribuée, sur Redis**, avec la même connexion et la même configuration que `mutex-redis`. Deux variantes au choix :
   - **cache partagé** : les entrées vivent dans Redis, et tous les nœuds lisent la même valeur ;
   - **cache local à deux niveaux** : une copie locale, plus une **invalidation diffusée** à tous les nœuds (Redis pub/sub, ou un sujet garganttua-events / Kafka). C'est la variante la plus rapide en lecture, et elle tient la même cohérence pour une révocation.
4. **L'invalidation suit les écritures du pipeline.** Une écriture CRUD (`createOne`, `updateOne`, `deleteOne`, `PATCH`) sur un domaine déclaré « en cache » invalide l'entrée correspondante, sur tous les nœuds, **avant** de répondre. La révocation d'une autorisation est alors visible partout dès que la requête de révocation a répondu.
5. **Un comportement sûr en cas de panne.** Si Redis ne répond pas, le cache se comporte comme **absent** : on relit la source, et le service est plus lent mais juste. Il ne sert jamais une valeur dont l'invalidation a pu être manquée. Une durée de vie maximale courte, de quelques secondes, borne le pire cas si un message d'invalidation se perd.
6. **Une déclaration par le DSL**, sur le domaine ou sur l'opération (par exemple `domain.cache(CacheSpec.of().ttl(…).distributed())`, `operation.cache(…)`), et un choix d'implémentation par configuration, sans changement de code entre mono-nœud et cluster.

**L2 (identité & autorisations)** et **L3 (lecture de domaine)** veulent la cohérence stricte : l'invalidation par le pipeline (point 4). **L4 (résultat de requête)** se contente d'un **TTL court sans invalidation précise** — pour un agrégat coûteux qui tolère quelques secondes de retard ; c'est le même `ICache`, déclaré avec un TTL et sans lien d'invalidation.

## L0–L1 restent LOCAUX

Les caches **de structure** (L0), qui ne dépendent que du code et des types — membres résolus, accesseurs de champs, scripts compilés, convertisseurs par type, lecteurs BSON par type — sont identiques sur tous les nœuds d'une même version et ne s'invalident jamais pendant la vie du processus. La **mémoïsation par requête** (L1) meurt avec la requête. Les distribuer coûterait plus que de les recalculer, et introduirait une invalidation là où il n'en faut aucune. Fiches liées : `le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md`, `resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md`.

## L5 — le cache d'interface (HTTP), pas un cache de données

Au niveau REST, le cadre devrait permettre de **cacher la RÉPONSE hors du processus**, sans jamais stocker de donnée métier côté serveur :

- émettre des **validateurs** (`ETag` dérivé du contenu + sa version, `Last-Modified`) et honorer les requêtes conditionnelles (`If-None-Match` → **`304 Not Modified`**) ;
- émettre des directives **`Cache-Control`** (ex. `public, max-age=…, stale-while-revalidate=…`) sur les routes déclarées cacheables, pour que **Caddy / un CDN** servent la réponse sans retoucher l'application ;
- se déclarer **par route ou par contenu** (vitrine, pages de contenu, `sitemap.xml`, médias publics), jamais par défaut.

L'invalidation est triviale et sans cluster : l'`ETag` change quand le contenu change (nouvelle version du binaire, ou écriture du contenu en base). C'est le plus gros gain sur le **site public** et le plus sûr — aucune cohérence inter-nœuds à tenir. C'est un étage distinct de L2–L4 : il ne partage pas l'`ICache`.

## Priorité suggérée (la décision reste à la plateforme)

1. **L2** — coût **mesuré** par requête, et préalable de la fiche jeton. Le plus rentable.
2. **L5** — site public, facile, sans risque de cohérence.
3. **L3 / L4** — à la demande, domaine par domaine, une fois l'`ICache` en place.

(L0 existe ; L1 est couvert par sa propre fiche.)

## Ce que la fiche ne demande PAS

- Ne pas mettre en cache les lectures de données métier **par défaut** : L3/L4 se déclarent, domaine ou opération à la fois.
- Ne pas imposer Redis au mono-nœud ni à l'on-prem mono-instance : l'implémentation locale doit suffire (L2–L4), et L5 doit marcher **derrière Caddy seul**, sans exiger de CDN.
- Ne pas cacher les réponses **authentifiées** au niveau L5 par défaut : l'interface ne pose `Cache-Control: public` que là où c'est déclaré explicitement.
- Ne pas affaiblir l'AOT pur : ni l'implémentation Redis ni le niveau HTTP ne doivent réintroduire de réflexion à l'exécution.
- Ne pas distribuer L0/L1 : ils sont locaux par nature.

## Réponse d'étape — 2026-11-23, sur `main` (prochaine ALPHA) : `ICache`, ses implémentations, L2 et L3

**La pile de niveaux est retenue.** Les niveaux se distinguent par leur emplacement, leur clé et leur invalidation ;
le stockage est indépendant des trois. Un seul contrat de cache, plusieurs implémentations livrées par le cadre,
sur le modèle des mutex.

### Ce qui est fait

1. **Le contrat, dans `garganttua-commons`** :
   - `ICache<K, V>` : `get`, `get(clé, chargeur)`, `put`, `invalidate`, `invalidateAll`, `stats()` ;
   - `CacheSpec` : entrées par nœud, durée de vie, codec pour un stockage hors du processus ;
   - `CacheName`, `ICacheFactory`, `@CacheFactory` (indexée, donc trouvée en natif) et `ICacheManager`.
2. **Quatre implémentations** :
   - `LocalCache` et `NoCache`, dans `garganttua-cache` ;
   - `RedisCache` (valeurs partagées dans Redis) et `RedisNearCache` (copie locale, invalidations diffusées par
     pub/sub), dans `garganttua-cache-redis`, sur le même Jedis que `mutex-redis`.
3. **Le comportement en panne, comme demandé.**
   - Redis injoignable : le cache est absent, pas faux.
   - Un nœud qui n'est plus abonné aux invalidations n'utilise pas sa copie, et la vide en se réabonnant.
   - La durée de vie borne une invalidation perdue.
   - En plus : un chargement dépassé par une invalidation n'est pas rangé. Sinon, une lecture lancée avant une
     écriture aurait remis en cache la valeur que l'écriture venait de remplacer.
4. **L3 et L2.** `domain.cache(gestionnaire, implémentation, spec)` met en cache les lectures **par uuid** du
   domaine.
   - **Emplacement** : dans le dépôt, sous le contrôle d'accès. Il garde des DTO, et chaque lecture refait une
     entité neuve.
   - **Invalidation** : par **toutes** les écritures du dépôt, avant la réponse. Cela couvre le pipeline, mais
     aussi les écritures que le cadre fait sans lui (rotation du rafraîchissement, révocation de lignée). Une
     invalidation accrochée au seul pipeline les aurait manquées.
   - **L2** : la vérification du jeton et le rafraîchissement lisent l'autorisation stockée et le principal par
     `getByUuid`. Déclaré sur le domaine d'autorisations et sur le domaine authentificateur, le cache retire ces
     deux lectures de chaque requête authentifiée.

### Ce qui le prouve

- `RedisCachesTest`, contre un vrai Redis :
  - une valeur partagée entre deux nœuds, invalidée pour tous, expirée ;
  - une invalidation diffusée ;
  - le vidage de la copie après une coupure forcée de l'abonnement (`CLIENT KILL TYPE pubsub`).
- `DomainCacheIntegrationTest` :
  - une seule lecture en base pour deux lectures par uuid ;
  - l'invalidation par le dépôt et par une mise à jour passée par le pipeline ;
  - la suppression ;
  - l'isolation des entités rendues.
- `RefreshRotationIntegrationTest.Cached` : avec les deux domaines en cache, la réutilisation d'un jeton échangé
  est toujours détectée, et une révocation ou une suspension écrite par le dépôt est vue au rafraîchissement
  suivant.

### Pour vous

```java
.domain(Authorization.class).cache(caches, IClass.getClass(RedisNearCache.class), CacheSpec.ttl(30, SECONDS))
.domain(User.class).cache(caches, IClass.getClass(RedisNearCache.class), CacheSpec.ttl(30, SECONDS))
```

`LocalCache` en mono-nœud et en on-prem, `RedisNearCache` en cluster, sur votre Redis déjà présent pour les verrous.

## Réponse — 2026-11-23, sur `main`, à paraître dans `3.0.0-ALPHA37` : L4, L5, les tuiles, et le banc

Tous les niveaux convenus sont livrés. Chacun se déclare seul sur le domaine, avec sa propre clé :

```java
.domain(Organization.class)
    .cache(caches, IClass.getClass(RedisNearCache.class), CacheSpec.ttl(30, SECONDS))       // L3 : par uuid
    .queryCache(caches, IClass.getClass(LocalCache.class), CacheSpec.ttl(30, SECONDS))      // L4 : recherches
    .tileCache(caches, IClass.getClass(LocalCache.class), CacheSpec.of(), "latitude", "longitude")
    .httpCache(HttpCachePolicy.revalidated())                                              // L5 : ETag, 304
```

### Ce qui est fait

1. **L4, les recherches.** `getEntities`, `getEntitiesPage` et `getCount` passent par le cache, sous une clé qui
   reprend toute la requête : l'arbre du filtre, la page, le tri, la projection.
   - Le filtre contient déjà la condition d'accès que le pipeline ajoute. Deux appelants qui voient des lignes
     différentes ne partagent donc jamais une entrée.
   - Toute écriture du domaine vide ce cache, puisqu'elle peut faire entrer ou sortir une ligne de n'importe quelle
     recherche.
2. **Les tuiles, pour la carte.** C'est la réponse à « le zoom n'est jamais le même entre deux requêtes ».
   - Une recherche qui borne la latitude et la longitude des deux côtés par des nombres, sans page ni tri, est lue
     par les cases d'une grille fixe de `180 / 2^z` degrés, avec au plus trois cases par axe. Le niveau `z` suit le
     zoom.
   - Chaque case est mise en cache sous elle-même et sous le reste de la requête, filtre d'accès compris. L'union
     des cases est recoupée **exactement** aux bornes demandées, strictes ou non.
   - Déplacer la carte ne relit que les cases nouvelles. Votre recherche de carte (`healthcare-organization-api.adapter.ts` :
     `latitude: {$gte, $lte}`, `longitude: {$gte, $lte}` et une projection qui garde les deux champs) est de cette
     forme telle quelle.
   - Une recherche `$near` / `$maxDistance` n'est pas lue par tuiles : elle passe normalement, ou par L4.
3. **L5, le HTTP.** Il se fait dans la liaison Javalin, puisque `interface-rest` est un module vide.
   - Un GET qui répond 200 reçoit un `ETag` fort et le `Cache-Control` déclaré. Un `If-None-Match` qui correspond
     reçoit un `304` sans corps.
   - `public` n'est envoyé qu'aux requêtes anonymes. Une requête authentifiée reçoit `private`, et toutes reçoivent
     `Vary: Accept, Authorization`.
4. **Un défaut trouvé par le banc, corrigé.** Chaque `RedisNearCache` tenait une connexion du pool pour toute la
   durée de son abonnement. Avec huit caches, le pool Jedis par défaut était vide, et tout appel Redis attendait
   indéfiniment, y compris les verrous s'ils partagent le pool.
   - Les caches d'une même `RedisNearCacheFactory` partagent maintenant **un** abonnement (un motif sur tous les
     canaux), donc une seule connexion, quel que soit leur nombre.
   - Créez la fabrique une fois par application et fermez-la (`close()`) à l'arrêt.

### Ce qui le prouve

- `TileCacheIntegrationTest` :
  - sur 200 cadrages aléatoires, bornes inclusives et strictes, avec ou sans autres conditions, les mêmes lignes
    que la base ;
  - une ligne exactement sur la borne ;
  - le déplacement de la carte qui relit moins ;
  - l'écriture qui vide les tuiles ;
  - une recherche ouverte d'un côté, qui n'est pas lue par tuiles.
- `DomainCacheIntegrationTest.Searches` : l'isolation entre deux tenants à travers le pipeline, et l'invalidation.
- `JavalinHttpCacheTest`, plus l'E2E du starter : `ETag`, `public, max-age=300`, `304` à corps vide.
- `RedisCachesTest` : dix caches d'une même fabrique sur un pool de **deux** connexions, tous abonnés et tous
  invalidés.

### Le banc : avec et sans cache

Le module `api/bench/cache-benchmark` (profil `-Pbench`, hors du build normal) mesure chaque niveau contre un vrai
MongoDB 7, un vrai PostgreSQL 17 et un vrai Redis 7, en Docker sur la boucle locale. Le client est seul, sur un seul
fil. Les tableaux complets sont dans son README. L'essentiel :

| Niveau | Base | Sans cache | Avec | Requêtes en base |
|---|---|---|---|---|
| L2/L3, lecture par uuid | MongoDB | 7 000 /s, p50 110 µs | `LocalCache` 116 000 /s, p50 1,2 µs ; `RedisNearCache` 104 000 /s | 50 000 → 3 564 |
| L2/L3, lecture par uuid | PostgreSQL | 19 000 /s, p50 46 µs | `LocalCache` 215 000 /s ; `RedisNearCache` 152 000 /s | 50 000 → 3 564 |
| L2/L3, avec 2 % d'écritures | MongoDB | 8 700 /s | `LocalCache` 63 000 /s ; `RedisNearCache` 53 000 /s | 50 000 → ~4 500 |
| L4, mêmes recherches (250 lignes) | MongoDB | 2,0 ms | 0,3 ms | 5 000 → 0 |
| L4, avec 2 % d'écritures | MongoDB | 1,7 ms | 0,8 ms | 5 100 → 1 936 |
| Carte, 300 cadrages | MongoDB | p50 250 µs | tuiles : p50 20–36 µs | 301 → 115 |
| Carte, 300 cadrages | PostgreSQL | p50 190 µs | tuiles : p50 13–18 µs | 301 → 115 |
| L5, liste de 200 lignes | MongoDB | 6,9 ms, 67 Ko | `304` : 6,0 ms, 0 octet ; `304` + L4 : 3,7 ms | 1 000 → 0 avec L4 |

Ce que les chiffres disent, y compris ce qu'on n'attendait pas :

- **`RedisNearCache` est le cache distribué à prendre.** Il fait presque aussi bien que `LocalCache`, puisqu'un
  succès ne fait aucun aller-retour.
- **`RedisCache` est plus lent que PostgreSQL sans cache** sur une lecture par clé primaire : 14 800 /s contre
  19 000 /s. L'aller-retour Redis plus la sérialisation coûtent plus que l'index. Il ne vaut que devant une base
  lente ou lointaine.
- **Le cache de requêtes à clé exacte ne sert à rien sur une carte** : 288 requêtes en base sur 301. Les tuiles
  divisent ces requêtes par 2,6 et la médiane par 7 à 14. Le prix : un cadrage qui manque ses cases lit jusqu'à neuf
  fois sa surface, et le p99 monte (MongoDB 2,5 ms ; PostgreSQL 1,0 ms en `LocalCache`). Sur 30 000 points, l'index
  suffisait presque. Le gain grandit avec la densité et avec la distance à la base.
- **Le `304` économise le réseau, pas le serveur.** Sans L4, le pipeline relit et sérialise tout pour calculer
  l'`ETag` : il économise 67 Ko par réponse mais seulement 12 % du temps. Avec L4 dessous, une requête passe de
  6,9 à 3,7 ms. Le reste est le coût du pipeline et de la conversion de 200 lignes, que le cache ne touche pas.
- **Les écritures pèsent lourd sur L4** : chacune vide toutes les recherches du domaine. À 2 % d'écritures, le gain
  passe de ×6,5 à ×2. Réservez L4 aux domaines lus bien plus qu'écrits.

### Pour vous

Le plus rentable, dans l'ordre :

1. **L2/L3** sur le domaine d'autorisations et sur le domaine authentificateur, en `RedisNearCache`. Cela retire
   deux lectures de chaque requête authentifiée.
2. **Les tuiles** sur le domaine des établissements, si la carte est un chemin chaud.
3. **L4** sur les référentiels peu écrits.
4. **L5** sur les listes publiques.

Pour la conformité HDS : les caches stockent les données brutes sous le contrôle d'accès, et le HTTP n'est `public`
que pour l'anonyme. En `RedisCache`, les données de santé passent par Redis. `RedisNearCache` ne fait passer que des
clés.
