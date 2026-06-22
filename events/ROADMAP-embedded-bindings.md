# Roadmap: Bindings C/C++, Python et support multi-protocoles pour IoT/M2M

## Vision

Permettre aux assets embarqués (MCU, edge gateways, capteurs) de créer des contexts et propager des exchanges vers un serveur central garganttua-events, via des protocoles IoT standards (MQTT, Kafka, CoAP), avec support de sérialisation Protocol Buffers, XML et JSON.

---

## Phase 1 — Fondations : Schéma protobuf et module protocole

**Objectif** : Définir le format d'échange inter-langage comme source unique de vérité.

### 1.1 Créer le module `garganttua-events-protocol`

- Fichiers `.proto` définissant tous les types du wire protocol :
  - `Exchange` (payload, headers, journey steps, metadata, tenant/cluster/asset IDs)
  - `JourneyStep` (step_id, direction, asset_id, route_id, timestamp)
  - Records de contexte : `ContextDef`, `RouteDef`, `RouteStageDef`, `TopicDef`, `DataflowDef`, `ConnectorDef`, `SubscriptionDef`, `LockDef`
  - Messages de management (soumission de contexte, heartbeat, etc.)
- Plugin Maven `protobuf-maven-plugin` pour générer les classes Java
- Scripts de build pour générer les sources C (nanopb) et Python (`protoc --python_out`)
- Champ `schema_version` dans l'Exchange pour l'évolution du schéma

### 1.2 Support protobuf dans le serveur Java

- Ajouter la dépendance `protobuf-java` au core
- Adapter les expressions `protocol_in` / `protocol_out` pour dispatcher selon le content-type (JSON, protobuf, XML)
- Négociation de content-type via header ou propriété MQTT user-property

### 1.3 Support XML dans le serveur Java

- Ajouter un sérialiseur/désérialiseur XML pour Exchange et les context records
- Utiliser JAXB ou Jackson XML (cohérent avec Jackson JSON déjà utilisé)
- XML limité au serveur et clients Python — pas pertinent pour devices embarqués contraints

---

## Phase 2 — Connecteur MQTT Java

**Objectif** : Permettre au moteur Java de consommer/produire directement via MQTT.

### 2.1 Créer le module `garganttua-events-connector-mqtt`

- Implémenter `IConnector` / `IConsumer` / `IProducer` avec Eclipse Paho Java Client
- Configuration via `Map<String, String>` : broker URL, client ID, QoS, TLS, clean session, topic mapping
- Support MQTT 3.1.1 et 5.0
- Gestion des reconnexions et persistence des messages (QoS 1/2)
- Mapping topics MQTT ↔ topics garganttua-events

### 2.2 Architecture edge-to-cloud

- Pattern recommandé : Devices → MQTT Broker (EMQX/HiveMQ/Mosquitto) → Kafka (via bridge ou connecteur MQTT direct)
- Le connecteur MQTT permet aussi un déploiement simplifié sans Kafka (MQTT direct)

---

## Phase 3 — Client Python

**Objectif** : SDK Python pur pour edge gateways et clients non-contraints.

### 3.1 Créer le package `garganttua-events-client-python`

- **Approche recommandée** : reimplementation Python pure (pas de wrapping C/JNI)
  - Zéro dépendance native → installable via `pip install` partout
  - Compatible CPython et MicroPython (subset)
- Dépendances : `protobuf`, `paho-mqtt`, `xml.etree` (stdlib)

### 3.2 Fonctionnalités du client Python

- Construction d'Exchange (payload, headers, metadata, journey steps)
- Sérialisation/désérialisation protobuf, JSON, XML
- Publication d'exchanges via MQTT (paho-mqtt)
- Construction et soumission de ContextDef au serveur
- API fluide pour construire des contextes :
  ```python
  ctx = ContextBuilder() \
      .tenant("iot-fleet") \
      .cluster("factory-01") \
      .topic("sensors/temperature") \
      .route("temp-route") \
          .stage("not_null") \
          .stage("log", "INFO") \
      .build()
  ```

### 3.3 Transport pluggable

- MQTT (paho-mqtt) — principal
- Kafka (confluent-kafka-python) — optionnel
- gRPC (grpcio) — optionnel, pour communication request/response avec le serveur

---

## Phase 4 — Bibliothèque C/C++ pour embarqué

**Objectif** : Bibliothèque C légère pour MCU et devices contraints.

### 4.1 Approche : bibliothèque standalone (pas de JVM)

- **Pas de JNI/GraalVM** — les MCU n'ont pas de JVM
- Bibliothèque C pure implémentant le wire protocol protobuf
- Interopérabilité définie au niveau du format fil (même `.proto` que Java/Python)
- Empreinte cible : <50 KB ROM pour le subset minimal

