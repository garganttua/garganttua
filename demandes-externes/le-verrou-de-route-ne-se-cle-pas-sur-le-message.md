# Le verrou d'une route ne peut pas se clé sur le message

**À l'attention de :** garganttua-events (`api` : `IRouteBuilder`, `core` : `RouteMessageProcessor`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM ; cluster Kafka + Redis en production HDS à venir)
**Date :** 2026-11-02
**Version constatée :** garganttua-events `3.0.0-ALPHA30`
**Gravité :** confort et débit, pas correction. Sans cela, palliad sérialise toutes les écritures de son journal des
accès sous un seul verrou, ce qui suffit au volume estimé. La demande ne devient utile que si la mesure de pic le
montre ; nous la déposons pour qu'elle soit connue, pas pour qu'elle soit traitée d'urgence.

## Contexte

Le journal des accès de palliad est écrit par une route `ONLY_ONE_CLUSTER_NODE`. Ce mode garantit un seul lecteur à
la fois, mais pas toujours le même nœud (redémarrage, rééquilibrage du groupe), et pendant un rééquilibrage deux nœuds
peuvent traiter un court instant. L'état de la chaîne (rang et empreinte de la dernière ligne, **par équipe**) est donc
en base, lu et avancé sous la `synchronization` de la route. Deux équipes n'ont aucune raison de s'attendre.

## Cause

**Lu** :

- `events/api/src/main/java/com/garganttua/events/api/dsl/IRouteBuilder.java`, l. 22 :
  `synchronization(String lock, String lockObject)` ;
- `events/api/src/main/java/com/garganttua/events/api/context/RouteSyncDef.java` : deux chaînes fixes ;
- `events/core/src/main/java/com/garganttua/events/core/RouteMessageProcessor.java`, l. 189-223 : le mutex est résolu
  **une fois** par route (`toMutexName`), `lockObject` étant concaténé tel quel au nom.

## Attendu

Que `lockObject` puisse être une **expression évaluée sur chaque message** (la même syntaxe que les filtres de route,
par exemple `$.tenantId`), le mutex étant alors résolu par message : `<lock>:<valeur>`. Une valeur absente retombe sur
le verrou de la route sans suffixe.

## Ce que la fiche ne demande pas

- Changer le sens d'un `lockObject` littéral existant.
- Une file par clé ou un parallélisme par clé : la concurrence de la route reste ce qu'elle est ; le verrou par clé ne
  sert qu'à ne pas bloquer une équipe derrière une autre quand plusieurs nœuds traitent en même temps.
