# garganttua-events — suivi de migration (legacy GGEvents → ALPHA04)

Suivi du portage de l'ancienne implémentation **GGEvents** (basée `garganttua-tooling`, package
`com.gtech.garganttua`, préfixe `GG`) vers la réécriture **ALPHA04** (basée `garganttua-core`,
package `com.garganttua.events`, plus de préfixe `GG`).

- **Source legacy** : `…/INTERNAL/legacy/garganttua-events`, commit `6a88383` (« initial comit »),
  arbre `src/main/java/com/gtech/garganttua/core/engine/…`.
- **Cible** : `…/INTERNAL/garganttua/events` (`api` + `core` + `expressions` + `connector-*`).
- **Bascule d'architecture** : la chaîne de processeurs transactionnelle legacy
  (`GGSynchronizedLinkedProcessorList`, `createTransaction`/`pop`/`flush`) est remplacée par le
  moteur **garganttua-core Workflow → Script → Runtime → Expression**. Un `RouteDef` compile en
  `IWorkflow` ; chaque processeur devient un stage `exchange <- <@Expression>(…)`.

Légende statut : ✅ porté · ⚠️ partiel · ❌ absent · ➕ nouveau en ALPHA04 (absent du legacy).

---

## 0. État de la migration (à jour)

**Migration fonctionnellement terminée.** Tout le comportement *réel* du legacy est porté sur
`main` :

