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

### Ce qui reste, dans l'ordre convenu

- **L5**, cache d'interface : `ETag` et `304` sur les GET, `Cache-Control` sur les routes déclarées. Il se fait dans
  la liaison Javalin, puisque `interface-rest` est un module vide.
- **L4**, résultat d'opération : le même `ICache` avec une durée de vie. La clé comprend l'appelant, puisque le
  résultat est déjà filtré par les droits. Les résultats sont vidés à chaque écriture du domaine.
- **Le cache géographique par tuiles**, une stratégie de clé sur `ICache` au niveau du dépôt. À mesurer d'abord
  (`explain` sur une vraie recherche) : l'index géographique suffit peut-être.
