# La synchronisation des écritures casse l'upsert de DÉMARRAGE — « StatementBlock: no runtime context available »

**À l'attention de :** garganttua-api
**Émis par :** palliad (v3, AOT pur, multi-tenant, MongoDB)
**Date :** 2026-09-19
**Version constatée :** `3.0.0-ALPHA20`
**Se rapporte à :** [synchronisation-multi-instances-absente-de-lapi](synchronisation-multi-instances-absente-de-lapi.md)
(la fonctionnalité livrée en réponse) et
[mutex-redis-au-classpath-tue-lapi](mutex-redis-au-classpath-tue-lapi.md) (le défaut qu'il faut
lever d'abord pour reproduire celui-ci)
**Gravité :** bloquante. Une application qui déclare des entités de démarrage ne peut PAS activer la
synchronisation : l'API ne monte pas. Et comme le serveur web continue de servir, l'instance paraît
saine.
**Statut de la constatation :** **MESURÉ**, avec témoin.

## Symptôme

Avec une politique déclarée depuis un `IApiAutoConfiguration` — le scénario exact de la fiche
d'origine, et exactement la forme que vous proposez :

```java
context.apiBuilder().synchronization(
        SynchronizationPolicy.of(manager, IClass.getClass(RedisMutex.class))
                .withStrategy(new MutexStrategy(10, SECONDS, 0, 0, MILLISECONDS, 30, SECONDS))
                .withUseCases(Set.of("submit", "reset", "recompute", "purge-references")));
```

le démarrage échoue :

```
ERROR … - Auth API failed to start — the web server keeps serving
com.garganttua.api.commons.ApiException: Startup update (upsert) on domain 'users' returned SERVER_ERROR
    at …DomainStartupExecutor.bootstrapFailure(DomainStartupExecutor.java:276)
    at …DomainStartupExecutor.upsertStartupEntities(DomainStartupExecutor.java:132)
Caused by: com.garganttua.core.workflow.WorkflowException: StatementBlock: no runtime context available
    at …Workflow.executePrecompiled(Workflow.java:223)
    at …Domain.doInvoke(Domain.java:342)
    at …DomainStartupExecutor.bootstrapInvoke(DomainStartupExecutor.java:246)
    at …DomainStartupExecutor.bootstrapUpdate(DomainStartupExecutor.java:222)
Caused by: com.garganttua.core.script.ScriptException: StatementBlock: no runtime context available
    at com.garganttua.core.script.nodes.StatementBlock.execute(StatementBlock.java:61)
```

Redis est joignable (`PING` → `PONG`) et **aucune clé n'a été posée** : l'échec précède
l'acquisition.

## Témoin

Même binaire, même classpath, même Redis. Seule la politique change :

| Politique déclarée | API montée ? |
|---|---|
| aucune | **oui** — 401 `application/json`, zéro échec de montage |
| `apiBuilder.synchronization(…)` globale | **NON** — trace ci-dessus |

La politique est donc bien le déclencheur, et le chemin fautif est celui du DÉMARRAGE
(`DomainStartupExecutor.bootstrapInvoke`), pas celui d'une requête HTTP.

## Ce que nous en lisons

L'enveloppe `synchronizeWrite` exécute le `StatementBlock` que `wrap` lui passe — c'est bien le
correctif que votre réponse décrit, et il paraît juste pour une requête. Mais l'upsert de démarrage
invoque le domaine **hors requête**, et le bloc n'y trouve pas de contexte d'exécution. Les quinze
tests d'intégration de `WriteSynchronizationIntegrationTest` n'ont pas pu voir le cas : ils
éprouvent des écritures, pas un démarrage qui sème.

Ce n'est pas un cas marginal : c'est le mécanisme par lequel une application pose son administrateur
d'amorçage et ses référentiels. Chez nous, `users`, `pages` et le gabarit de contenu passent par là.

## Attendu

Que la synchronisation n'empêche pas le démarrage, dans cet ordre de préférence :

1. **Que l'enveloppe fonctionne aussi hors requête** — le contexte manquant fourni sur le chemin
   d'amorçage, l'upsert tournant alors sous verrou comme une écriture ordinaire. C'est la réponse la
   plus juste : deux instances qui démarrent ensemble sèment les mêmes entités, et c'est précisément
   une course.
2. **Ou que les écritures de démarrage soient exemptées**, explicitement et documentées comme
   telles : elles sont idempotentes par construction (upsert par uuid stable), donc la course y est
   inoffensive. Une exemption écrite vaut mieux qu'un échec.

Et, quelle que soit l'option : **un test qui démarre une api avec des entités de démarrage ET une
politique**, pour que le cas ne puisse plus passer entre les mailles.

## Ce que cette fiche ne demande pas

- Pas de changement de la clé, de la stratégie, ni du 409 sur contention : tout cela nous convient
  tel que livré.
- Pas de verrou sur les lectures.
- Pas de traitement particulier des cas d'usage : le `withUseCases` fait ce qu'il annonce, nous ne
  l'avons simplement pas encore pu éprouver.

## Où nous en sommes

Notre module est écrit et compile (`palliad-cluster` : SPI, registre de mutex câblé explicitement,
politique globale). Il ne peut pas tourner tant que ces deux fiches ne sont pas levées — celle-ci et
celle des surcharges de `syncRedis`. Nous reprendrons la mesure dès qu'une version les corrige : le
banc est prêt, Redis compris.

---

## Réponse de la plateforme — 2026-09-20

**Traitée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA21`.

Et d'abord ceci : **le défaut est plus large que votre fiche ne le dit, et c'est notre faute, pas la
vôtre.** Vous ne pouviez pas le voir — vous n'avez jamais pu dépasser la première écriture.

### Ce n'est pas le démarrage

Nous avons commencé par écrire votre cas : une api avec entités de démarrage et une politique
déclarée. **Le test est passé.** Nous l'avons durci — la ligne présente en base, donc l'upsert
prenant le chemin de la mise à jour, comme à chaque redémarrage après le premier. **Passé encore.**

Ce qui manquait n'était pas dans le scénario, il était dans notre outillage de test. Notre faux
verrou exécutait le bloc protégé **sur le thread appelant**. `InterruptibleLeaseMutex` ne le fait
pas : pour appliquer le bail — que nous avons rendu obligatoire — il soumet le bloc à un exécuteur,
pour pouvoir l'interrompre. Le bloc tourne donc **sur un autre thread**.

Or les contextes ambiants dont un étage a besoin sont des `ScopedValue`, délibérément non
héritables. Ils ne traversent pas. Le bloc n'en trouve aucun :

```
ScriptException: StatementBlock: no runtime context available
```

**Conséquence : ce n'était pas l'upsert de démarrage. C'était TOUTE écriture synchronisée.** Votre
`PATCH` aurait échoué de la même façon. Vous l'avez rencontré au démarrage simplement parce que
c'est la première écriture qu'une application fait.

Nos quinze tests d'intégration étaient verts pendant ce temps. Un faux plus gentil que toutes les
implémentations réelles ne prouve rien, et celui-là a caché un défaut qui rendait la fonctionnalité
entièrement inopérante. C'est la leçon la plus chère de cette livraison.

### Le correctif

`synchronizeWrite` capture les contextes ambiants **sur le thread qui prend le verrou** et les
rétablit autour du bloc, quel que soit le thread sur lequel le mutex choisit de l'exécuter. Il y en
a deux — le contexte d'exécution du runtime, et celui du script, sans lequel `include()` et
`execute_script()` refusent à leur tour (`include: no script execution context available`, que nous
avons vu juste après le premier).

Nous avons retenu **votre option 1**, comme vous la préfériez : l'upsert de démarrage tourne sous
verrou comme une écriture ordinaire. Deux instances qui démarrent ensemble sèment les mêmes entités,
et vous avez raison que c'est une course — l'exempter aurait laissé un trou à l'endroit précis où
une application pose son administrateur d'amorçage.

### Ce qui garde le cas fermé

Trois choses, parce qu'une seule n'aurait pas suffi :

1. **Notre faux verrou exécute désormais le bloc sur un autre thread**, comme le fait toute
   implémentation qui applique un bail. Les quinze tests existants tournent contre lui.
2. **Un test avec le VRAI `InterruptibleLeaseMutex`** de core, qui écrit réellement. C'est le
   garde-fou : si le socle acquiert un troisième contexte ambiant, il échoue ici et non dans vos
   journaux.
3. **Le test que vous demandiez** : une api qui démarre avec une entité de démarrage déjà présente
   en base ET une politique déclarée.

`WriteSynchronizationIntegrationTest` en compte 17.

### Ce que nous ne changeons pas

Rien de ce que vous excluiez : ni la clé, ni la stratégie, ni le 409 sur contention, ni de verrou
sur les lectures, ni de traitement particulier des cas d'usage — `withUseCases` continue de faire ce
qu'il annonce, et vous pourrez enfin l'éprouver.

### Pour votre banc

Les deux fiches sont levées dans la même version. Votre module `palliad-cluster` devrait tourner
tel qu'il est écrit. Un point à vérifier de votre côté quand vous mesurerez : le bail que vous
passez dans la `MutexStrategy` n'est **pas** lu par `RedisMutex` — il vient du `RedUtilsConfig` de
la fabrique. C'est écrit dans la fiche d'origine, section « Précision sur le bail » ; nous le
répétons ici parce que votre stratégie en déclare un et qu'il ne fera rien.
