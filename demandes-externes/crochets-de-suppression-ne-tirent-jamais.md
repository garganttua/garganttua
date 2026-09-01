# Bogue — les crochets libres `beforeDelete` / `afterDelete` ne tirent jamais

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-25
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** élevée — un consommateur écrit une purge à la suppression, le DSL l'accepte, la
compilation passe, et la purge ne s'exécute jamais. Rien ne le signale.

## Symptôme

Un crochet libre attaché à `beforeDelete` ou `afterDelete` n'est **jamais appelé**. La suppression
rend 200, l'entité disparaît, et le code que le consommateur avait écrit pour nettoyer ce qui la
citait ne tourne pas.

Reproduit sur palliad, domaine `activities` : `PatientActivityIndex` retire de
`Patient.interventionActivityUuids` l'uuid d'une activité supprimée. `DELETE /activities/{A}` rend
200, `GET /activities/{A}` rend 404 — et l'index du patient **garde la référence morte**, avec un
`lastInterventionDate` qui pointe l'acte supprimé. La fiche patient peint donc une ligne
d'historique qui ne se relit plus.

Aucune exception, aucun journal, aucun avertissement au démarrage : le crochet est bien enregistré,
il n'est simplement jamais exécuté.

## Cause

Deux défauts distincts, dans le même chemin.

**1. `runListLifecycleHooks` n'appelle pas `runFreeHooks`.**

`runBeforeDelete` et `runAfterDelete` passent tous deux par `runListLifecycleHooks`. Contrairement à
`runLifecycleHooks` (celui de la création et de la mise à jour), cette méthode n'appelle jamais
`runFreeHooks` : elle n'exécute que les crochets liés à un champ, et laisse tomber les crochets
libres.

Correction à une lecture trop rapide : **`afterGet` n'est PAS concerné.** `runAfterGet` a son propre
corps et appelle bien `runFreeHooks`. Seuls `beforeDelete` et `afterDelete` sont touchés.

**2. `ObjectAddress` construite sur un libellé ANSI.**

`MethodBinder.getExecutableReference()` rend `Methods.prettyColored(method)` — une chaîne
d'affichage, avec des séquences d'échappement de couleur. Les deux exécuteurs la passent telle
quelle à `new ObjectAddress(...)`. L'adresse ainsi construite ne désigne rien de résoluble.

## Conséquence pour le consommateur

Le patron « une entité référence une autre, la suppression purge la référence » est **inapplicable**
par les crochets. Le consommateur découvre le problème seulement s'il vérifie l'état APRÈS une
suppression réelle — un test unitaire sur le crochet lui-même passe, puisque la méthode est
correcte ; c'est son appel qui manque.

## Contournement en place chez le consommateur

Aucun qui rétablisse la purge. Palliad **raccommode à l'écriture suivante** : à la création de
l'intervention suivante pour le même patient, l'index est recalculé et la référence morte disparaît.
Entre-temps, la fiche patient cite une activité introuvable, et la date de dernière intervention
peut désigner un acte supprimé.

## Ce qu'on attend

Que `runListLifecycleHooks` appelle `runFreeHooks` comme le fait `runLifecycleHooks`, et que
`getExecutableReference()` rende une référence résoluble plutôt qu'un libellé d'affichage.

## Comment le reproduire

1. Déclarer un domaine avec un crochet libre sur `afterDelete` qui écrit dans un journal.
2. `DELETE` une entité de ce domaine.
3. Le journal reste muet ; la réponse est 200.
