# Un observateur du flux ne peut pas faire échouer l'opération observée

**À l'attention de :** garganttua-core (`GlobalObservers`), garganttua-events (`connector-api`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM ; cluster Kafka en production HDS à venir, BigQueue en on-prem)
**Date :** 2026-11-02
**Version constatée :** garganttua-core / garganttua-events `3.0.0-ALPHA30`
**Gravité :** pas de garantie « aucun accès sans trace ». Le journal des accès aux données de santé de palliad
(certification HDS) est alimenté par le connecteur `api`. Quand la trace ne peut pas être enregistrée (courtier Kafka
injoignable, disque BigQueue plein), la donnée est servie quand même et l'accès n'est nulle part. C'est un souhait
plus qu'un bloquant : la numérotation demandée à côté rend la perte détectable ; celle-ci la rendrait impossible.

## Symptôme

Courtier Kafka arrêté : les lectures de dossiers patients répondent 200 normalement, et la route qui publie les
événements d'accès échoue en arrière-plan. Le seul signe est dans les journaux.

## Cause

**Lu** :

- `api/core/src/main/java/com/garganttua/api/core/domain/Domain.java`, l. 297-306 : l'événement de fin
  (`scope.fireEnd`) est émis **après** `doInvoke` et **avant** `return response` — donc avant que la liaison HTTP
  n'écrive la réponse. Le moment serait le bon.
- `core/commons/src/main/java/com/garganttua/core/observability/GlobalObservers.java`, l. 84-96 : `fire` appelle chaque
  observateur dans un `try` et **avale** toute `RuntimeException` (WARN). La méthode est documentée « never throws ».
- `events/connector-api/.../ApiEventsConsumer.java`, l. 107-112 et 154-160 : l'observateur ne fait qu'empiler ; la
  route s'exécute plus tard sur le fil de vidage, et le connecteur n'implémente pas `IAcknowledgingConsumer` : son
  échec ne remonte nulle part.

## Attendu

Un mode **synchrone et bloquant** pour un connecteur `api`, choisi explicitement à sa déclaration (par exemple
`.synchronous(true)` ou un `deliveryMode`), où :

1. la route s'exécute **sur le fil émetteur**, sans file ;
2. si elle échoue, l'échec **remonte à l'opération** : l'appel `fireEnd` lève, `Domain.invoke` rend une erreur (500 ou
   503), la donnée n'est pas servie.

Côté core, cela suppose qu'un observateur puisse se déclarer « bloquant » et que `fire` relance l'exception **de cet
observateur-là** (les autres restant protégés comme aujourd'hui). Palliad limiterait la route synchrone à une
publication (Kafka ou BigQueue), pour que son coût sur la requête reste faible ; nous le mesurerons.

## Ce que la fiche ne demande pas

- Changer le comportement par défaut de `fire` : un observateur ordinaire ne doit jamais pouvoir casser une
  opération.
- Annuler une **écriture** déjà faite : pour une création ou une mise à jour, l'échec arriverait après l'écriture.
  Palliad s'en contente (le besoin porte d'abord sur les lectures). Un veto sur l'événement de début serait une autre
  demande, que nous ne faisons pas.

## Réponse de la plateforme — 2026-11-10

**Traitée comme proposé.** Sur la branche `perf/token-verification`, à paraître dans `3.0.0-ALPHA34`.

- **core** : `GlobalObservers.addBlockingObserver(...)`.
  - `fire` offre d'abord l'événement à **tous** les observateurs. Ensuite seulement, si un observateur bloquant a
    échoué, il lève `ObserverFailureException`.
  - Les observateurs ordinaires restent protégés comme avant. `ObservabilityEmitter` notifie aussi ses propres
    registres avant de relever l'échec.
- **api** : si un observateur bloquant refuse l'événement de fin (ou de début), `Domain.invoke` rend
  **`NOT_AVAILABLE` (503)**, sans la donnée.
- **connecteur `api`** : `delivery=sync`.
  - Il n'y a pas de file : l'observateur est bloquant, la route s'exécute pendant que l'opération attend
    (`deliveryTimeout`, 5000 ms par défaut).
  - Une route qui échoue, ou qui ne répond pas à temps, fait échouer l'opération.
  - La numérotation s'applique aussi, et la fin de séquence est livrée à l'arrêt.
- **Garde-fou** : le mode `sync` a besoin de l'issue de la route. Il est refusé sur une souscription déclarée
  `AT_MOST_ONCE`, ainsi que s'il est démarré sans acquittement.

Comme vous l'aviez accepté, une écriture est déjà faite quand l'échec arrive : la réponse est 503, mais la donnée
est écrite.

**Ce qui le rend utile** est le défaut corrigé avec la fiche Kafka/BigQueue. `produce` journalisait l'échec d'une
publication sans faire échouer la route : avec un Kafka arrêté, une route `sync` aurait répondu « réussie ». Ce
n'est plus le cas.

Testé :
- `GlobalObserversTest` : l'échec remonte après la notification de tous, plusieurs échecs sont gardés, l'émetteur
  notifie d'abord ;
- `OperationTraceTest` : 503 sans donnée, à la fin comme au début, et service normal quand l'observateur enregistre ;
- `ApiEventsDeliveryTest` : `sync` réussi, en échec, en délai dépassé, et démarré sans issue.
