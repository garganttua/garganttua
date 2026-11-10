# La vérification du jeton coûte la moitié de chaque requête

**À l'attention de :** garganttua-api
**Émis par :** palliad (v3, AOT pur, natif G1 + PGO et JVM HotSpot ; jeton porteur ES256, `checkStoredOnVerify(true)`)
**Date :** 2026-11-05
**Version constatée :** garganttua-api `3.0.0-ALPHA33`
**Gravité :** la plus forte mesurée. Sur une requête ordinaire, la vérification du jeton pèse plus que le travail
demandé. Sur 2 vCPU, c'est environ la moitié de la capacité d'un serveur qui part dans cette vérification.

## Symptôme (mesuré)

Banc de charge de palliad (« Banc 5 · Profil d'exécution », `palliad/docs/benchmark-profil-execution.md`), binaire natif
G1 + PGO, MongoDB, 2 vCPU / 1 Go, 4 utilisateurs sans pause, parcours réels des écrans. Le chronométrage par étape
(`workflowTiming`) et un observateur sur chaque domaine et chaque workflow donnent, pour **2 517 requêtes HTTP** :

| Opération imbriquée sur le domaine `authorizations` | Nombre | Par requête HTTP | Moyenne |
|---|---|---|---|
| `authorizations-read-all-authorizations` | 5 006 | **2** | 17,1 ms |
| `authorizations-create-one-authorization` (portant l'étape `authenticate`) | 2 489 | **1** | 28,3 ms |

| Étape du workflow de la requête | ms par requête |
|---|---|
| **`verify-authorization`** | **43,2** (MongoDB) — 42,9 (PostgreSQL) |
| `readAll` (le travail demandé, quand c'est une liste) | 12,2 |
| `serialize` | 2,8 |
| `exit-code` | 2,5 |

Dans les étapes des appels imbriqués à `authorizations` : `authenticate` 18,0 ms, **`exit-code` 6,5 ms**, `readAll` 5,2 ms
par requête HTTP.

La médiane d'une requête est de 80 ms. La même répartition se lit sur la JVM, sous Java Flight Recorder : 15 % du
processeur est sous `SecurityAuthenticationExpressions`, et autant sous `Domain.invoke` du domaine des autorisations.
Les deux bases donnent le même coût de vérification. Ce n'est donc pas la base de données.

## Cause (lu, à confirmer par l'équipe)

`VERIFY_AUTHORIZATION.gs` documente qu'après le décodage, si un authentificateur est câblé sur le domaine cible, le
pipeline **`authenticate` complet** est invoqué (statut du compte, résolution du principal). palliad câble un
authentificateur à jeton sur `authorizations`. Chaque requête porte donc un workflow entier du domaine
`authorizations` : protocole, désérialisation, règles, deux lectures, étape métier, sérialisation, `exit-code`.
Avec `checkStoredOnVerify`, `SecurityVerificationSupport` relit en plus l'enregistrement stocké, mais par le
dépôt, sans pipeline.

Le nombre exact de lectures (deux par requête) et l'opération « create-one » qui porte l'authentification sont **mesurés**.
Le chemin d'appel exact qui les produit reste **à confirmer** de votre côté.

## Attendu

Vérifier un jeton porteur déjà signé (ES256) et le confronter à son enregistrement stocké (révocation) devrait coûter
une lecture indexée par `uuid` et une vérification de signature. Cela fait de l'ordre de la milliseconde, sans workflow
imbriqué complet. Pistes, à votre jugement :
- un chemin direct de vérification, sans pipeline : signature, puis lecture par `uuid`, puis contrôles intrinsèques ;
- et une seule lecture par requête au lieu de deux ;
- **puis** un cache de l'autorisation vérifiée, **qui doit être distribué**. palliad tourne en cluster : une révocation
  faite sur un nœud doit valoir immédiatement sur tous les autres. Un cache local laisserait passer un jeton révoqué sur
  les autres nœuds pendant toute sa durée de vie, ce qui est inacceptable pour des données de santé. Le cache doit
  donc passer par le cache du cadre demandé dans `un-cache-du-cadre-local-et-distribue.md` : soit une copie locale
  avec invalidation diffusée à tous les nœuds, soit des entrées partagées dans Redis. L'invalidation doit se faire à la
  révocation, avant que la requête de révocation réponde, avec une durée de vie maximale courte en filet.
  L'implémentation locale reste la règle en mono-nœud.

## Ce que la fiche ne demande pas

- Ne pas affaiblir la révocation : `checkStoredOnVerify` doit continuer de refuser un jeton révoqué, sur **tous** les
  nœuds. Un cache purement local n'est acceptable qu'en mono-nœud.
- Ne pas changer le format du jeton ni l'API de l'authentificateur.

## Réponse de la plateforme — 2026-11-10

**Traitée par votre première piste, un chemin direct sans pipeline, mais en option à déclarer.** Le chemin direct
ne s'active pas par défaut. La révocation n'est affaiblie dans aucune combinaison. Sur la branche
`perf/token-verification`, à paraître dans `3.0.0-ALPHA34`. La troisième piste, le cache distribué, attend la fiche
`un-cache-du-cadre-local-et-distribue.md`.

### La cause, confirmée

Votre lecture était juste. Quand le domaine du jeton déclare un authentificateur, `verifyAuthorization` invoquait
l'opération `authenticate` complète de ce domaine à chaque requête (`invokeAuthenticate`) :

- un workflow imbriqué, avec protocole, désérialisation, `exit-code`, etc. ;
- sa recherche du principal (`PrincipalSupplier`) passait par un `readAll` du domaine, c'est-à-dire un **second**
  workflow imbriqué ;
- après quoi `checkStoredOnVerify` relisait l'enregistrement stocké, puis le principal final le relisait **une
  troisième fois** (`serverAuthoritativeAuthorization`), pour obtenir la même ligne.

### Ce qui est fait : deux réglages, sur le domaine du jeton

Le passage par les pipelines imbriqués a sa raison d'être : la vérification y est une opération comme les autres,
que les observateurs, les étapes et les règles de DTO du domaine voient. Il reste donc le **défaut**. Le chemin
direct se déclare sur `.authorization()`, à côté de `checkStoredOnVerify`, et chaque moitié se choisit séparément :

```java
authorization.security()
        .authorization()
            ...
            .checkStoredOnVerify(true)
            .authenticateOnVerify(VerificationPath.DIRECT)
            .lookupOnVerify(VerificationPath.DIRECT)
```

| Réglage | `PIPELINE` (défaut) | `DIRECT` |
|---|---|---|
| `authenticateOnVerify` : comment s'exécute l'authentificateur du jeton | l'opération `authenticate` du domaine, imbriquée | ses stratégies, dans le pipeline de la requête (`TokenSelfVerification`) |
| `lookupOnVerify` : comment est lu le principal que reçoivent ses stratégies | l'opération `readAll` du domaine, imbriquée | le dépôt ; pour un jeton stockable dont le login est l'uuid, c'est votre cas, la ligne que `checkStoredOnVerify` lit de toute façon |

Ce qui reste vrai dans les quatre combinaisons :

- **les mêmes contrôles, dans le même ordre**, que ceux de `AUTHENTICATE.gs` :
  - signature contre la clé nommée par le jeton ;
  - tenant exigé pour un authentificateur `tenant` ;
  - principal trouvé par login ;
  - statut du compte ;
  - stratégies dans l'ordre déclaré : la première réponse authentifiée gagne, aucune donne 401 ;
- **le refus d'un jeton révoqué ou supprimé**, sur tous les nœuds, puisque la ligne est relue à chaque requête ;
- le format du jeton, l'API de l'authentificateur et les paramètres de vos stratégies.

Les deux réglages sont indépendants. En `lookupOnVerify(DIRECT)` avec `authenticateOnVerify(PIPELINE)`, la ligne
déjà lue est remise à l'opération imbriquée (`KnownPrincipal`), qui ne la relit pas. Hors pipeline, c'est-à-dire
lors d'un appel Java direct sans contexte d'exécution, l'opération `authenticate` reste le seul chemin possible.

Une amélioration s'applique à tous, y compris par défaut : la ligne stockée n'est plus lue qu'une fois pour le
contrôle `checkStoredOnVerify` et pour la copie qui fait autorité (`StoredRecord`), au lieu de deux.

### Mesuré

Un test pose votre forme exacte (`SelfVerifyingTokenCostTest`) : domaine de jetons stockable, `checkStoredOnVerify`,
authentificateur `login("uuid")` avec `PrincipalSupplier` et `DecodedAuthorizationSupplier`. Il rejoue chaque contrôle
sur les quatre combinaisons et fige ce qui suit :

| Par requête protégée | ALPHA33 | Défaut ALPHA34 | `DIRECT` des deux côtés |
|---|---:|---:|---:|
| Lectures de la table des jetons | 3 | 2 | **1** |
| Workflows imbriqués (`authenticate`, puis `readAll`) | 2 | 2 | **0** |
| Appels de la stratégie | 1 | 1 | 1 |

Temps de la vérification seule, JVM, DAO en mémoire : environ **2,9 ms par les pipelines, 0,2 à 0,8 ms en
`DIRECT`**. La différence se mesure entre une lecture protégée et la même lecture anonyme, sur une machine chargée,
d'où la fourchette. Ce chiffre ne remplace pas votre banc : nous n'avons ni votre binaire natif, ni MongoDB, ni
ES256 réels.

### Ce que vous avez à faire

Déclarer les deux réglages sur votre domaine `authorizations` (`PalliadAuthAutoConfiguration`). Sans eux, rien ne
change pour vous, sauf la lecture en double supprimée.

### Ce qui reste par requête, et que nous n'avons pas touché

- **Une lecture de la clé de signature** (`DomainKeySupplier`, par le dépôt et sans pipeline). Une clé révoquée doit
  être refusée tout de suite, donc la mettre en cache relève de la même fiche que le cache des autorisations.
- **Une lecture du propriétaire** (le domaine `users`), pour construire le principal final.
- La vérification ES256 elle-même, et la réconciliation de l'appelant.

### Ce que nous n'avons pas pu confirmer

Vous mesuriez **deux** `read-all-authorizations` par requête. Notre banc n'en reproduit qu'un : celui du
`PrincipalSupplier`, appelé une fois par stratégie déclarée sur le domaine. Si votre domaine d'autorisations déclare
deux stratégies, cela fait deux lectures. En `lookupOnVerify(DIRECT)`, les deux disparaissent, puisque la ligne est
déjà connue. Dites-nous si votre banc en voit encore une après la montée.

### À savoir si vous passez en `DIRECT`

Votre observateur par domaine **ne verra plus** l'opération `authenticate` imbriquée du domaine `authorizations` à
chaque requête, ni ses `readAll` : en `DIRECT`, elles n'existent plus. Les connexions par mot de passe, elles, sont
inchangées.
