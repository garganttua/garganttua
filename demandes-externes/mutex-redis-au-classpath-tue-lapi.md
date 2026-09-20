# `garganttua-mutex-redis` au classpath tue l'API au démarrage — deux `syncRedis` sous un seul nom

**À l'attention de :** garganttua-api / garganttua-core (binding `mutex-redis`)
**Émis par :** palliad (v3, AOT pur, multi-tenant, MongoDB)
**Date :** 2026-09-19
**Version constatée :** `3.0.0-ALPHA20` — défaut PRÉEXISTANT, présent à l'identique en `ALPHA19`
**Se rapporte à :** [synchronisation-multi-instances-absente-de-lapi](synchronisation-multi-instances-absente-de-lapi.md)
**Gravité :** bloquante, et MUETTE pour une sonde de disponibilité. L'API ne monte pas, le serveur
web continue de servir — un `GET /` rend 200 avec la coquille SPA pendant que toutes les routes
d'API sont mortes.
**Statut de la constatation :** **MESURÉ**, avec témoin.

## Symptôme

Ajouter `com.garganttua.core:garganttua-mutex-redis` au classpath d'une application d'API suffit à
empêcher l'API de démarrer :

```
ERROR care.palliad.backend.PalliadBackend - Auth API failed to start — the web server keeps serving
com.garganttua.core.reflection.ReflectionException: Multiple overloads of method syncRedis in
    ownertype com.garganttua.core.mutex.redis.functions.RedisMutexFunctions match the exact signature
```

Ce qu'on observe ensuite, et qui est le vrai danger :

| Requête | Attendu | Obtenu |
|---|---|---|
| `GET /patients` (`Accept: application/json`) | 401, `application/json` | **200, `text/html`** — la coquille SPA |
| `PATCH /locations/<uuid>` | 200 ou 404 métier | **404 « Endpoint PATCH /locations/… not found »** |
| `GET /` | 200 | 200 — *l'instance paraît saine* |

## Témoin

Le défaut ne tient pas à l'usage qu'on fait du jar : **il suffit qu'il soit là**.

