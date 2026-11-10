# Un cache du cadre, local et distribué, invalidé par les événements

**À l'attention de :** garganttua-core (nouveau module, à côté de `mutex` / `mutex-redis`) et garganttua-api
**Émis par :** palliad (v3, AOT pur ; distributions mono-nœud ET cluster, Redis déjà présent en cluster pour les verrous)
**Date :** 2026-11-05
**Version constatée :** garganttua `3.0.0-ALPHA33`
**Gravité :** c'est le préalable de la fiche `la-verification-du-jeton-coute-la-moitie-de-chaque-requete.md`.
Sans cache sûr en cluster, il n'y a que deux issues : refaire à chaque requête un travail qui ne change presque jamais,
ce qui est le coût mesuré, ou mettre un cache local qui laisse passer un jeton révoqué sur les autres nœuds.

## Le besoin

Plusieurs choses que le cadre recalcule à chaque requête ne changent que rarement : l'autorisation stockée d'un
jeton, les autorités d'un appelant, un enregistrement de configuration. Un consommateur qui tourne sur **plusieurs
nœuds** (palliad en cluster : `palliad-cluster`, Redis, Kafka) ne peut pas les mettre en cache **localement**. Une
révocation, un retrait de droit ou une modification faits sur un nœud doivent valoir **immédiatement** sur tous les
autres.

## Ce qui est demandé

Une abstraction de cache du cadre, avec deux implémentations interchangeables et un contrat d'invalidation :

1. **`ICache<K, V>`** : `get`, `put`, `invalidate(key)`, `invalidateAll()`, une durée de vie maximale par entrée, une
   taille bornée, et un compteur de succès et d'échecs lisible par l'application.
2. **Une implémentation locale**, en mémoire, pour un seul nœud. C'est le défaut quand rien d'autre n'est configuré.
3. **Une implémentation distribuée, sur Redis**, avec la même connexion et la même configuration que `mutex-redis`.
   Deux variantes au choix :
   - **cache partagé** : les entrées vivent dans Redis, et tous les nœuds lisent la même valeur ;
   - **cache local à deux niveaux** : une copie locale, plus une **invalidation diffusée** à tous les nœuds (Redis
     pub/sub, ou un sujet garganttua-events / Kafka). C'est la variante la plus rapide en lecture, et elle tient la
     même cohérence pour une révocation.
4. **L'invalidation suit les écritures du pipeline.** Une écriture CRUD (`createOne`, `updateOne`, `deleteOne`, `PATCH`)
   sur un domaine déclaré « en cache » invalide l'entrée correspondante, sur tous les nœuds, **avant** de répondre. La
   révocation d'une autorisation est alors visible partout dès que la requête de révocation a répondu.
5. **Un comportement sûr en cas de panne.** Si Redis ne répond pas, le cache se comporte comme **absent** : on relit la
   source, et le service est plus lent mais juste. Il ne sert jamais une valeur dont l'invalidation a pu être manquée.
   Une durée de vie maximale courte, de quelques secondes, borne le pire cas si un message d'invalidation se perd.
6. **Une déclaration par le DSL**, sur le domaine ou sur l'opération (par exemple
   `domain.cache(CacheSpec.of().ttl(…).distributed())`), et un choix d'implémentation par configuration, sans
   changement de code entre mono-nœud et cluster.

## Ce qui n'a PAS besoin d'être distribué

Les caches **de structure**, qui ne dépendent que du code et des types : membres résolus, accesseurs de champs, scripts
compilés, convertisseurs par type, lecteurs BSON par type. Ils sont identiques sur tous les nœuds d'une même version,
et ne s'invalident jamais pendant la vie du processus. Ils restent **locaux** (fiche
`le-moteur-de-scripts-passe-son-temps-dans-des-tables-de-hachage.md`). Les distribuer coûterait plus que de les
recalculer.

## Ce que la fiche ne demande pas

- Ne pas mettre en cache les lectures de données métier par défaut : le cache se déclare, domaine par domaine.
- Ne pas imposer Redis au mono-nœud ni à l'on-prem mono-instance : l'implémentation locale doit suffire.
- Ne pas affaiblir l'AOT pur : l'implémentation Redis ne doit pas réintroduire de réflexion à l'exécution.
