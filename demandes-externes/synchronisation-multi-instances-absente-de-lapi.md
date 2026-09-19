# Aucune synchronisation entre instances : l'api ne connaît pas le mutex que core lui offre

**À l'attention de :** garganttua-api
**Émis par :** palliad (v3, AOT pur, image native, multi-tenant, MongoDB)
**Date :** 2026-09-19
**Version constatée :** garganttua-api `3.0.0-ALPHA19` (dépôt à `bc88341`)
**Gravité :** bloque la mise à l'échelle horizontale. Deux instances servant la même base se
perdent des écritures et franchissent les plafonds de quota, **sans erreur ni trace** — le dernier
qui écrit gagne, et personne n'apprend que l'autre a été effacé.
**Statut de la constatation :** **LU**, pas mesuré. Palliad tourne aujourd'hui en instance unique ;
la demande anticipe le second nœud, elle ne rapporte pas une panne en production.

## Ce que nous voulions faire, et qui n'est pas possible

Poser dans le classpath un module maison — rien d'autre qu'une dépendance — qui reçoive le
`IApiBuilder` avant son `build()` et active, **pour tous les domaines**, une synchronisation par
mutex Redis autour des opérations d'écriture. Le module absent, l'application se comporte
exactement comme aujourd'hui.

Nous pensions que l'api savait déjà le faire. Elle ne le sait pas, et ce qui manque n'est pas le
verrou : c'est le point où l'accrocher.

## Ce qui existe déjà, et qui est bon

Tout le nécessaire est dans `core`, mature et publié :

| Brique | Où |
|---|---|
| `IMutex`, avec `acquire(ThrowingFunction)` — la section critique passée en lambda, donc relâchée en `finally` | `core/commons/src/main/java/com/garganttua/core/mutex/IMutex.java:61,83` |
| `IMutexManager`, `IMutexFactory` (SPI), `MutexName` (type + nom), `@MutexFactory` | `core/commons/src/main/java/com/garganttua/core/mutex/` |
| `MutexStrategy` : attente, tentatives, intervalle, **bail** | `core/commons/src/main/java/com/garganttua/core/mutex/MutexStrategy.java:37-45` |
| Verrou **distribué** Redis | `core/bindings/mutex-redis/src/main/java/com/garganttua/core/mutex/redis/RedisMutex.java:22` |
| Publié en ALPHA19 | `com.garganttua.core:garganttua-mutex-redis:3.0.0-ALPHA19` |
| Auto-enregistrement par la seule présence au classpath | `core/mutex/src/main/resources/META-INF/services/com.garganttua.core.dsl.IBootstrapBuilderFactory` |

Et le patron d'intégration existe, **dans events** : `RouteMessageProcessor` enveloppe l'exécution
d'un message dans `handlers.mutex().acquire(() -> …)`
(`events/core/src/main/java/com/garganttua/events/core/RouteMessageProcessor.java:102-116`), dégrade
en exécution non synchronisée avec un `WARN` quand le verrou est irrésoluble (`:158-171`), et
**affine la clé par tenant et par entité** (`:177-194`). Le gestionnaire est monté une fois à
l'initialisation (`Events.java:172-185`), avec repli local si la construction échoue.

C'est exactement ce que nous demandons pour l'api. Le travail de conception est déjà fait ; il n'a
simplement jamais traversé la frontière entre les deux modules.

## La cause, lue dans les sources

**1. `api/` n'évoque le mutex nulle part.**

```
grep -rn "IMutex\|Mutex\|mutex" api/ --include=*.java --include=*.xml   →   0
```

`garganttua-mutex` est déclaré dans `core/pom.xml:179`, `core/console/pom.xml:60`,
`core/script/pom.xml:102`, `events/pom.xml:136`, `events/core/pom.xml:43` — **jamais dans un pom de
`api/`**.

**2. L'écriture CRUD est un lire-modifier-écrire sans protection.**

