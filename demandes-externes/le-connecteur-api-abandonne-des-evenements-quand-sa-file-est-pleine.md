# Le connecteur `api` abandonne des événements quand sa file est pleine, et rien en aval ne peut le savoir

**À l'attention de :** garganttua-events (`connector-api`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM ; cluster Kafka en production HDS à venir, BigQueue en on-prem)
**Date :** 2026-11-02
**Version constatée :** garganttua-events `3.0.0-ALPHA30`
**Gravité :** perte silencieuse. Pour palliad, le connecteur `api` doit alimenter le **journal des accès aux données
de santé** exigé par la certification HDS (traçabilité des consultations). Un accès dont l'événement est jeté n'existe
pas dans le journal, et aucun maillon en aval ne peut le détecter : c'est exactement ce qu'un auditeur ne peut pas
accepter. La même perte touche déjà, sans bruit, la chaîne du rapport annuel de palliad (rattrapée chez nous par une
réconciliation ; un journal d'accès n'a pas de source à relire).

## Symptôme

Aucun en fonctionnement normal. Sous charge (rafale de lectures, route lente, transport en panne qui ralentit le
fil de vidage), des événements disparaissent. La seule trace est un WARN, au plus une fois toutes les 5 s :
`Api events consumer queue full (10000); dropping events` — sans le nombre d'événements perdus.

## Cause

**Lu** dans `events/connector-api/src/main/java/com/garganttua/events/connectors/api/ApiEventsConsumer.java` :

- l. 46 et 54 : `QUEUE_CAPACITY = 10_000`, `ArrayBlockingQueue` en mémoire ;
- l. 109 : `if (!this.queue.offer(...)) { logOverflow(); }` — l'événement est jeté ;
- l. 119 : le WARN limité dans le temps ;
- l. 159 : le fil de vidage passe chaque octet au gestionnaire de la route, un par un.

Le commentaire de classe le dit (« On queue overflow the event is dropped ... the emitting thread is never
blocked ») : c'est un choix, cohérent pour de l'observabilité. Il ne convient pas à un consommateur qui doit **tout**
recevoir.

## Attendu

Une **politique de débordement par connecteur**, le défaut restant le comportement actuel :

1. `DROP` (défaut actuel) ;
2. `BLOCK` avec délai : le fil émetteur attend une place (`offer(bytes, timeout)`) ; au-delà du délai, l'événement
   est traité comme sous `DROP` **et compté** ;
3. éventuellement `SPILL` : débordement sur une file disque (BigQueue, déjà connu du cadre).

Et dans tous les modes, un **compteur des événements abandonnés**, lisible par l'application (méthode du connecteur
ou métrique), plutôt qu'un WARN seulement.

## Ce que la fiche ne demande pas

- Changer le défaut : les autres usages du connecteur (observabilité) doivent rester non bloquants.
- Agrandir la file : ça repousse la perte, ça ne la supprime pas.
- La numérotation des événements (fiche voisine
  [les-evenements-du-connecteur-api-ne-sont-pas-numerotes](les-evenements-du-connecteur-api-ne-sont-pas-numerotes.md)) :
  elle détecte une perte, celle-ci l'évite. Les deux sont utiles ensemble.
