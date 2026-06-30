# garganttua-events

## Description

garganttua-events est un framework de traitement d'événements **multi-tenant, multi-cluster,
multi-connecteur** (`3.0.0-ALPHA04`, Java 25, package racine `com.garganttua.events`) bâti sur
**garganttua-core**. Un message brut (`byte[]`) entre par un connecteur, traverse une **route**
configurable composée de **stages** d'expressions, et ressort vers un ou plusieurs connecteurs de
sortie.

### Route → Workflow (la bascule d'architecture ALPHA04)

La chaîne de processeurs transactionnelle du legacy GGEvents est remplacée par le moteur
**garganttua-core Workflow → Script → Runtime → Expression**. Chaque `RouteDef` est **compilé en un
`IWorkflow`** par `RouteWorkflowCompiler.compile(...)` :

- Un `WorkflowsBuilder` est construit (`WorkflowsBuilder.builder().provide(injectionContextBuilder)
  .provide(scriptsBuilder).workflow("route-<uuid>")…inlineAll()`). Il **doit** recevoir le
  `IScriptsBuilder` du bootstrap (et non un simple `IExpressionContextBuilder`) pour porter toute la
  chaîne `Workflows → Scripts → {Expression, Runtimes}` — sans elle les stages compileraient mais ne
  s'exécuteraient pas.
- Des **variables de workflow** sont liées : `assetId`, `tenantId`, `clusterId`, `subscriptionId`,
  `version`, `dataflowUuid`, `connectorName` (entrée) et `outbounds` (liste des cibles), plus les
  variables de compatibilité legacy `producer`/`topicRef`/`outVersion`/`outDataflowUuid`/
  `outConnectorName`/`outSubscriptionId` (1ère cible).
- Chaque stage devient un **script inline** de la forme `exchange <- <expression>(...)`
  (`Events.addExpressionStage` → `stageBuilder.script("exchange <- " + expression).inline()`), avec
  `when(condition)`, `catch_(...)`, `catchDownstream(...)` optionnels.

### Auto-wrap des stages de transport

L'auteur de route ne déclare **que la logique métier** ; le moteur encadre automatiquement
(`RouteWorkflowCompiler.addStages`) :

```
[début]  protocol_in   (UNIQUEMENT si le dataflow entrant est encapsulated)
[début]  filter_in     (UNIQUEMENT si entrée encapsulated, depuis la consumer config)
         … stages métier déclarés (dans l'ordre) …
[fin]    produce(@exchange, @outbounds, @assetId, @clusterId)   (si ≥1 cible to)
            └─ par cible : filter_out(policy) → encapsulate (si encapsulated) → publish
```