`api/core/src/main/resources/scripts/business/UPDATE_ONE.gs` lit l'entité stockée (`getEntities`,
ligne 30), fusionne les champs non nuls, puis écrit (`saveEntity`). Rien entre les deux : ni verrou,
ni champ de version, ni écriture conditionnelle. Le verrouillage optimiste n'existe pas non plus —
`grep "optimistic|@Version|versionField|compareAndSwap|ifMatch|etag" api/` ne rend rien.

Deux `PATCH` concurrents sur deux nœuds s'entrelacent donc ainsi :

```
nœud A : lit {statut: pending, notes: ""}          nœud B : lit {statut: pending, notes: ""}
nœud A : écrit {statut: validated}                 nœud B : écrit {notes: "rappeler"}
                                                   → la validation a disparu, 200 des deux côtés
```

La politique `ignoreNull` rend le cas fréquent plutôt que théorique : **toute** écriture partielle
relit l'entité entière pour la refusionner.

**3. Les crochets ne peuvent pas tenir lieu de verrou.** `@EntityBeforeCreate` /
`@EntityAfterCreate` sont deux appels indépendants : dans `CREATE_ONE.gs`, `runBeforeCreate` est
ligne 61, `saveEntity` ligne 76, `runAfterCreate` ligne 85 — et une erreur d'écriture (ligne 77,
`-> 500`) sort du script sans jamais atteindre le `after`. Un verrou pris dans le `before` fuirait
à la première erreur, ce qui est pire que pas de verrou du tout.

**4. Le vrai « autour » existe dans core-workflow, et l'api ne s'en sert pas.**
`IWorkflowStageBuilder.wrap(String)` passe le contenu du stage en `@0` à une expression
enveloppante (`core/commons/.../workflow/dsl/IWorkflowStageBuilder.java:79`, génération en
`core/workflow/.../generator/ScriptGenerator.java:196-217`). La fonction qui s'y branche est écrite
et publiée : `@Expression(name = "sync")`
(`core/mutex/src/main/java/com/garganttua/core/mutex/functions/MutexFunctions.java:73-115`, et son
équivalent Redis `RedisMutexFunctions.java:93`). `grep "\.wrap(" api/` → **0**.

## Ce qui empêche un tiers de le faire lui-même

Nous avons cherché le contournement avant d'écrire cette fiche. Le point d'entrée, lui, ne manque
pas : `IApiAutoConfiguration.apply(AutoConfigurationContext)` donne le builder non construit, un
`order()` pour se placer, et `registerResource(AutoCloseable)` pour confier un client Redis au cycle
de vie de l'api — invoqué en `ApiBuilder.java:354`, bien avant `build()`. Il est parfait.

Ce sont les trois suivants qui bloquent :

| Obstacle | Où | Effet |
|---|---|---|
| `stage(name)` construit **toujours un stage neuf**, et aucun getter ne rend un stage déclaré | `core/workflow/.../dsl/WorkflowBuilder.java:176-180` | un tiers ne peut pas reprendre le stage `create` ou `update` pour lui ajouter `.wrap("sync(…)")` ; `addStage` ne sait qu'ajouter en fin de liste, donc après `exit-code` |
| `IDomainBuilder.workflow(...)` est **ignoré pour les opérations CRUD** | `api/core/.../domain/DomainWorkflowAssembler.java:387-413` | seul `isSecurityDisabled()` y est lu ; le script standard est reposé quoi qu'on ait déclaré |
| `MutexContext` est un `ThreadLocal` alimenté **par la seule console** | `core/mutex/.../context/MutexContext.java:29`, unique `set` en `core/console/.../ScriptConsole.java:221` | un `sync(…)` injecté dans un script d'api lèverait « no MutexManager available in context » — et un `ThreadLocal` est de toute façon fragile dans un pool de threads HTTP |