| Classpath | API montée ? |
|---|---|
| distribution seule | **oui** (401 `application/json`) |
| + `garganttua-mutex` (local) | **oui** |
| + `garganttua-mutex-redis`, **sans** politique de synchronisation déclarée, sans module tiers | **NON** — erreur ci-dessus |
| + `garganttua-mutex-redis` dont la seule classe `RedisMutexFunctions` a été retirée (et ses entrées d'index) | **oui** |

La dernière ligne isole la cause à une classe.

## Cause

`core/bindings/mutex-redis/src/main/java/com/garganttua/core/mutex/redis/functions/RedisMutexFunctions.java`
déclare **deux surcharges** de même arité sous un seul nom d'expression :

```java
@Expression(name = "syncRedis", …)
public static Object syncRedis(@Nullable String mutexName, @Nullable ISupplier<?> expression)  // :95
…
public static Object syncRedis(@Nullable Object mutexName, @Nullable ISupplier<?> expression)  // :162
```

L'index AOT du jar publié les porte toutes les deux :

```
META-INF/garganttua/index/com.garganttua.core.expression.annotations.Expression
  M:com.garganttua.core.mutex.redis.functions.RedisMutexFunctions#syncRedis(String,ISupplier)
  M:com.garganttua.core.mutex.redis.functions.RedisMutexFunctions#syncRedis(Object,ISupplier)
```

La résolution par signature exacte en trouve deux et lève. Le pendant local,
`MutexFunctions.sync`, n'a qu'une surcharge (`Object`) — et ne pose pas le problème, ce qui explique
que personne ne l'ait rencontré : jusqu'à `ALPHA20`, aucune raison de mettre le binding Redis sur le
classpath d'une API.

## Attendu

Un nom d'expression désigne UNE méthode. Au choix :

1. supprimer la surcharge `String`, que la surcharge `Object` couvre déjà ;
2. ou lui donner un autre nom d'expression ;
3. ou, si les deux doivent coexister, que la résolution choisisse la plus spécifique au lieu de lever.

Et, indépendamment du correctif : **qu'un nom d'expression en double soit refusé au moment de
l'indexation**, pas à l'exécution — le processeur AOT les voit toutes les deux, il peut le dire à la
compilation du binding plutôt qu'au démarrage de l'application de l'utilisateur.

## Ce que cette fiche ne demande pas

- **Pas de changement du comportement de repli.** Que le serveur web continue de servir quand l'API
  échoue est un choix de palliad, pas du socle ; nous n'en demandons pas la modification ici.
- Pas de retrait de `RedisMutexFunctions` : nous ne nous en servons pas, mais d'autres si —
  c'est la forme `syncRedis(nom, bloc)` dans un script.

---

## Réponse de la plateforme — 2026-09-20

**Traitée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA21`.

### Votre diagnostic est exact, ligne pour ligne

Deux `@Expression(name = "syncRedis")` de même arité sur `RedisMutexFunctions`, l'une sur `String`
(`:95`), l'autre sur `Object` (`:162`). La seconde ne fait que convertir et déléguer à la première.
Votre tableau de témoins isole la cause à la classe ; nous n'avons rien trouvé à corriger dans votre
lecture.

Nous avons retenu **votre option 1** : la variante `String` perd son annotation et devient la mise
en œuvre privée (`syncRedisNamed`) ; la variante `Object` reste la seule à porter le nom
d'expression, comme `MutexFunctions.sync` le fait déjà côté local. Aucune signature publique de
script ne change : `syncRedis("nom", bloc)` continue de résoudre, par la surcharge `Object`.

Nous n'avons pas retenu l'option 3 (résolution de la plus spécifique) : elle aurait fait d'un
doublon une ambiguïté tranchée en silence, et le prochain doublon serait passé inaperçu au lieu
d'être corrigé.

### Votre seconde demande, à moitié tenue — et nous disons laquelle

Vous demandiez qu'un nom d'expression en double soit **refusé à l'indexation**, pas à l'exécution.

Ce que nous avons fait : **refusé à l'enregistrement**, sur les **trois** chemins qui enregistrent
des fonctions — les classes de la plateforme, celles qu'une application contribue par le SPI, et
celles que la détection par paquet trouve. Le message **nomme la paire fautive et sa provenance**,
au lieu de l'ambiguïté de réflexion qui ne nommait ni l'expression ni le module.

Le critère n'est pas « même nom, même arité » : nous avons écrit cette règle-là d'abord, et elle
condamnait `Beans.bean(Optional, BeanReference)` face à `Beans.bean(IClass, String)` — une fonction
qui résout parfaitement et que vous utilisez sans le savoir. Le critère est celui sur lequel le
résolveur bute réellement : **une surcharge en masque une autre**, ses paramètres acceptant ceux de
l'autre à chaque position, si bien qu'un appel écrit pour la plus précise correspond aux deux.
`syncRedis(String, …)` face à `syncRedis(Object, …)` est exactement ça.

Une asymétrie assumée, parce qu'elle n'est pas la même faute des deux côtés :

| Qui déclare la classe | Effet |
|---|---|
| la **plateforme** (`FRAMEWORK_FUNCTION_CLASSES`, dont `RedisMutexFunctions`) | la construction du contexte échoue — livrer un nom inutilisable est un bogue à corriger avant publication |
| une **application**, via le SPI contributeur | un `WARN` nommant la paire ; l'isolation par classe existante est conservée, une classe tierce fautive ne doit pas faire tomber le contexte entier |

Ce que nous **n'avons pas** fait : la vérification à la compilation. Elle vit dans
`garganttua-annotation-processor`, qui est **hors du réacteur** (pré-installé séparément) ; l'y
ajouter est un chantier à part, pas un ajout à ce correctif. Nous préférons vous le dire que de
laisser croire que le contrôle est en amont. Si le cas se reproduit, il sera attrapé au démarrage du
**module fautif**, avec un message exploitable — pas au premier appel chez son consommateur.

**Couvert par :** `AmbiguousExpressionNameTest` (4 tests) — le doublon refusé en nommant la paire,
et les trois cas légitimes qui doivent continuer de passer : arités différentes sous un même nom,
noms distincts sur des méthodes de même arité, et — celui qui nous a corrigés — même nom et même
arité mais types de paramètres sans rapport.

### Sur le repli et le retrait

Nous ne touchons pas au comportement de repli, comme vous le demandiez : que votre serveur web
continue de servir quand l'API échoue reste votre choix. Et `RedisMutexFunctions` n'est pas retirée :
`syncRedis(nom, bloc)` reste disponible dans un script. Un avertissement a été ajouté à son javadoc
sur un autre point, sans rapport avec cette fiche mais qui vous concerne si vous l'utilisez un jour :
branchée en enveloppe de stage de workflow, elle prendrait le verrou sans exécuter le bloc.

### Ce que le contrôle a trouvé chez nous dès qu'on l'a branché

Il a refusé le build. Pas sur `syncRedis` — celui-là était déjà corrigé — mais sur **deux
`@Expression(name = "setRequestArg")` de signature identique**, dans deux classes de l'api :
`SecurityAuthorizationExpressions` et `SerializationExpressions`. Elles ne faisaient pas la même
chose :

| | variante Security | variante Serialization |
|---|---|---|
| requête ou clé nulle | rend `false`, en silence | lève une `ApiException` |
| argument enveloppé dans un `Optional` | cast brut → `ClassCastException` | déballe requête, clé et valeur |
| retour | `true` | la valeur |

Le registre étant indexé par nom, **laquelle gagnait dépendait de l'ordre de scan**. Et
`setRequestArg` est appelée une vingtaine de fois dans les scripts du pipeline, dont plusieurs avec
des arguments que la pipeline produit sous `Optional` — `authResultPrincipal(...)` et `@_caller`
dans `VERIFY_AUTHORIZATION.gs`. Si la variante Security l'avait emporté, la vérification
d'autorisation cassait.

La variante Security est supprimée : personne ne l'appelait depuis Java, et c'est la sémantique de
l'autre dont les scripts dépendent.

Nous vous le rapportons parce que c'est votre fiche qui l'a fait sortir. Une demande sur un binding
que vous n'utilisez même pas a mis au jour un défaut latent au cœur du pipeline de sécurité — et
nous ne l'aurions pas trouvé sans elle.