### 4.2 Créer le module `garganttua-events-client-c`

- Dépendances :
  - **nanopb** pour sérialisation protobuf (pas d'allocation dynamique, <10 KB ROM)
  - **Eclipse Paho MQTT Embedded C** (couche MQTTPacket) pour MQTT
  - Optionnel : **libcoap** pour CoAP sur UDP (devices ultra-contraints)
- Pas de support XML côté C (inutile et coûteux en embarqué)

### 4.3 API C

```c
// Construction d'un exchange
gg_exchange_t* exchange = gg_exchange_create();
gg_exchange_set_payload(exchange, data, data_len);
gg_exchange_set_header(exchange, "sensor-type", "temperature");
gg_exchange_set_tenant(exchange, "iot-fleet");
gg_exchange_set_cluster(exchange, "factory-01");
gg_exchange_add_journey_step(exchange, "sensor-01", "temp-route", GG_DIRECTION_OUT);

// Sérialisation et envoi MQTT
uint8_t buffer[512];
size_t encoded_len = gg_exchange_encode(exchange, buffer, sizeof(buffer));
gg_mqtt_publish(client, "sensors/temperature", buffer, encoded_len, QOS_1);

gg_exchange_destroy(exchange);
```

### 4.4 Tiers de devices supportés

| Tier | Device | Protocole | Sérialisation | RAM min |
|------|--------|-----------|---------------|---------|
| Tier 1 — MCU ultra-contraint | Cortex-M0/M3, AVR | MQTT-SN/UDP ou CoAP | nanopb | <64 KB |
| Tier 2 — Device contraint | ESP32, STM32MP1 | MQTT/TCP+TLS | nanopb | 256 KB - 1 MB |
| Tier 3 — Edge gateway | Raspberry Pi, gateway industrielle | MQTT + Kafka client | protobuf-c | >128 MB |

### 4.5 Portabilité

- API POSIX pour Linux/gateways
- Couche d'abstraction OS (HAL) pour FreeRTOS, Zephyr, bare-metal
- Build system CMake avec options de cross-compilation

---

## Phase 5 — Connecteurs additionnels et optimisations

### 5.1 Connecteur CoAP Java (optionnel)

- Pour devices ultra-contraints utilisant UDP au lieu de TCP
- Basé sur Eclipse Californium (implémentation Java CoAP)
- Ou proxy CoAP-to-MQTT en amont du broker

### 5.2 Support CBOR (optionnel)

- Alternative à protobuf pour devices nécessitant un format schema-less
- ~60% plus compact que JSON, sémantiquement similaire
- Utile si certains devices ne peuvent pas embarquer un schema protobuf fixe

### 5.3 Connecteur AMQP (optionnel)

- Pour intégration enterprise (RabbitMQ, Azure Service Bus)
- Plus lourd que MQTT, adapté aux cas nécessitant transactions et routing complexe

---

## Résumé des modules à créer

| Module | Langage | Priorité | Dépendances clés |
|--------|---------|----------|-------------------|
| `garganttua-events-protocol` | Protobuf (.proto) | P0 | protobuf-maven-plugin, nanopb_generator |
| Support protobuf+XML dans core | Java | P0 | protobuf-java, jackson-dataformat-xml |
| `garganttua-events-connector-mqtt` | Java | P1 | Eclipse Paho Java |
| `garganttua-events-client-python` | Python | P2 | protobuf, paho-mqtt |
| `garganttua-events-client-c` | C | P3 | nanopb, Paho MQTT Embedded C |
| `garganttua-events-connector-coap` | Java | P4 | Eclipse Californium |

---

## Risques et considérations

- **Évolution du schéma protobuf** : Ne jamais réutiliser de numéros de champs supprimés. Utiliser `reserved` pour les champs retirés. Tester la compatibilité ascendante/descendante entre versions.
- **Sécurité IoT** : TLS/DTLS obligatoire en production. Authentification device par certificats X.509 ou tokens JWT. Rotation des credentials.
- **Bande passante** : Sur réseaux LPWAN (LoRa, NB-IoT), protobuf est 3-10x plus compact que JSON. Éviter XML sur ces réseaux.
- **MicroPython** : Le client Python complet ne fonctionnera pas sur MicroPython — prévoir un subset minimal ou utiliser le client C via FFI.
- **Maintenance multi-langage** : Les fichiers `.proto` comme source unique de vérité minimisent la dérive entre implémentations. CI obligatoire pour valider la compatibilité cross-langage.