Autour de `Domain.invoke` il n'y a que de l'observabilité (`fireStart`/`fireEnd`/`fireError`,
`Domain.java:285-293`) : les observateurs sont prévenus, ils n'encadrent rien et ne peuvent pas
retenir l'exécution.

## Ce que nous demandons

**Que l'api puisse être rendue synchronisée par la seule présence d'un module au classpath, sans
qu'une ligne de l'application change.**

La forme nous importe moins que la propriété. Deux nous conviendraient :

**A — le verrou, dans l'esprit d'events.** Une déclaration sur le builder, par exemple
`apiBuilder.synchronization(...)` globalement et `IDomainBuilder.synchronized(...)` par domaine,
qui enveloppe les stages d'ÉCRITURE (`create`, `update`, `deleteOne`, `deleteAll`, et les cas
d'usage qui écrivent) dans `mutex.acquire(...)`. Avec, repris d'events parce qu'ils y ont déjà fait
leurs preuves :

- la **clé affinée** par domaine + tenant + uuid d'entité — un verrou global sur `create`
sérialiserait toute la plateforme ;
- le **type de mutex choisi par le nom** (`RedisMutex::…` contre le mutex local), pour que la même
application tourne en mono-instance sans Redis ;
- la **dégradation annoncée** : verrou irrésoluble → exécution non synchronisée + `WARN`, jamais un
blocage au démarrage ;
- un **bail** obligatoire : un nœud qui meurt en section critique ne doit pas figer les autres.

**B — le verrouillage optimiste**, si la plateforme préfère ne pas dépendre d'un tiers : un champ de
version sur l'entité, écriture conditionnelle, et un conflit rendu au client (409). Cela règle la
perte de mise à jour, mais **pas** les invariants qui se calculent avant d'écrire — nos plafonds de
quota comptent les lignes existantes puis autorisent la création ; deux nœuds les franchissent tous
les deux. C'est pourquoi nous préférons A.

Quelle que soit la forme, ce qui nous serait le plus utile tout de suite, et qui semble le moins
coûteux : **rendre un stage déjà déclaré reprenable** (un `stage(name)` idempotent, ou un
`getStage(name)`), et **alimenter le `MutexContext` dans le pipeline de l'api**. Avec ces deux-là,
un module tiers écrit le reste — `wrap("sync(...)")` fait déjà exactement ce qu'il faut.

## Ce que cette fiche ne demande pas

- **Pas d'élection de leader, pas de quorum, pas de jeton d'exclusion (`fencing`).** Rien de tout
cela n'existe dans le monorepo et nous n'en avons pas l'usage.
- **Pas de transaction distribuée** entre domaines, ni de rollback : une opération, un verrou.
- **Pas de changement de défaut.** Sans le module, le comportement doit rester strictement celui
d'aujourd'hui — pas de verrou, pas de dépendance Redis, pas de coût. Nous sommes plusieurs
consommateurs à tourner en instance unique et cela doit le rester gratuitement.
- **Pas de dépendance de `api` vers `mutex`.** Le binding doit rester optionnel, apporté par celui
qui en veut.
- **Pas de synchronisation des lectures.** `readOne` / `readAll` n'ont pas à être sérialisés ; nous
n'avons pas besoin de lectures cohérentes entre nœuds.

## Pourquoi cela compte pour nous

Palliad est un SaaS de santé. Le passage à deux instances est prévu pour la disponibilité — une
mise à jour sans coupure, un nœud qui tombe sans interrompre le service. Trois de nos mécanismes
deviennent faux ce jour-là, et aucun ne le dira :

- les **plafonds d'abonnement** (`QuotaEnforcer` compte puis autorise) — un client dépasse le
plafond qu'il paie ;
- nos **observateurs qui relisent l'entité pour la réécrire** — trace des statuts, index des
interventions d'un patient : une entrée perdue sur deux écritures simultanées ;
- les **synchronisations planifiées** (annuaire FHIR, nomenclatures NOS, recalcul des bilans) — deux
nœuds les lanceraient en double.