- ✅ Modes de publication `ON_CHANGE` / `TIME_INTERVAL` + `buffered` / `bufferPersisted` (chantier 5,
  intégré au refactor multi-`.to()` de l'autre agent).
- ✅ `protocol_in` / `protocol_out` (auto-wrap), `produce` (auto, **multi-cible** : fan-out vers
  plusieurs `to`).
- ✅ **`filter_in` auto-injecté** depuis la consumer config (sur flux encapsulé).
- ✅ **`filter_out` auto-injecté** depuis la producer config, appliqué **par cible** dans
  `publishToTarget` (`TO_ANY` → broadcast/`toUuid` effacé ; `ONLY_TO_*` → adresse conservée) —
  correct pour le fan-out multi-`.to()` (chaque destination porte sa propre policy via `OutboundTarget`).
- ✅ Dead-letter d'erreur, `source()` external loading, DSL `processor()`.
- ✅ **Synchronisation `route(...).synchronization(lock, lockObject)`** : branchée sur le **mutex de
  garganttua-core** (`IMutexManager` / `IMutex`), **pas** de mécanisme interne à events. Le workflow
  par message tourne dans `IMutex.acquire(...)` (bloque, exécute, relâche). Un `lock` simple utilise
  le `InterruptibleLeaseMutex` par défaut ; un `lock` qualifié `Type::name` sélectionne une factory
  `@MutexFactory` enregistrée — donc le **lock distribué se branche via le SPI mutex du core**, sans
  rien d'events. L'ancien `IDistributedLock` (abstraction interne) et le registre `ClusterRuntime.locks`
  ont été **supprimés**.
- ✅ Concurrence par subscription, firehose observabilité, auto-détection `@Connector` (nouveautés ALPHA04).

**Constat clé** : `buffered`/`bufferPersisted`, **tenant partitioning policy** et **topic routing**
étaient des **enums/flags dormants dès le legacy** (jamais agis) — il n'y a donc **rien à porter** ;
ils sont déjà à parité (déclarés, inertes). Les implémenter serait de **nouvelles features** à
concevoir, pas de la migration.

**Reliquat réel** : aucun. Tout le comportement du legacy est porté. Pour activer un lock *distribué*
(Redis / DB / Zookeeper), il suffit désormais de fournir une implémentation `IMutex` + `@MutexFactory`
côté garganttua-core et de référencer son type dans le `lock` qualifié de la route — aucune
modification d'events requise.

---

## 1. Bilan des anciens processeurs

| Processeur legacy | Rôle / comportement | Construit depuis | Équivalent ALPHA04 | Statut |
|---|---|---|---|---|
| `GGProtocolInProcessor` | Si `encapsulated` : désérialise l'enveloppe `Message`, hérite/initialise l'uuid de parcours, ajoute un **JourneyStep IN**. | dataflow entrant (`encapsulated`, version), assetId, clusterId, subscriptionId | `@Expression protocol_in` | ✅ (logique identique) |
| `GGProtocolOutProcessor` | Si `encapsulated` : ajoute un **JourneyStep OUT**, sérialise l'exchange en `Message`. | dataflow sortant, topic, version, subscriptionId | `@Expression protocol_out` | ✅ |
| `GGInFilterProcessor` | Filtre entrant : applique `originPolicy` + `destinationPolicy` (ex. `ONLY_TO_ASSET` → `toUuid == assetId` sinon `GGCoreFilterException`). | `ConsumerConfiguration`, assetId, clusterId | `@Expression filter_in` (mêmes contrôles : version + policies) | ✅ existe / ⚠️ **non auto-injecté** |
| `GGOutFilterProcessor` | Filtre sortant : applique la `destinationPolicy` du producteur, fixe `toUuid` selon la policy. | `ProducerConfiguration` | `@Expression filter_out` | ✅ existe / ⚠️ **non auto-injecté** |
| `GGOnChangeProducer` | `handle()` publie **immédiatement** vers le connecteur. `start()` = no-op. | subscription `to` | `produce` immédiat (stage auto) | ✅ |
| `GGTimeIntervalProducer` | `handle()` **stocke** le dernier exchange (sous lock) ; `scheduleAtFixedRate(0, interval, unit)` publie le dernier à chaque tick (« last-wins »). | subscription `to`, `timeInterval` | `TimeIntervalProducer` (décorateur) | 🟠 chantier 5 |
| `GGOnChangeConsumer` | Consommateur direct (dispatch immédiat aux routes). | subscription `from` | thread consumer + dispatch | ✅ |
| `GGTimeIntervalConsumer` | Consommateur échantillonné. **Commenté dès le legacy** (`GGSubscription:60`) — jamais actif. | — | — | ❌ (jamais utilisé) |
| `GGCoreFilterException` | Exception « message filtré » : interrompt la route sans erreur (drop propre). | — | `FilterException` (events-expressions) | ✅ |

---

## 2. Chaîne de processeurs auto-ajoutée par le framework (début/fin de route)

### Legacy (`GGRoute`)
```
[début]  GGProtocolInProcessor            (toujours ; encapsulation gérée EN INTERNE)
[début]  GGInFilterProcessor  (filter_in) (depuis la consumer config)
         … processeurs métier déclarés …
[fin]    GGProtocolOutProcessor           (toujours)
[fin]    GGOutFilterProcessor (filter_out)(depuis la producer config)
[fin]    producer                          (OnChange | TimeInterval)
[exc.]   out-filter → protocol_out → producer   (chaîne d'exception séparée)
```

### ALPHA04 (chantier auto-wrap)
```
[début]  protocol_in    (SEULEMENT si dataflow entrant encapsulated)
         … stages métier déclarés …
[fin]    protocol_out   (SEULEMENT si dataflow sortant encapsulated)
[fin]    produce        (si `to`)
[exc.]   dead-letter niveau engine → publie l'exchange sur la subscription d'erreur
```

**Écarts** :
- ❌ ALPHA04 n'auto-injecte **pas** `filter_in` / `filter_out` (à déclarer en stage manuel).
- ⚠️ `protocol_in/out` conditionnés au flag `encapsulated` (legacy : toujours présents, test interne).
- 🔄 multi-cibles `to` (plusieurs sorties) : **en cours par un autre agent** (`OutboundTarget`,
  `StringOrListDeserializer`).

---

## 3. Diff exhaustif des features

| Feature | Legacy | ALPHA04 | Statut |
|---|---|---|---|
| Publication `ON_CHANGE` | `GGOnChangeProducer` | produce immédiat | ✅ |
| Publication `TIME_INTERVAL` | `GGTimeIntervalProducer` (last-wins) | `TimeIntervalProducer` | 🟠 chantier 5 |
| `buffered` / `bufferPersisted` | déclarés, **dormants même en legacy** | implémentés (last-wins / batch mémoire / batch persisté fichier) | 🟠 chantier 5 |
| Auto `filter_in` / `filter_out` | auto-ajoutés | `@Expression` présents, non auto-injectés | ❌ |
| `protocol_in` / `protocol_out` | toujours (encapsulation interne) | conditionnels au flag | ⚠️ |
| Exceptions | chaîne de processeurs d'exception | dead-letter niveau engine | ✅ (simplifié) |
| Synchronisation / lock | `GGLockObject` actif | **mutex garganttua-core** (`IMutexManager`/`IMutex`) ; `IDistributedLock` interne supprimé | ✅ via core mutex |
| Tenant partitioning policy | `GGContextTenantPartitioningPolicy` | absent | ❌ |
| Topic routing | `GGContextTopicRouting` | absent | ❌ |
| Consumer config | processMode/origin/dest/HA | + `concurrency` | ✅ étendu |
| Producer config | `GGContextProducerConfiguration` | `ProducerConfigurationDef` | ✅ |
| Multi-cible `to` | (single) | `OutboundTarget` | 🔄 autre agent |
| Exécution des processeurs | `GGSynchronizedLinkedProcessorList` (transactionnel) | core Workflow→Script→Runtime | 🔄 ré-architecturé |
| Sources de contexte | `GGContextItemSource` | `source()` DSL (file/resource/json) | ✅ |
| DSL stage | `processor` | `expression()` → **renommé `processor()`** | ✅ chantier 5 |
| Threading / concurrence | (consumer config) | concurrence par subscription + `RouteDispatcher` | ➕ ALPHA04 |
| Firehose observabilité | — | `GlobalObservers` | ➕ ALPHA04 |
| Auto-détection connecteur | (registre manuel) | `@Connector` (`@Indexed`) | ➕ ALPHA04 |

---

## 4. Modes de publication (sémantique cible)

- `ON_CHANGE` (défaut) : publication immédiate à chaque message.
- `TIME_INTERVAL` : publication cadencée par `timeInterval` (`{interval, unit}`).
  - `buffered = false` : **last-wins** — seul le dernier exchange de l'intervalle est émis (sémantique legacy).
  - `buffered = true` : **batch** — tous les exchanges de l'intervalle sont émis à chaque tick.
    - `bufferPersisted = false` : buffer mémoire.
    - `bufferPersisted = true` : buffer **fichier** (frames préfixées par longueur), rejoué au redémarrage.

> Note : `buffered`/`bufferPersisted` étaient **dormants dans le legacy** (jamais agis) — la
> sémantique ci-dessus est **définie pour ALPHA04**, pas un portage à l'identique.

---

## 5. Chantiers de finalisation (ALPHA04)

| # | Chantier | Commit | Statut |
|---|---|---|---|
| 1 | Auto-wrap (protocol_in/out + produce injectés ; `encapsulated` porteur) | `51ffa14` | ✅ poussé |
| 2 | Concurrence par subscription (`garanteeOrder` porteur) | `14631df` | ✅ poussé |
| 3 | Dead-letter d'erreur + hook de synchro | `bc7e087` | ✅ poussé |
| 4 | `source()` external loading + doc | `927a916` | ✅ poussé |
| 5 | TIME_INTERVAL + buffered/bufferPersisted + DSL `processor()` | — | 🟠 implémenté, **non commité** (collision) |

Fichiers du chantier 5 (à committer après résolution de la collision) :
- `api/.../context/SubscriptionDef.java` (+ `buffered`/`bufferPersisted`, ctor compat 8-args)
- `api/.../dsl/IRouteStageBuilder.java` + `core/.../dsl/RouteStageBuilder.java` (`expression()` → `processor()`)
- `core/.../TimeIntervalProducer.java` (neuf)
- `core/.../Events.java` (scheduled executor, `wrapForPublicationMode`, lifecycle) ← **en conflit**
- `core/.../context/JsonContextReader.java` (parse `timeInterval`/`buffered`/`bufferPersisted`)
- `core/.../test/EventsRouteE2ETest.java` (tests TIME_INTERVAL last-wins / batch — verts en isolation)

---

## 6. Gaps restants

- ✅ **Auto-injection `filter_in`/`filter_out`** — porté (`RouteWorkflowCompiler.addStages` pour
  `filter_in` ; `publishToTarget` par cible pour `filter_out`).
- ✅ **Lock de synchronisation** — branché sur le **mutex de garganttua-core** (`IMutexManager`/`IMutex`) ;
  l'ancien `IDistributedLock` interne et `ClusterRuntime.locks` ont été supprimés. Un lock distribué
  s'ajoute par une `@MutexFactory` côté core (aucune modification d'events).
- 🟰 **Tenant partitioning policy** + **topic routing** — enums **dormants dès le legacy** (jamais
  agis) : déjà à parité, rien à porter ; les activer serait une nouvelle feature.
- 🟰 **Parallel-keyed** (concurrence + ordre) : impossible tant que le SPI consumer `byte[]` n'expose
  pas de clé de partition (limite de contrat, pas un écart de migration).

---

## 7. Coordination (⚠️ collision en cours)

Au moment du chantier 5, **un autre agent refactore en parallèle** les mêmes fichiers
(`Events.java`, `SubscriptionDef`, `RouteDef`, DSL de route) pour le support **multi-cibles `to`**
(`OutboundTarget`, `StringOrListDeserializer`). L'arbre de travail est un mélange instable des deux
séries de modifs et **ne compile pas** transitoirement (`OutboundTarget` ↔ `wrapForPublicationMode`
dans `Events.bindOutbound`).

**Procédure** : l'autre agent termine et commit son refactor multi-cibles → je rebase le chantier 5
dessus → fusion manuelle de `Events.bindOutbound` (son `OutboundTarget` × mon `TimeIntervalProducer`).
