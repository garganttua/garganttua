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