Nous savons contourner le troisième (un seul nœud porteur des tâches). Les deux premiers tiennent à
l'écriture elle-même, et c'est là que nous ne pouvons rien sans vous.

---

## Réponse de la plateforme — 2026-09-19

**Traitée, dans la forme A.** Sur `main`, à paraître dans `3.0.0-ALPHA20`.

Votre fiche est la mieux instruite que ce dossier ait reçue : vous avez lu le socle, cité les
lignes, et cherché le contournement avant d'écrire. Tout ce que vous décrivez du manque est exact.
Trois de vos conclusions sur les **obstacles** sont fausses, et les trois vont dans le sens du
moins de travail — voilà ce que ça change.

### Ce que vous obtenez

```java
apiBuilder.synchronization(mutexManager, IClass.getClass(RedisMutex.class));   // pour tous
domainBuilder.synchronization(mutexManager, "com.acme.RedisMutex::factures");  // ou pour un seul
```

Depuis un `IApiAutoConfiguration`, donc exactement votre scénario : un module au classpath, pas une
ligne de l'application. Les deux formes de déclaration du type existent — l'`IClass`, vérifié à la
compilation et sûr en image native, et la chaîne `Type::nom` d'events, pour que la même déclaration
se lise pareil sur toute la plateforme.

Les quatre propriétés que vous demandiez, reprises d'events parce qu'elles y ont fait leurs preuves :

| Ce que vous demandiez | Tenu |
|---|---|
| clé affinée par domaine + tenant + entité | `<préfixe>:<tenant>:<uuid>` — et sur `create`, `<préfixe>:<tenant>`, sans uuid, ce qui est précisément la granularité dont vos plafonds ont besoin |
| type de verrou choisi par le nom | oui, `IClass` ou `Type::nom` |
| dégradation annoncée | verrou irrésoluble → `WARN` + écriture non synchronisée, jamais de blocage au démarrage |
| bail obligatoire | une politique sans bail est **refusée à la construction** |

