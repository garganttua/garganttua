# Les connecteurs Kafka et BigQueue acquittent un message que la route n'a pas réussi à traiter

**À l'attention de :** garganttua-events (`connector-kafka`, `connector-bus`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM ; Kafka en cluster, BigQueue en mono-nœud et on-prem)
**Date :** 2026-11-02
**Version constatée :** garganttua-events `3.0.0-ALPHA30`
**Gravité :** livraison « au plus une fois » sur les deux transports durables. Un échec de la route (base
indisponible un instant, verrou non obtenu, exception) perd le message pour de bon. Palliad vit avec depuis ALPHA24
pour le rapport annuel (rattrapé par une réconciliation nocturne) ; son futur journal des accès aux données de santé
(certification HDS) ne peut pas, faute de source à relire. Le cadre a déjà l'outil :
`IAcknowledgingConsumer` (ALPHA28) — palliad l'implémente dans son connecteur de relève HTTP, et il marche.

## Symptôme

Route d'écrivain en échec sur un message : le message part vers la souscription d'erreur si elle existe, sinon nulle
part ; au redémarrage il n'est pas relivré.

## Cause

**Lu** :

- `events/connector-kafka/src/main/java/com/garganttua/events/connectors/kafka/KafkaConsumer_.java`, l. 16 : implémente
  `IConsumer` seulement ; l. 39-42 : `records.forEach(record -> messageHandler.accept(...))` puis `commitSync()` dès que
  le lot n'est pas vide, **quel que soit le résultat** de la route. Avec une concurrence supérieure à 1, le gestionnaire
  soumet au pool (`RouteMessageProcessor`, l. 100-104) : l'offset peut même être validé **avant** que la route n'ait
  tourné.
- `events/connector-bus/src/main/java/com/garganttua/events/connectors/bus/BusConsumer.java`, l. 11 : `IConsumer`
  seulement ; l. 41-43 : `queue.dequeue()` **puis** `messageHandler.accept(...)` — le message est retiré du disque avant
  d'être traité.
- `events/core/src/main/java/com/garganttua/events/core/RouteMessageProcessor.java`, l. 83-95 : le moteur sait déjà
  rendre l'issue de chaque message à un `IAcknowledgingConsumer` (`CompletionStage<Boolean>`, `true` après une route
  réussie).

## Attendu

Que `KafkaConsumer_` et `BusConsumer` implémentent `IAcknowledgingConsumer` :

- **Kafka** : valider l'offset d'un enregistrement seulement quand son issue est `true` ; sur `false`, revenir à cet
  offset (`seek`) pour le relivrer, sans valider les suivants de la même partition. Avec une concurrence de 1 et
  l'ordre garanti, cela revient à valider message par message.
- **BigQueue** : lire sans retirer (`peek`), retirer sur `true` ; sur `false`, laisser le message en tête et réessayer
  au tour suivant (avec un nombre d'essais au-delà duquel il part vers la souscription d'erreur, pour ne pas bloquer la
  file sur un message empoisonné).

Les consommateurs devront être idempotents : c'est le contrat de « au moins une fois », et palliad l'est déjà.

## Ce que la fiche ne demande pas

- Garder les deux modes : si l'acquittement après route devient le seul comportement, palliad n'y voit pas
  d'inconvénient, mais d'autres consommateurs pourraient ; un réglage par connecteur conviendrait aussi.
- Une sémantique « exactement une fois ».

## Réponse de la plateforme — 2026-11-10

**Traitée.** Le choix se fait dans le DSL, par souscription, sous la forme d'une qualité de service. Sur la branche
`perf/token-verification`, à paraître dans `3.0.0-ALPHA34`.

```java
.consumerConfiguration().qualityOfService(QualityOfService.AT_LEAST_ONCE)
```