- `protocol_in` est injecté **seulement** si le dataflow entrant porte le flag `encapsulated`.
- **`filter_in` est auto-injecté** (port du legacy `GGInFilterProcessor`) juste après `protocol_in`,
  depuis la consumer config de la subscription d'entrée — donc **uniquement** sur les flux entrants
  `encapsulated` (un flux non encapsulé n'a pas de version à filtrer). Il applique la `version` du
  dataflow puis les `destinationPolicy` / `originPolicy` de la consumer config.
- La **sérialisation d'enveloppe de sortie n'est plus un stage `protocol_out` distinct** : elle est
  faite **par cible** à l'intérieur du `produce` multi-cible (`publishToTarget` → `encapsulate`),
  uniquement pour les cibles dont le dataflow est `encapsulated`. La fonction `@Expression
  protocol_out` reste disponible pour un usage manuel.
- **`filter_out` est auto-injecté par cible sortante** (port du legacy `GGOutFilterProcessor`) :
  `produce` appelle `filterOut(exchange, target.destinationPolicy())` dans `publishToTarget` **avant**
  l'encapsulation/publication de chaque cible. La `destinationPolicy` est portée par chaque
  `OutboundTarget` (résolue depuis la producer config de la subscription de sortie par
  `RouteWorkflowCompiler.resolveOutboundTarget`) — `TO_ANY` efface `toUuid` (broadcast), `ONLY_TO_*`
  conserve l'adressage. Correct pour le fan-out multi-`.to()` (chaque cible porte sa propre policy).

### Processeurs implémentés (legacy GGEvents → ALPHA04)

La chaîne de processeurs transactionnelle du legacy `GGEvents` est **entièrement portée** sur le
moteur core Workflow/Script. Chaque ancien processeur a une réalisation ALPHA04 (souvent une
`@Expression`), câblée soit en **auto-wrap** (injectée par `RouteWorkflowCompiler`), soit en **stage
manuel** déclaré via le DSL `processor(...)`. Légende : ✅ porté · ➕ nouveau en ALPHA04.

| Processeur legacy | Réalisation ALPHA04 | Câblage | Statut |
|---|---|---|---|
| `GGProtocolInProcessor` | `@Expression protocol_in` | **auto-wrap** en début de route si le dataflow entrant est `encapsulated` (`addStages`) | ✅ |
| `GGProtocolOutProcessor` | `@Expression protocol_out` / helper `encapsulate` | **par cible sortante** intégré au `produce` multi-cible (`publishToTarget`) ; `protocol_out` reste disponible en **stage manuel** | ✅ |
| `GGInFilterProcessor` | `@Expression filter_in` | **auto-injecté** depuis la consumer config, après `protocol_in`, sur flux entrant `encapsulated` (`addStages`, `0dc70dd`) | ✅ |
| `GGOutFilterProcessor` | `@Expression filter_out` | **auto-injecté par cible sortante** : `produce` → `publishToTarget` appelle `filterOut(exchange, target.destinationPolicy())` avant encapsulation (`e5b08cd`) | ✅ |
| `GGOnChangeProducer` | stage `produce(@exchange, @outbounds, @assetId, @clusterId)` | **auto-wrap** en fin de route si ≥1 cible `to` ; publication **immédiate** (`PublicationMode.ON_CHANGE`) | ✅ |
| `GGTimeIntervalProducer` | décorateur `TimeIntervalProducer` | `PublicationMode.TIME_INTERVAL` : `wrapForPublicationMode` décore le producteur (last-wins / `buffered` mémoire / `bufferPersisted` fichier) ; flush cadencé par `timeInterval` | ✅ |
| `GGOnChangeConsumer` | thread consumer + `RouteDispatcher` | un **thread daemon par route** (`consumer-<uuid>`) ; concurrence par subscription (`concurrency`) ; `garanteeOrder ⇒ séquentiel` | ✅ |
| `GGLockObject` (synchronisation) | **mutex garganttua-core** (`IMutexManager` / `IMutex`) | `RouteSyncDef.synchronization(lock, lockObject)` → `MutexName` ; le workflow par message tourne dans `IMutex.acquire(...)` (`RouteMessageProcessor`) | ✅ |
| chaîne d'exception legacy (dead-letter) | routage d'erreur **niveau engine** | si le `WorkflowResult` échoue ou lève, l'exchange est republié sur la subscription d'erreur `RouteDef.exceptions.to` (`RouteMessageProcessor.routeToError`) | ✅ |
| `GGCoreFilterException` | `FilterException extends HandlingException` | un message filtré (`filter_in`/`filter_out`) interrompt la route en **drop propre**, sans erreur | ✅ |
| `GGTimeIntervalConsumer` | — | commenté **dès le legacy** (jamais actif) — rien à porter | — |

**Stages utilitaires `@Expression`** disponibles pour un **déclaratif manuel** via `processor(...)`
(non auto-injectés) : `set_header`, `get_header`, `json_path`, `log`, `protocol_out`, `route_to_error`,
`not_null` (typiquement en `when(...)`), plus la surcharge `produce(@exchange, <producer>)` mono-cible.
Voir le [Catalogue des fonctions `@Expression`](#catalogue-des-fonctions-expression).

> Concurrence par subscription, firehose d'observabilité (`GlobalObservers`) et auto-détection
> `@Connector` sont des ➕ **nouveautés ALPHA04** (absentes du legacy). Détail processeur par
> processeur dans [`MIGRATION.md`](MIGRATION.md) §1 / §3.

### Engine : Asset → Tenant → Cluster

`Events` (`extends AbstractLifecycle implements IEvents, IBootstrapSummaryContributor`) tient une
carte `runtimes : Map<tenantId, Map<clusterId, ClusterRuntime>>`. Pour chaque `ContextDef` :

- un **`ClusterRuntime`** est créé : registres in-place de `connectors`, `topics`, `dataflows`,
  `subscriptions`, `routeWorkflows`, `consumers`, `producers` (la synchronisation passe par le mutex
  de core — il n'y a plus de registre `locks` interne).
- `initContext` enregistre topics + dataflows, configure les connecteurs (`initConnector`),
  enregistre les subscriptions, puis compile chaque route en workflow.

Au **start** (`doStart`) : démarrage des connecteurs (`onInit`/`onStart`), démarrage des consumers
(un **thread daemon par route**, `consumer-<uuid>`), puis lancement des schedules `TIME_INTERVAL`.

Au **stop** (`doStop`) : arrêt consumers/producers/connectors, interruption des threads,
`RouteDispatcher.close()`, `TimeIntervalProducer.stop()`, shutdown des executors.

### Cheminement d'un message routé

`RouteMessageProcessor.runConsumer(...)` branche le `IConsumer` au workflow compilé :

1. À réception, un `Exchange` est créé (`Exchange.create(connector, topic, dataflowUuid, rawBytes)`).
2. Le travail est **dispatché** via un `RouteDispatcher` par route :
   - `concurrency = fromSub.consumerConfiguration().concurrency()` (défaut 1) ;
   - si `dataflow.garanteeOrder()` ⇒ **exécution séquentielle inline** (l'ordre prime, la
     parallélisation est désactivée avec un warning) ;
   - sinon, si `concurrency > 1` ⇒ pool borné `route-worker-<uuid>` (parallèle).
3. `processMessage` exécute le workflow **sous le mutex de synchro garganttua-core** (si configuré —
   `RouteSyncDef` résolu en `MutexName`, exécution dans `IMutex.acquire(...)` ; voir
   [Synchronisation](#synchronisation-via-le-mutex-garganttua-core)), via `RouteObserver.execute(...)`
   qui émet les events `events:route:<uuid>` Start/End/Error.
4. **Dead-letter** : si le `WorkflowResult` n'est pas succès, ou si une `RuntimeException` est levée,
   l'exchange est republié sur la **subscription d'erreur** (`RouteDef.exceptions.to`).

> **Pas de mode « parallel-keyed ».** Au moment du dispatch l'`Exchange` est encore brut (son
> `tenantId`/`correlationId` ne sont peuplés qu'au stage `protocol_in`) et le SPI consumer `byte[]`
> n'expose aucune clé de partition. `garanteeOrder = true` force donc le séquentiel
> (`RouteDispatcher` documente ce choix).

### Modules

| Module Maven (`artifactId`) | Rôle | Dépend de |
|---|---|---|
| `garganttua-events-api` | Contrats : interfaces, records de config, enums, exceptions, annotations, DSL (`I*Builder`) | core |
| `garganttua-events-expressions` | Fonctions `@Expression` (transport, filtre, header, json, log, produce…) | api |
| `garganttua-events-core` | Moteur (`Events`), compilateur route→workflow, DSL impl, lecteur JSON, SPI factory | api |
| `garganttua-events-connector-kafka` | Connecteur Apache Kafka | api |
| `garganttua-events-connector-bus` | Connecteur file persistante in-process (BigQueue) | api |
| `garganttua-events-connector-mail` | Connecteur e-mail SMTP (producteur seul) | api |
| `garganttua-events-connector-websocket` | Connecteur WebSocket bidirectionnel (serveur/client) | api |
| `garganttua-events-connector-observability` | Connecteur firehose d'observabilité (read-only) | api, core observability |
| `garganttua-events-connector-api` | Connecteur d'événements métier garganttua-api (read-only) | api, api business events |
| `garganttua-events-starters` | Parent agrégeant les starters Spring-Boot-like | — |

Graphe de dépendances : `api ← {expressions, core, connector-*}`.

## Installation

> Le bloc de dépendances ci-dessous est **généré** par `scripts/run_all.py` (idempotent, câblé au
> build à `generate-resources`). Ne pas éditer à la main entre les marqueurs `AUTO-GENERATED`.

<!-- AUTO-GENERATED-START -->
<!-- AUTO-GENERATED-END -->

En pratique on ne dépend pas du parent agrégateur : on choisit un **starter** « batteries-included »
(voir la table dans [Core Concepts › Starters](#starters)) qui agrège le runtime garganttua,
`events-core`, `events-expressions` et le(s) connecteur(s) voulu(s). garganttua-events s'auto-charge
alors au cold start du bootstrap via `EventsBuilderFactory` (SPI `IBootstrapBuilderFactory`).

## Core Concepts

### Modèle de configuration (records/enums)

Tous les `*Def` sont des **records immuables** (`com.garganttua.events.api.context`).

#### Topologie

| Record | Champs | Sens |
|---|---|---|
| `ContextDef` | `String tenantId`, `String clusterId`, `List<TopicDef> topics`, `List<DataflowDef> dataflows`, `List<ConnectorDef> connectors`, `List<SubscriptionDef> subscriptions`, `List<RouteDef> routes`, `List<LockDef> locks` | Topologie d'un (tenant, cluster) |
| `TopicDef` | `String ref` | Référence logique d'un topic |
| `DataflowDef` | `String uuid`, `String name`, `String type`, `boolean garanteeOrder`, `String version`, `boolean encapsulated` | Flux : `garanteeOrder` (ordre garanti ⇒ séquentiel), `encapsulated` (enveloppe `Message`), `version` (vérifiée par `filter_in`) |
| `ConnectorDef` | `String name`, `String type`, `String version`, `Map<String,String> configuration` | Déclaration d'un connecteur (résolu en bean `connector:type:version`) |
| `LockDef` | `String name`, `String type`, `String version`, `Map<String,String> configuration` | Déclaration d'un verrou (parsée/construite dans `ContextDef.locks`). La synchronisation effective passe désormais par le **mutex garganttua-core** — voir [Synchronisation](#synchronisation-via-le-mutex-garganttua-core) |

#### Subscription & configurations

| Record | Champs |
|---|---|
| `SubscriptionDef` | `String id`, `String dataflow`, `String topic`, `String connector`, `PublicationMode publicationMode`, `ConsumerConfigurationDef consumerConfiguration`, `ProducerConfigurationDef producerConfiguration`, `TimeIntervalDef timeInterval`, `boolean buffered`, `boolean bufferPersisted` |
| `ConsumerConfigurationDef` | `ProcessMode processMode`, `OriginPolicy originPolicy`, `DestinationPolicy destinationPolicy`, `HighAvailabilityMode highAvailabilityMode`, `int concurrency` |
| `ProducerConfigurationDef` | `DestinationPolicy destinationPolicy`, `String destinationUuid` |
| `TimeIntervalDef` | `int interval`, `String unit` (nom de `TimeUnit`, ex. `SECONDS`) |

> `SubscriptionDef` expose un **constructeur de compatibilité à 8 arguments** (sans
> `buffered`/`bufferPersisted`, mis à `false`) en plus du canonique à 10 arguments.

#### Route

| Record | Champs | Notes |
|---|---|---|
| `RouteDef` | `String uuid`, `String from`, `List<String> to`, `List<RouteStageDef> stages`, `RouteExceptionsDef exceptions`, `RouteSyncDef synchronization` | `to` est une **liste** (fan-out multi-cible) ; le constructeur canonique normalise `null → []` ; champ déserialisé via `@JsonDeserialize(using = StringOrListDeserializer.class)` tolérant `"to":"sub"` **et** `"to":["a","b"]` |
| `RouteStageDef` | `String name`, `String expression`, `String condition`, `String catchExpression`, `String catchDownstreamExpression` | `expression` = l'appel `@Expression` du stage (alimenté par le DSL `processor(...)`) |
| `RouteExceptionsDef` | `String to`, `String cast`, `String label` | `to` = subscription dead-letter d'erreur |
| `RouteSyncDef` | `String lock`, `String lockObject` | nom du verrou + objet logique verrouillé |

#### Enveloppe & message (top-level api)

| Type | Composants |
|---|---|
| `Exchange` *(record)* | `exchangeId`, `correlationId`, `messageId`, `tenantId`, `byte[] value`, `Map<String,String> headers`, `contentType`, `List<JourneyStep> steps`, `toUuid`, `dataflowVersion`, `fromConnector`, `fromTopic`, `fromDataflowUuid`, `toConnector`, `toTopic`, `toDataflowUuid`, `Map<String,Object> properties` (`@JsonIgnore`) |
| `Message` *(record)* | `headers`, `correlationId`, `messageId`, `steps`, `tenantId`, `byte[] value`, `contentType`, `toUuid`, `dataflowVersion` |
| `JourneyStep` *(record)* | `Date date`, `assetId`, `subscriptionId`, `Direction direction`, `dataflowVersion`, `uuid`, `clusterId` |
| `ConnectorContext` *(record)* | `assetId`, `tenantId`, `clusterId` |

`Exchange` : fabrique `create(fromConnector, fromTopic, fromDataflowUuid, value)` ; copieurs
immuables `withValue`, `withHeader`, `withHeaders`, `withCorrelationId`, `withMessageId`,
`withTenantId`, `withContentType`, `withSteps`, `withStep`, `withToUuid`, `withDataflowVersion`,
`withTo(connector, topic, dataflowUuid)`, `withProperty`.
`Message` : `create(tenantId, MediaType, version, bytes)` et `fromExchange(Exchange)`.

#### Enums (`com.garganttua.events.api.enums`)

| Enum | Constantes |
|---|---|
| `PublicationMode` | `ON_CHANGE`, `TIME_INTERVAL` |
| `Direction` | `IN`, `OUT` |
| `DestinationPolicy` | `ONLY_TO_CLUSTER`, `ONLY_TO_ASSET`, `TO_ANY` |
| `OriginPolicy` | `FROM_ANY`, `ONLY_FROM_ASSET_ID`, `ONLY_FROM_CLUSTER_ID`, `ONLY_FROM_ASSET_CLUSTER`, `ONLY_FROM_OTHER_CLUSTER` |
| `ProcessMode` | `EVERYBODY`, `ONLY_ONE_CLUSTER_NODE` |
| `HighAvailabilityMode` | `LOAD_BALANCED`, `MASTER_SLAVE` |
| `MediaType` | `APPLICATION_JSON` (`"application/json"`), `TEXT_PLAIN` (`"text/plain"`) — `toString()` renvoie la valeur MIME |

#### Exceptions

`EventsException extends Exception` est la racine. Descendantes : `ConnectorException`,
`ProcessingException`, `HandlingException`, et `FilterException extends HandlingException`
(« message filtré » — interrompt la route en *drop* propre, équivalent du legacy
`GGCoreFilterException`).

### DSL (EventsBuilder → context → route → stage)

DSL fluide à navigation `up()` (interfaces dans `events/api/.../dsl`, impls dans `events/core/.../dsl`).
`EventsBuilder` est annoté `@ConfigurableBuilder("events")` (configurable par fichier — voir
[SPIs & points d'extension](#spis--points-dextension)).

#### `IEventsBuilder` (racine)

```java
IEventsBuilder asset(String assetId);
IEventsBuilder withPackage(String pkg);              // propage le package au scan @Expression partagé
IContextBuilder context(String tenantId, String clusterId);
IEventsBuilder source(String type, String configuration);   // file | resource/classpath | json/string/inline
IEventsBuilder connector(String url);                                            // par bean-référence
IEventsBuilder connector(IClass<? extends IConnector> connectorClass);           // par classe @Connector
IEventsBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorBuilder);
IEventsBuilder connector(IConnector connector);                                  // par instance
```

#### `IContextBuilder`

```java
IContextBuilder topic(String ref);
IContextBuilder dataflow(String uuid, String name, String type,
        boolean garanteeOrder, String version, boolean encapsulated);
IConnectorConfigBuilder connector(String name, String type, String version);   // → .config(k,v).up()
ISubscriptionBuilder subscription(String id, String dataflow, String topic,
        String connector, PublicationMode publicationMode);
IRouteBuilder route(String uuid, String from);
IContextBuilder lock(String name, String type, String version);
```

#### `IConnectorConfigBuilder`, `ISubscriptionBuilder`, `IConsumerConfigBuilder`, `IProducerConfigBuilder`

```java
// IConnectorConfigBuilder
IConnectorConfigBuilder config(String key, String value);

// ISubscriptionBuilder
IConsumerConfigBuilder consumer();
IProducerConfigBuilder producer();

// IConsumerConfigBuilder
IConsumerConfigBuilder processMode(ProcessMode mode);
IConsumerConfigBuilder originPolicy(OriginPolicy policy);
IConsumerConfigBuilder destinationPolicy(DestinationPolicy policy);
IConsumerConfigBuilder highAvailabilityMode(HighAvailabilityMode mode);
IConsumerConfigBuilder concurrency(int concurrency);

// IProducerConfigBuilder
IProducerConfigBuilder destinationPolicy(DestinationPolicy policy);
IProducerConfigBuilder destinationUuid(String uuid);
```

#### `IRouteBuilder` & `IRouteStageBuilder`

```java
// IRouteBuilder — to(...) est répétable (fan-out multi-cible : .to(a).to(b))
IRouteBuilder to(String subscriptionRef);
IRouteStageBuilder stage(String name);
IRouteBuilder exceptions(String toSubscription, String cast, String label);   // dead-letter d'erreur
IRouteBuilder synchronization(String lock, String lockObject);

// IRouteStageBuilder
IRouteStageBuilder processor(String processor);     // l'appel @Expression du stage
IRouteStageBuilder when(String condition);          // garde conditionnelle
IRouteStageBuilder catch_(String catchExpr);        // gestionnaire d'exception local
IRouteStageBuilder catchDownstream(String catchExpr);
```

### Catalogue des fonctions `@Expression`

Définies dans `EventExpressions` (`events/expressions`), auto-découvertes par scan `@Expression` du
contexte d'expression partagé. **12 méthodes `@Expression`** (dont 2 surcharges `produce`).
Toutes prennent l'`Exchange` en 1er argument et le retournent (sauf `get_header`, `json_path`,
`not_null`). Dans les stages, l'`Exchange` courant est référencé `@exchange`.

| Nom | Signature Java | Rôle |
|---|---|---|
| `protocol_in` | `Exchange protocolIn(Exchange, String assetId, String clusterId, String subscriptionId, String version)` | Désérialise l'enveloppe `Message` entrante, hérite/initialise l'uuid de parcours, ajoute un `JourneyStep` **IN**, recopie correlationId/version/steps/tenant/value/headers/contentType/toUuid |
| `protocol_out` | `Exchange protocolOut(Exchange, String assetId, String clusterId, String topicRef, String version, String dataflowUuid, String connectorName, String subscriptionId)` | Stampe l'adressage de sortie + un `JourneyStep` **OUT**, sérialise l'exchange en enveloppe `Message` (délègue à `encapsulate`) — usage **manuel** (le `produce` multi-cible encapsule lui-même) |
| `filter_in` | `Exchange filterIn(Exchange, String destinationPolicy, String originPolicy, String assetId, String clusterId, String version)` | Filtre entrant : **version** du dataflow (`FilterException` si ≠), puis `DestinationPolicy` (`ONLY_TO_ASSET`→`toUuid==assetId`, `ONLY_TO_CLUSTER`→`toUuid==clusterId`, `TO_ANY`) |
| `filter_out` | `Exchange filterOut(Exchange, String destinationPolicy)` | Filtre sortant : `TO_ANY` ⇒ efface `toUuid` ; `ONLY_TO_*` ⇒ conserve l'adressage |
| `set_header` | `Exchange setHeader(Exchange, String key, String value)` | Pose un header sur l'exchange |
| `get_header` | `String getHeader(Exchange, String key)` | Lit la valeur d'un header (`null` si absent) |
| `json_path` | `Object jsonPath(Exchange, String path)` | Extrait une valeur du payload via **JSONPath** (`com.jayway.jsonpath`) |
| `log` | `Exchange log(Exchange, String level)` | Journalise `[Exchange][CorrelationId] value size=` au niveau `DEBUG`/`WARN`/`ERROR`/(défaut)`INFO` |
| `produce` | `Exchange produce(Exchange, IProducer producer)` | Publie le `value` de l'exchange sur **un** producteur (surcharge simple) |
| `produce` | `Exchange produce(Exchange, List<OutboundTarget> outbounds, String assetId, String clusterId)` | **Broadcast** vers **chaque** cible ; encapsule à la volée (`Message`) les cibles `encapsulated` ; une cible en échec est loguée sans interrompre les autres (auto-injecté par l'auto-wrap) |
| `route_to_error` | `Exchange routeToError(Exchange, IProducer producer)` | Republie l'exchange vers la subscription d'erreur |
| `not_null` | `boolean notNull(Object value)` | Vrai si la valeur n'est pas `null` (typiquement en `when(...)`) |

Helper privé partagé : `encapsulate(...)` (stampe adressage + `JourneyStep` OUT, sérialise en
`Message`) — mutualisé entre `protocol_out` et le `produce` multi-cible.

`OutboundTarget` *(record api)* : `IProducer producer`, `boolean encapsulated`, `String topicRef`,
`String version`, `String dataflowUuid`, `String connectorName`, `String subscriptionId`.

### Connecteurs

Tous implémentent **`IConnector extends ILifecycle`** :
`getName()`, `configure(Map<String,String>, ConnectorContext)`, `createConsumer(SubscriptionDef,
DataflowDef)`, `createProducer(SubscriptionDef, DataflowDef)`. `IConsumer` :
`start(Consumer<byte[]>)`, `stop()`. `IProducer` : `publish(byte[])`, `stop()`. Chaque classe porte
`@Connector(type=…)` (**`@Indexed` + `@Reflected` + `@Qualifier`**, défaut `version="1.0"`,
`name=""`). Toutes lisent une clé `name` (injectée par le moteur depuis `ConnectorDef.name`).

#### `bus` — file persistante BigQueue (in-process)

- **Type/version** : `bus` / `1.0`. Aller-retour producteur↔consommateur, utile pour les tests.
- **Clés** : `name` (déf. `"bus"`), `homeDirectory` (déf. `java.io.tmpdir`), `pollInterval`
  (déf. `10`), `pollIntervalUnit` (déf. `SECONDS`).
- **Stockage** : `homeDirectory/<assetId>/<tenantId>/<clusterId>/<topic-normalisé>` (BigQueue Apache).
- **Consumer** (`BusConsumer`) : boucle de polling qui `dequeue()` des `BusMessage`
  (record `dataflowUuid:value`) et passe les `value` bytes au handler.
- **Producer** (`BusProducer`) : `enqueue()` d'un `BusMessage` sérialisé.

#### `kafka` — Apache Kafka

- **Type/version** : `kafka` / `1.0`.
- **Clés** : `name` (déf. `"kafka"`), `url` (**requis**, bootstrap servers), `maxPollRecords`
  (déf. `1`), `enableAutoCommit` (déf. `"false"`), `autoOffsetReset` (déf. `"latest"`),
  `allowAutoCreateTopics` (déf. `false`), `pollInterval` (déf. `10`), `pollIntervalUnit`
  (déf. `SECONDS`).
- **Consumer** (`KafkaConsumer_`) : `poll(Duration)` bloquant ; clé `String`
  (`StringDeserializer`), valeur `byte[]` (`ByteArrayDeserializer`) ; `commitSync()` si
  `!autoCommit` ; assignor **RoundRobin** ; `group.id` dérivé du tenant/dataflow/topic/cluster
  (et de l'asset en mode `EVERYBODY`) ; `wakeup()` à l'arrêt.
- **Producer** (`KafkaProducer_`) : `send(record)` asynchrone, clé = `dataflowUuid`, valeur = bytes.

#### `mail` — e-mail SMTP (**producteur seul**)

- **Type/version** : `mail` / `1.0`. `createConsumer()` ⇒ `UnsupportedOperationException`.
- **Clés** : `name` (déf. `"mail"`), `from`, `to`, `object` (sujet par défaut — *attention : clé
  `object`*), `body`, `username`, `password`, `contentType`, `auth` (`mail.smtp.auth`),
  `starttls` (`mail.smtp.starttls.enable`), `ssl` (`mail.smtp.ssl.enable`), `host` (déf.
  `"localhost"`), `port` (déf. `"587"`).
- **Producer** (`MailProducer` → `MailSender`) : le payload est parsé en **`MailEnvelope` JSON**
  (`MailEnvelope.tryParse`, lenient) ; si le payload n'est pas un objet JSON il est traité comme
  corps texte brut. Résolution par enveloppe puis fallback config : `to`, `from` (ou `username`),
  `subject` (ou `"Event notification"`), `contentType` (ou `"text/plain"`), `body`, `attachments`.
- **`MailEnvelope`** : `to`, `from`, `subject`, `contentType`, `body`, `List<MailAttachment>
  attachments`. **`MailAttachment`** : `filename`, `contentType` (déf. `application/octet-stream`),
  `content` (**Base64**). `MailSender` construit un **`MimeMultipart`** : corps en 1ère partie, puis
  un `MimeBodyPart` par pièce jointe (`DataHandler` sur les bytes décodés, disposition `ATTACHMENT`).
  Sans pièce jointe : `setContent(body, contentType)`. Pièce malformée ⇒ ignorée + warning.

#### `websocket` — WebSocket bidirectionnel

- **Type/version** : `websocket` / `1.0`. **Bidirectionnel** (consumer + producer).
- **Clés** : `name` (déf. `"websocket"`), `mode` (`server` déf. | `client`), `ws.host`
  (déf. `"0.0.0.0"`), `ws.port` (**requis** en serveur), `ws.url` (**requis** en client),
  `frameFormat` (`text` déf. | `binary`), `connectTimeoutMs` (déf. `5000`).
- **Mode serveur** : `EventsWebSocketServer` embarqué + `WebSocketRegistry` partagé (sessions et
  handlers par *path*). Consumer = handler enregistré pour son path ; Producer = **broadcast**
  (pub/sub) vers toutes les sessions vivantes du path.
- **Mode client** : un `WebSocketClientEndpoint` partagé par topic (consumer + producer sur la même
  socket). Consumer = `client.onFrame(handler)` ; Producer = `client.send(...)`.
- Trames text → `String` UTF-8 ; binaires → bytes ; livrées en `byte[]` en aval.

#### `observability` — firehose d'observabilité (**read-only**)

- **Type/version** : `observability` / `1.0`. Producteur = `ReadOnlyProducer` (`publish` lève
  `ConnectorException` « read-only »).
- **Clés** : `name` (déf. `"observability"`), `events` (CSV parmi `start,end,error,log`, défaut =
  tous), `sourcePattern` (glob `*`/`?` matché sur `ObservableEvent.source()`, défaut = tout).
- **Consumer** (`ObservabilityConsumer`) : s'auto-enregistre comme `IObserver` sur **`GlobalObservers`**
  (le firehose process-global de garganttua-core) → **chaque** event de la plateforme y arrive.
  Filtrage via `EventFilter.of(events, sourcePattern)` (kinds `START/END/ERROR/LOG` + glob).
  Sérialisation `ObservableEventCodec` (JSON : `type`, `source`, `executionId`, `timestamp`, +
  `code`/`durationMs` (End), `error`/`durationMs` (Error), `level`/`message` (Log), `payload`).
  File bornée (10 000) + thread de drain (200 ms) ; le thread émetteur n'est jamais bloqué (warning
  throttlé à l'overflow).

#### `api` — événements métier garganttua-api (**read-only**)

- **Type/version** : `api` / `1.0`. Producteur = `ReadOnlyProducer` (« read-only »).
- **Clés** : `name` (déf. `"api-events"`), `domain` (filtre sur le segment domaine), `operations`
  (filtre sur la clé d'opération).
- **Consumer** (`ApiEventsConsumer`) : s'enregistre sur `GlobalObservers` ; ne retient que les
  events terminaux (`EndEvent`/`ErrorEvent`) de source préfixée **`api:operation:`**
  (`api:operation:<domain>:<operationKey>`), dont le `payload` est un `IEvent`. Même mécanique
  file-bornée + drain que l'observability connector.
- **`ApiEventCodec`** (JSON profond par `IEvent`) : `operation`, `domain`, `businessOperation`,
  `useCase`, `code`, `exceptionCode`, `exceptionMessage`, `tenantId`, `ownerId`, `userId`,
  `inDate`, `outDate`, et **`in` / `out`** sérialisés en `JsonNode` profond (`valueToTree`, fallback
  `toString`).
- **`ApiEventFilter`** (`IFilter`, posé via `.filter(IFilter)` avec snapshot défensif) — arbre de
  filtres :
  - **Combinateurs** : `$and` (tous), `$or` (au moins un), `$nor` (aucun).
  - **Sélecteur de champ** : `$field` (porte le nom de champ + exactement un opérateur).
  - **Opérateurs feuille** : `$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte` (numérique si les 2 côtés
    parsent en `double`, sinon lexicographique), `$regex`, `$empty` (null/blank), `$in`, `$nin`
    (appartenance à un ensemble de littéraux).
  - **Champs résolus** : `operation`/`businessOperation`, `technicalOperation`, `domain`/`domainName`,
    `code`, `tenantId`, `ownerId`, `userId`, et `in.<prop>` / `out.<prop>` (getter réflexif
    best-effort sur les payloads `getIn()`/`getOut()`).
  - **Robustesse** : ne lève jamais ; filtre `null` ⇒ tout passe ; forme malformée ⇒ `false`.

### Modes de publication (ON_CHANGE / TIME_INTERVAL / buffered / persisted)

Pilotés par `SubscriptionDef.publicationMode` (sortie). `RouteWorkflowCompiler.wrapForPublicationMode`
décore le `IProducer` de cible selon le mode.

- **`ON_CHANGE`** (défaut) : publication **immédiate** à chaque message (le producteur connecteur
  est utilisé tel quel).
- **`TIME_INTERVAL`** : publication cadencée par `timeInterval` (`{interval, unit}`), via le
  décorateur **`TimeIntervalProducer`** (`scheduleAtFixedRate(flush, interval, interval, unit)` sur
  l'executor `events-publication-scheduler`). `publish(...)` n'émet pas, il **bufferise** ; le tick
  flush vers le producteur réel. Trois modes selon `buffered`/`bufferPersisted` :
  - `buffered = false` → **last-wins** : seul le **dernier** payload de l'intervalle est émis
    (sémantique legacy `GGTimeIntervalProducer`).
  - `buffered = true`, `bufferPersisted = false` → **batch mémoire** : tous les payloads de
    l'intervalle sont émis au tick.
  - `buffered = true`, `bufferPersisted = true` → **batch persisté fichier** : buffer file-backed
    (`TimeIntervalProducer.PersistentBuffer`, frames préfixées par longueur sous
    `<tmpdir>/garganttua-events-buffer/<assetId>/<routeUuid>/<subId>.buf`), **rejoué au redémarrage**.

### SPIs & points d'extension

#### `@Connector` — auto-détection (`@Indexed`)

`@com.garganttua.events.api.connectors.annotations.Connector(type, version="1.0", name="")` est
`@Indexed`/`@Reflected`/`@Qualifier`. `EventsBuilder.doAutoDetection()` scanne globalement les classes
annotées (`IReflection.getClassesWithAnnotation`) et les enregistre sous `type:version`
(« batteries-included » : un JAR connecteur sur le classpath suffit). Chaque connecteur est
enregistré comme **bean qualifié** `connector:type:version` dans le contexte d'injection (prototype
par classe pour les auto-détectés/`connector(IClass)`, singleton instance pour `connector(IConnector)`
et les suppliers, prototype par classe honorant provider/strategy/name pour `connector(url)`).

#### `IConnector` / `IConsumer` / `IProducer` (SPI connecteur)

Contrat d'un connecteur tiers (voir [Connecteurs](#connecteurs)). Le moteur résout d'abord le bean
`connector:type:version`, puis retombe sur l'instanciation réflexive du registre
(`Events.resolveConnector`) — fonctionne avec ou sans injection.

#### `IEventsTopologyContributor` (META-INF/services)

`@FunctionalInterface` : `void contribute(IEventsBuilder events)` + `default int order()`. Listé dans
`META-INF/services/com.garganttua.events.api.dsl.IEventsTopologyContributor`,
`EventsBuilderFactory.create()` charge tous les contributeurs (`ServiceLoader`), les trie par `order()`
croissant et les applique **au builder partagé du bootstrap**, isolés par contributeur (un échec est
logué et sauté). Une application contribue ainsi une topologie configurée **sans lancer un second
Bootstrap**.

#### `EventsBuilderFactory` (`IBootstrapBuilderFactory`, auto-load)

Listé dans `META-INF/services/com.garganttua.core.dsl.IBootstrapBuilderFactory`. Sur le classpath,
garganttua-events se câble au cold start du bootstrap : ses dépendances builder
(`IInjectionContextBuilder`, `IExpressionContextBuilder`, `IScriptsBuilder`) sont résolues
automatiquement depuis le réacteur. Il configure aussi le contexte d'expression partagé (parité api :
`autoDetect(true)`, packages `com.garganttua.core.expression.functions` /
`...script.functions` / `...observability`, + `withPackage(...)` applicatifs) pour que les stages de
route résolvent built-ins **et** `@Expression` applicatives.

#### `@ConfigurableBuilder("events")` + `source()`

`EventsBuilder` est `@ConfigurableBuilder("events")` : un fichier de configuration ciblant l'alias
`events` peuple le builder directement (intégration garganttua-configuration). En complément,
**`source(type, configuration)`** charge une topologie externe : `JsonContextReader.readFromFile`
(`file`), `readFromResource` (`resource`/`classpath`, via thread-context/classe/système ClassLoader),
ou `readFromString` (`json`/`string`/`inline`). Le JSON couvre topics, dataflows, connectors,
subscriptions (avec `timeInterval`/`buffered`/`bufferPersisted`/consumer/producer configs), routes
(`to` string **ou** array via `parseTo`, stages avec `catch`/`catchDownstream`, exceptions, synchro)
et locks.

#### Synchronisation via le mutex garganttua-core

La synchronisation de route s'appuie **entièrement sur le mutex de garganttua-core**
(`com.garganttua.core.mutex` : `IMutexManager` / `IMutex`) — il n'y a **plus** d'abstraction de
verrou interne à events (l'ancien `IDistributedLock` et le registre `ClusterRuntime.locks` ont été
**supprimés** en `d8f7756`).

- `Events.doInit` construit l'`IMutexManager` via `MutexManagerBuilder` (auto-détection des
  `@MutexFactory` du classpath), avec repli sur un `MutexManager` local.
- `RouteMessageProcessor.resolveMutex` mappe `RouteSyncDef.synchronization(lock, lockObject)` en
  `MutexName` (`toMutexName`) : un `lock` simple utilise le **`InterruptibleLeaseMutex`** par défaut ;
  un `lock` qualifié **`Type::name`** sélectionne une factory `@MutexFactory` enregistrée ; un
  `lockObject` non vide **affine** le nom (`name:lockObject`) pour sérialiser des clés indépendantes
  sous un même verrou logique.
- Le workflow par message tourne dans `IMutex.acquire(...)` (le mutex bloque, exécute, relâche) ; un
  lock malformé/non résolu est **logué** et la route tourne sans synchro plutôt que d'échouer au
  démarrage.

Pour un **lock distribué** (Redis / DB / Zookeeper), il suffit de fournir une implémentation
`IMutex` + `@MutexFactory` côté garganttua-core et de référencer son type dans le `lock` qualifié de
la route — **aucune** modification d'events requise (le SPI mutex du core fait le branchement).

### API programmatique (IEvents)

`IEvents extends ILifecycle, IObservable` :

| Méthode | Rôle |
|---|---|
| `String getAssetId()` | Identifiant d'asset du moteur |
| `IReflection reflection()` | Façade de réflexion (`IClass.getReflection()`) |
| `void publish(String topic, byte[] payload)` | Publie un payload sur la 1ère subscription dont le topic correspond (`EventsPublisher.resolveByTopic`) |
| `IProducer producer(String subscriptionId)` | Producteur mémoïsé pour une subscription (par id) — `EventsPublisher` réutilise les instances de connecteur **du moteur** |
| `String describeRoute(String routeUuid)` | Rendu texte d'une route (jointe topic/connector/dataflow, `<unresolved: ref>` si non résolu) |
| `String describeRoutes()` | Rendu de toutes les routes |
| `void addObserver(IObserver<ObservableEvent>)` / `removeObserver(...)` | Abonnement aux events `events:route:<uuid>` |

`EventsPublisher` (collaborateur de `Events`) partage la carte runtime vivante ; les producteurs
ad-hoc de `publish`/`producer` sont mémoïsés (`ConcurrentHashMap` par subscription id) et fermés au
stop. `Events` implémente aussi **`IBootstrapSummaryContributor`** (`getSummaryCategory() = "Events"`,
`getSummaryItems()` via `EventsSummary.items` : asset id, nombre de clusters, totaux routes /
connectors / topics / dataflows / subscriptions) — affiché dans le résumé de démarrage du bootstrap.

### Observabilité

Chaque exécution de route émet, via `RouteObserver.execute(...)`, des `ObservableEvent`
Start/End/Error de source **`events:route:<uuid>`** sur l'`IObservable` du moteur (s'abonner via
`IEvents.addObserver(...)`). En parallèle, le firehose process-global **`GlobalObservers`** de
garganttua-core reçoit **tous** les events de la plateforme ; les connecteurs `observability` et
`api` s'y branchent (read-only) pour les router comme messages.

### Intégration bootstrap & récupération de l'IEvents

- garganttua-events s'**auto-charge** au bootstrap via `EventsBuilderFactory` (SPI
  `IBootstrapBuilderFactory`). Le builder retourné est celui que le bootstrap construit et publie.
- Le `IEvents` construit est **enregistré comme bean `"events"`** (singleton, scope `garganttua`)
  dans le contexte d'injection (`EventsBuilder.registerEventsBean`), qui est ensuite injecté au moteur
  (`Events.setInjectionContext`) pour la résolution des connecteurs comme beans.
- Le moteur **tolère une configuration vide** : sans assetId ni contexte, il se construit *idle*
  (assetId `"default"`) et reste no-op jusqu'à ce qu'un fichier de config / le DSL / un contributeur
  le peuple (parité `ApiBuilder`).
- Récupération : via le registre du runner neutre de core (`GarganttuaApplication` / `IBuiltRegistry`,
  bean `"events"`).

### Starters

Chaque starter (`garganttua-events-starter-*`) agrège le runtime garganttua (`garganttua-starter-runtime`)
+ `events-core` + `events-expressions` + le(s) connecteur(s) correspondant(s), pour un démarrage
« batteries-included ».

| Starter | Connecteur(s) embarqué(s) |
|---|---|
| `garganttua-events-starter-bus` | `connector-bus` |
| `garganttua-events-starter-kafka` | `connector-kafka` |
| `garganttua-events-starter-mail` | `connector-mail` |
| `garganttua-events-starter-observability` | `connector-observability` |
| `garganttua-events-starter-api` | `connector-api` **+** `connector-observability` |
| `garganttua-events-starter-websocket` | `connector-websocket` |

(`starter-websocket` n'embarque pas explicitement `events-expressions` dans son POM ; les autres oui.)

## Usage

### Topologie via `IEventsTopologyContributor`

L'application déclare un contributeur (listé dans
`META-INF/services/com.garganttua.events.api.dsl.IEventsTopologyContributor`) ; le bootstrap l'applique
au builder partagé sans second Bootstrap.

```java
public class SensorTopology implements IEventsTopologyContributor {

    @Override
    public int order() { return 0; }

    @Override
    public void contribute(IEventsBuilder events) {
        events
            .asset("device-42")
            .context("tenantA", "cluster1")
                .topic("/in/sensors")
                .topic("/out/alerts")
                .dataflow("df-in",  "sensors", "json", false, "1.0", true)    // encapsulated
                .dataflow("df-out", "alerts",  "json", false, "1.0", false)
                .connector("bus-in",  "bus", "1.0").config("homeDirectory", "/tmp/q").up()
                .connector("bus-out", "bus", "1.0").config("homeDirectory", "/tmp/q").up()
                .subscription("sub-in",  "df-in",  "/in/sensors", "bus-in",  PublicationMode.ON_CHANGE)
                    .consumer().concurrency(4).up()
                .up()
                .subscription("sub-out", "df-out", "/out/alerts", "bus-out", PublicationMode.ON_CHANGE).up()
                .route("route-1", "sub-in")
                    .to("sub-out")
                    .stage("threshold")
                        .processor("set_header(@exchange, \"alert\", \"true\")")
                        .when("not_null(json_path(@exchange, \"$.value\"))")
                    .up()
                    .exceptions("sub-out", null, "dead-letter")
                .up()
            .up();
    }
}
```

### Boot api + events, récupération de l'`IEvents`

```java
IBuiltRegistry reg = GarganttuaApplication.run(MyApp.class, args);
IEvents events = GarganttuaApplication.get(reg, IEvents.class);   // bean "events"

// Publier sur la 1ère subscription dont le topic correspond
events.publish("/in/sensors", "{\"value\":42}".getBytes(StandardCharsets.UTF_8));

// Producteur mémoïsé pour une subscription
IProducer producer = events.producer("sub-out");

// Inspecter une route compilée
System.out.println(events.describeRoute("route-1"));
```

### Connecteur `api` — filtre sur les événements métier

```java
IFilter filter = Filter.in("operation", "create", "update");   // $in sur le champ operation

events
    .context("tenantA", "cluster1")
        .connector("api-bridge", "api", "1.0")
            .config("domain", "users")
        .up()
    // … la subscription posant ce connector applique .filter(filter) côté ApiEventsConsumer
    .up();
```

### Mail avec pièces jointes

Le payload de l'exchange est une enveloppe `MailEnvelope` JSON :

```json
{
  "to": "ops@example.com",
  "from": "noreply@example.com",
  "subject": "Sensor alert",
  "contentType": "text/html",
  "body": "<b>Threshold exceeded</b>",
  "attachments": [
    { "filename": "report.csv", "contentType": "text/csv", "content": "aWQsdmFsdWUKMSw0Mgo=" }
  ]
}
```

`MailSender` construit un `MimeMultipart` : le `body` en 1ère partie, puis un `MimeBodyPart` par
pièce jointe (`content` Base64 décodé, disposition `ATTACHMENT`). Sans `attachments`, un simple
`setContent(body, contentType)` est utilisé.

## Tips and best practices

- **`garganttua-events-expressions` doit être sur le classpath** pour que les fonctions de transport
  (`protocol_in`, `produce`, `filter_*`, `set_header`, `json_path`, `log`, …) soient résolues par le
  contexte d'expression partagé. Les starters l'embarquent (sauf `starter-websocket` — l'ajouter
  explicitement si on écrit des stages d'expression).
- **Shade & SPI** : tout JAR shadé produisant un exécutable **doit inclure
  `ServicesResourceTransformer`** pour fusionner les descripteurs `META-INF/services/*` — critique
  pour le cold start SPI (`IBootstrapBuilderFactory`, `IReflectionProvider`, `IEventsTopologyContributor`)
  et le native-image.
- **Fonctions `@Expression` applicatives** : exposez-les via `IExpressionFunctionContributor` (et/ou
  `withPackage(...)` sur le builder) — elles sont alors scannées par le même contexte d'expression
  que les stages de route. Ne pas réinventer un registre parallèle.
- **Réutiliser les primitives de core** (Workflow / Script / Runtime / Expression, observabilité,
  injection) plutôt que bâtir un mécanisme parallèle : une route **est** un `IWorkflow`, un stage
  **est** un script inline. C'est la règle « decalque le chemin existant » héritée d'api.
- **Filtres `filter_in`/`filter_out` auto-injectés** : comme le legacy, ils sont câblés
  automatiquement — `filter_in` depuis la consumer config (sur flux entrant `encapsulated`),
  `filter_out` **par cible sortante** depuis la producer config (`OutboundTarget.destinationPolicy`).
  Inutile de les déclarer en stage manuel pour le filtrage version/destination/origine standard ; les
  `@Expression filter_in`/`filter_out` restent disponibles pour un filtrage ad-hoc supplémentaire.
- **Synchronisation via le mutex de core** : `RouteSyncDef.synchronization(lock, lockObject)` exécute
  le workflow par message dans un `IMutex` de garganttua-core (`InterruptibleLeaseMutex` par défaut,
  `Type::name` pour une factory `@MutexFactory`). Pour un lock **distribué**, fournir une
  implémentation `IMutex` + `@MutexFactory` côté core — voir
  [Synchronisation](#synchronisation-via-le-mutex-garganttua-core).

### État & gaps

Synthèse de l'état réel du code courant. Pour la table legacy↔ALPHA04 processeur par processeur et le
détail des chantiers, voir [`MIGRATION.md`](MIGRATION.md).

> **MIGRATION.md ⇄ code : alignés.** La migration legacy → ALPHA04 est **fonctionnellement terminée**
> (`MIGRATION.md` §0). Tout le comportement réel du legacy est porté à HEAD : multi-`.to()` (fan-out,
> `OutboundTarget`), `TIME_INTERVAL` + buffered/bufferPersisted (`TimeIntervalProducer`),
> auto-injection `filter_in`/`filter_out`, et synchronisation via le **mutex garganttua-core** (l'ancien
> `IDistributedLock` supprimé). Reliquats = features jamais agies même en legacy (voir ❌ ci-dessous).

**✅ Porté / présent**

- Compilation route → core Workflow (`RouteWorkflowCompiler`), stages `exchange <- @Expression`.
- Auto-wrap `protocol_in` (si entrée `encapsulated`) + `produce` ; encapsulation de sortie par cible.
- **Auto-injection `filter_in`** depuis la consumer config (sur flux entrant `encapsulated`,
  `addStages`, `0dc70dd`) et **`filter_out` par cible sortante** depuis la producer config
  (`publishToTarget` → `filterOut`, `e5b08cd`).
- **Synchronisation de route** via le **mutex garganttua-core** (`IMutexManager`/`IMutex`,
  `RouteMessageProcessor`, `d8f7756`) — l'`IDistributedLock` interne et `ClusterRuntime.locks` ont
  été supprimés ; un lock distribué se branche par `@MutexFactory` côté core, sans code events.
- Publication `ON_CHANGE` (immédiate).
- **`TIME_INTERVAL` + buffered/bufferPersisted** (`TimeIntervalProducer` : last-wins / batch mémoire /
  batch persisté fichier), scheduler, parse JSON — **mergé** (MIGRATION.md le note « non commité »).
- **Multi-cible `to` (fan-out)** : `RouteDef.to: List<String>`, `OutboundTarget`,
  `StringOrListDeserializer`, `produce` multi-cible — **mergé** (MIGRATION.md le note « 🔄 autre agent »).
- DSL de stage **`processor()`** (renommé depuis `expression()`).
- Dead-letter niveau engine (`RouteDef.exceptions` → republication sur subscription d'erreur).
- Concurrence par subscription + `RouteDispatcher` (`garanteeOrder` ⇒ séquentiel).
- Sources de contexte externes `source()` (file / resource / json).
- Auto-détection connecteur `@Connector` ; firehose `GlobalObservers` (observability/api connectors).
- Consumer config étendue (`concurrency`) ; producer config (`ProducerConfigurationDef`).

**⚠️ Partiel**

- **`protocol_in`/`protocol_out`** conditionnés au flag `encapsulated` (le legacy les ajoutait
  toujours). `protocol_out` n'est plus un stage auto distinct (encapsulation intégrée au `produce`
  multi-cible).

**❌ Absent**

- **Tenant partitioning policy** (legacy `GGContextTenantPartitioningPolicy`) : absent.
- **Topic routing** (legacy `GGContextTopicRouting`) : absent.
- **Parallel-keyed** (concurrence + ordre simultanés) : impossible tant que le SPI consumer `byte[]`
  n'expose pas de clé de partition (`RouteDispatcher` force le séquentiel si `garanteeOrder`).

## License
This module is distributed under the MIT License.