Et vos cinq exclusions sont tenues : pas d'élection de leader, pas de transaction distribuée,
**aucune dépendance de `api` vers `mutex`** (seuls les contrats de `garganttua-commons` sont
touchés — `IMutex`, `IMutexManager`, `MutexName`, `MutexStrategy` ; c'est vous qui apportez
l'implémentation), pas de synchronisation des lectures, et **aucun changement de défaut** : sans
politique déclarée, le script généré est caractère pour caractère celui d'aujourd'hui. Un test le
verrouille.

### Le point où nous ne vous avons pas suivis

Verrou impossible à prendre dans le délai : **409**, pas de dégradation. Un verrou qui abandonne en
silence n'est pas un verrou — la perte d'écriture qu'il devait empêcher revient, sans trace, et
c'est exactement la panne muette que votre fiche décrit. Nous distinguons donc deux échecs : le
verrou **irrésoluble** (problème de déploiement → `WARN`, on continue, le domaine ne tombe pas) et
le verrou **non obtenu** (contention → 409, le client rejoue).

### Vos trois obstacles, relus dans le code

**1. « `stage(name)` construit toujours un stage neuf »** — vrai, mais ce n'est pas bloquant.
`DomainWorkflowAssembler` construit le pipeline **par le DSL workflow** (`builder.stage(label)
.script("classpath:…")`), donc `.wrap(...)` est à notre portée depuis l'intérieur. Votre obstacle
bloque un tiers qui viendrait de l'extérieur ; il ne bloquait pas la plateforme. **Aucune
modification de core n'a été nécessaire.**

**2. « il faut alimenter le `MutexContext` dans le pipeline »** — inutile, et ce n'aurait pas suffi.
Le contexte de domaine est déjà passé à tout script métier (`@2`) : le gestionnaire voyage par là,
pas par un `ThreadLocal` dans un pool de threads HTTP — votre réserve là-dessus était juste.

**3. « `wrap("sync(...)")` fait déjà exactement ce qu'il faut »** — non, et c'est le piège qui vous
aurait coûté une journée. Nous y sommes tombés en premier, et il y a un test pour ça maintenant.

### Le piège, en détail, parce qu'il vous attendait

`IWorkflowStageBuilder.wrap(expr)` est une **substitution textuelle** : `ScriptGenerator` remplace
`@0` par le contenu du stage entre parenthèses. Ce groupe arrive à la fonction enveloppante sous
forme de **`StatementBlock` — un bloc encore à exécuter**, pas une valeur évaluée. C'est la forme
que `ControlFlowFunctions.if` exécute explicitement.

Or `MutexFunctions.sync` prend un `ISupplier`. Branché en enveloppe de stage, il **prendrait le
verrou et n'exécuterait rien** : le stage ne ferait silencieusement aucun travail, et l'écriture
disparaîtrait. Idem pour `syncRedis`.

Nous ne pouvons pas le corriger sur place : `StatementBlock` vit dans `garganttua-script`, qui
dépend de `mutex` — l'inverse fermerait un cycle. Les deux fonctions portent désormais un
avertissement à cet endroit précis de leur javadoc. L'api embarque sa propre enveloppe,
`synchronizeWrite`, qui exécute le bloc.

Deuxième chausse-trappe du même mécanisme, trouvée en testant : **la garde du stage est à
l'intérieur du bloc**. Une enveloppe tourne donc sur *toute* requête, quelle que soit l'opération —
une simple lecture prenait les quatre clés des quatre étages d'écriture avant de ne rien faire sous
chacune. L'étage nomme maintenant l'opération qu'il sert, et le verrou n'est pris que si la requête
en est une.

`wrap` n'avait **aucun test ni aucun appelant** dans tout le dépôt. Il en a deux maintenant
(`WorkflowWrapScopeTest`), qui fixent ce que l'enveloppe reçoit et ce qui survit à l'enveloppement —
la sortie déclarée de l'étage et son code de sortie, dont dépend la garde de tous les étages
suivants.

### Ce qui est vérifié

`WriteSynchronizationIntegrationTest`, 15 tests. Les deux qui comptent pour vous :

- quatre écritures concurrentes sur **la même** entité : chevauchement observé **1** — jamais deux
  à la fois, donc plus de lecture-fusion-écriture entrelacée ;
- quatre écritures concurrentes sur des entités **différentes** : chevauchement **> 1** — la clé
  narrow vraiment, la plateforme n'est pas sérialisée.

Plus : la clé exacte par opération, aucun verrou sur les lectures, aucun verrou sans politique, le
bail transmis à `acquire`, le 409 sur refus avec **rien d'écrit**, la dégradation sur verrou
irrésoluble, et un échec métier qui garde son propre code (404) au lieu d'être déguisé en conflit
de verrou.

### Les cas d'usage

Sur opt-in, pas d'office : le socle ne peut pas savoir si un cas d'usage écrit, et envelopper celui
qui ne fait que lire le sérialiserait pour rien. Vous les nommez :

```java
SynchronizationPolicy.of(manager, IClass.getClass(RedisMutex.class))
        .withUseCases(Set.of("consumeQuota"))
```

C'est le geste dont votre `QuotaEnforcer` a besoin.

### Ce qui reste vrai et que vous devez savoir

La clé porte l'uuid **quand la requête désigne l'entité par uuid** — le cas de vos routes HTTP. Une
recherche par un autre critère retombe sur la clé par tenant : plus grossier, jamais faux. Et
l'option B (verrouillage optimiste) n'est pas implémentée : vous aviez raison qu'elle ne règle pas
les invariants qui se calculent avant d'écrire, et A les règle.