ou `"qualityOfService": "AT_LEAST_ONCE"` dans un contexte JSON. Sans déclaration, chaque connecteur garde son
comportement. Une route `AT_LEAST_ONCE` sur un connecteur qui ne sait pas acquitter après la route est **refusée au
démarrage du moteur**, plutôt que de perdre en silence.

### Le contrat de l'issue a changé, et votre relève HTTP est concernée

Jusqu'ici, un message déposé dans la souscription d'erreur rendait `false`. Un connecteur qui acquitte après la
route, comme votre relève HTTP, le rendait donc au transport : il était relivré **et** se trouvait aussi dans la file
d'erreur, un doublon par essai.

Désormais :
- **`true`** veut dire *en lieu sûr* : la route a réussi, ou la souscription d'erreur a pris le message ;
- **`false`** veut dire *nulle part* : il échoue encore et il n'y a pas de souscription d'erreur, ou le dépôt a lui
  aussi échoué.

Votre relève n'a rien à changer : elle cessera simplement de relivrer ce qui est déjà en file d'erreur.

### Des essais avant la file d'erreur

`exceptions(to, cast, label, attempts)` (ou `"attempts"` en JSON) : la route rejoue un message jusqu'à n fois, en
attendant 200 ms, puis le double à chaque essai, 5 s au plus. Le message ne va en erreur qu'ensuite. Le défaut est
1, c'est-à-dire le comportement actuel. Un message empoisonné finit donc en erreur au lieu de bloquer, à condition
qu'une souscription d'erreur soit déclarée.

### Les deux transports

- **Kafka** (`KafkaAcknowledgingConsumer`) :
  - le lot lu est remis à la route, en parallèle si la concurrence le permet, et toutes ses issues sont attendues ;
  - l'offset est ensuite validé, partition par partition, jusqu'au premier message qui n'est pas en lieu sûr ;
  - le connecteur se **repositionne** sur ce message et le relit après une attente croissante ;
  - les messages suivants de la même partition sont relus aussi, ce qui fait partie du contrat « au moins une fois » ;
  - l'auto-commit est forcé à off, avec un avertissement s'il était demandé.
- **BigQueue** (`BusAcknowledgingConsumer`) :
  - la tête de file est lue sans être retirée (`peek`), puis retirée seulement une fois en lieu sûr ;
  - sinon elle reste en tête et est réessayée après une attente croissante ;
  - la file d'un sujet est partagée par ses consommateurs sur le nœud, donc tout se fait sous le verrou de la file,
    que le consommateur classique prend aussi : un message à la fois par file ;
  - un message illisible est retiré avec une erreur, puisque rien ne pourra jamais le traiter.

Sans souscription d'erreur, un message qui échoue toujours bloque sa partition ou sa file. Pour un journal
d'audit, c'est préférable à la perte.

### Deux défauts trouvés en chemin, corrigés

- **`produce` vers les destinations d'une route ne faisait que journaliser l'échec d'une publication.** La route
  réussissait, et le message était perdu en silence.
  - Elle publie toujours vers toutes ses destinations, puis **échoue** si l'une a manqué.
  - Un Kafka arrêté fait donc enfin échouer la route, avec ses essais, son erreur et son acquittement.
  - Sans cela, ni cette fiche ni le mode `sync` n'auraient servi à rien.
- **BigQueue ne démarrait pas sans log4j 1.x** (`NoClassDefFoundError: org/apache/log4j/Logger`), ce que votre
  `palliad-backend-common` contourne avec `log4j-over-slf4j`. Le connecteur l'apporte désormais lui-même. Votre
  contournement devient inutile, mais il ne gêne pas.

Testé :
- `KafkaAcknowledgingConsumerTest` : validation, repositionnement et relecture, issues tardives attendues ;
- `BusAcknowledgingConsumerTest` : sur une vraie file disque ;
- `EventsQualityOfServiceE2ETest` : le moteur de bout en bout, avec les essais, le sens de l'issue, les trois
  déclarations de qualité de service, le refus et le contexte JSON.
