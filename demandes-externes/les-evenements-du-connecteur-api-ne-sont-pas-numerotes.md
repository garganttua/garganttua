# Les événements du connecteur `api` ne sont pas numérotés : une perte en aval est indétectable

**À l'attention de :** garganttua-events (`connector-api`, `ApiEventCodec`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM ; cluster Kafka en production HDS à venir, BigQueue en on-prem)
**Date :** 2026-11-02
**Version constatée :** garganttua-events `3.0.0-ALPHA30`
**Gravité :** impossible de prouver qu'un flux est complet. Le journal des accès aux données de santé de palliad
(certification HDS) est alimenté par ce connecteur. Entre l'émission et l'écriture en base, un événement peut se
perdre à plusieurs endroits (file mémoire pleine, arrêt brutal du processus, échec de publication). Sans numéro
d'ordre posé à la source, le dernier maillon ne voit pas le trou. Ce que l'auditeur demande n'est pas « aucune perte
jamais », c'est « toute perte connue et datée ».

## Symptôme

Après un arrêt brutal (OOM, `kill -9`, nœud perdu), les événements qui attendaient dans la file mémoire du connecteur
ont disparu. Rien dans le flux ne permet de le constater ni d'en connaître le nombre.

## Cause

**Lu** :

- `events/connector-api/src/main/java/com/garganttua/events/connectors/api/ApiEventsConsumer.java`, l. 54 : la file
  est en mémoire ; l. 109 : un événement peut y être jeté (fiche voisine sur le débordement).
- `events/connector-api/src/main/java/com/garganttua/events/connectors/api/ApiEventCodec.java`, l. 52-72 : les clés
  encodées sont `operation`, `domain`, `businessOperation`, `useCase`, `code`, `exceptionCode`, `exceptionMessage`,
  `tenantId`, `entityUuid`, `ownerId`, `userId`, `inDate`, `outDate`, `in`, `out`. Aucun identifiant d'événement,
  aucune identité d'émetteur, aucun numéro d'ordre.
- L'`executionUuid` existe dans `Domain.invoke`
  (`api/core/src/main/java/com/garganttua/api/core/domain/Domain.java`, l. 284) mais n'est pas recopié dans l'`IEvent`
  ni dans le codec.

Une route en aval ne peut pas poser ce numéro elle-même : la perte a lieu **avant** elle.

## Attendu

Que le connecteur `api` ajoute à chaque événement **qu'il retient** (donc après son filtrage), au moment où il le
prend (dans l'observateur, avant la file) :

| Clé | Contenu |
|---|---|
| `eventId` | un uuid unique (l'`executionUuid` de l'opération conviendrait, s'il est propagé) |
| `source` | l'identité du nœud (l'`assetId` du moteur d'événements suffit) |
| `bootId` | un uuid tiré au démarrage du connecteur |
| `seq` | un compteur **continu** par instance de connecteur, à partir de 1 |

Et à l'arrêt propre (`stop()`), un dernier message « fin de séquence » portant `source`, `bootId` et le dernier `seq`
émis. Un `bootId` dont la fin de séquence n'arrive jamais signe alors un arrêt brutal.

Avec cela, le consommateur final tient « dernier `seq` vu par (`source`, `bootId`) » et écrit une ligne « trou de n à
m » quand il saute.

## Ce que la fiche ne demande pas

- Une numérotation globale au cluster : par instance de connecteur suffit, et n'exige aucune coordination.
- Que le cadre détecte les trous lui-même : c'est le travail du consommateur final, qui seul sait ce qui lui est
  arrivé.
- Une durabilité de la file (voir la fiche sur le débordement, mode `SPILL`, et celle sur l'échec d'observateur).
