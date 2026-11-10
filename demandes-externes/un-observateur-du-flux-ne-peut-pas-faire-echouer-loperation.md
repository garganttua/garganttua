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
