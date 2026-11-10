# En natif, deux méthodes `invokeAuthenticate` empêchent l'API de monter

**À l'attention de :** garganttua-api (`core`), garganttua-core (`reflection`, `expression`)
**Émis par :** palliad (v3, AOT pur, binaire natif GraalVM 25 G1 + PGO)
**Date :** 2026-11-10
**Version constatée :** garganttua `3.0.0-ALPHA34`, construite en local sur `fcbef1e`
**Gravité :** BLOQUANTE pour toute application native. L'authentification ne monte pas, donc **toutes** les routes
d'API répondent 404, connexion comprise. Les pages statiques, elles, rendent 200 : le serveur a l'air vivant. Rien ne se
voit en JVM, où toute la suite de tests est verte. Le défaut n'existait pas en `3.0.0-ALPHA33`.

## Symptôme

**Mesuré.** Palliad 1.4.74, seul changement de cadre ALPHA33 → ALPHA34, construit avec `clean`, natif G1 + PGO :

- démarrage : `Auth API failed to start — the web server keeps serving`, levée de
  `com.garganttua.core.reflection.ReflectionException: Multiple overloads of method invokeAuthenticate in ownertype
  com.garganttua.api.core.expression.SecurityAuthenticationExpressions match the exact signature` ;
- pile :
  ```
  at com.garganttua.core.reflection.methods.MethodResolver.methodByMethod(MethodResolver.java:150)
  at com.garganttua.core.reflection.binders.dsl.AbstractMethodBinderBuilder.method(AbstractMethodBinderBuilder.java:147)
  at com.garganttua.core.expression.dsl.ExpressionContextBuilder.lambda$doAutoDetection$1(ExpressionContextBuilder.java:388)
  at com.garganttua.core.expression.dsl.ExpressionContextBuilder.doAutoDetection(ExpressionContextBuilder.java:381)
  at com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder.build(AbstractAutomaticDependentBuilder.java:129)
  at com.garganttua.core.bootstrap.dsl.Bootstrap.buildAndRegister(Bootstrap.java:711)
  …
  at com.garganttua.core.application.GarganttuaApplication.run(GarganttuaApplication.java:77)
  ```
- ensuite : `POST /users/authenticate` → 404, toute route d'API → 404 (« Endpoint … not found »), `GET /` → 200.

Le même code sur ALPHA33 démarre et sert l'API en natif (palliad 1.4.73 est en production sur ce binaire).
Sur ALPHA34 en JVM, l'API monte et toute la suite de palliad passe.

## Cause

**Lu** dans les sources, non instrumenté.

1. ALPHA34 ajoute dans `api/core/src/main/java/com/garganttua/api/core/expression/SecurityAuthenticationExpressions.java`
   une seconde méthode du même nom que l'expression :
   - l. 428-440 : `@Expression(name = "invokeAuthenticate") public static IAuthentication invokeAuthenticate(Object
     apiContext, Object targetDomain, Object authRequest, Object tenantId)` ;
   - l. 446 : `static IAuthentication invokeAuthenticate(IDomain<?> domain, IAuthenticationRequest req, String tenant,
     KnownPrincipal knownPrincipal)`, sans annotation, appelée par la première et par `TokenSelfVerification` (l. 93).

   Même nom, même nombre de paramètres : quatre.
2. La détection automatique des expressions (`core/expression/.../ExpressionContextBuilder.java`, l. 381-388) passe
   l'`IMethod` de l'expression à `AbstractMethodBinderBuilder.method(...)`, qui la résout à nouveau par
   `MethodResolver.methodByMethod` (`core/reflection/.../MethodResolver.java`, l. 132-157). Cette résolution liste les
   méthodes du nom (`query.addresses(method.getName())`), puis filtre celles dont le type de retour et les types de
   paramètres « correspondent » (`ResolvedMethod.matches(...)`) ; plus d'une correspondance lève l'exception.
3. En JVM, seule l'expression correspond. En natif, les deux correspondent. **Hypothèse, non vérifiée** : sous l'image
   fermée, les types de paramètres de la méthode non publique ne sont pas résolus comme en JVM (descripteur AOT absent
   ou partiel), et `matches` les accepte.

Le scanner des surcharges de palliad, passé sur tout le dépôt garganttua, ne trouve pas d'autre surcharge NOUVELLE
d'une expression en ALPHA34. Celles de même nombre de paramètres qui existent déjà (`ScriptTimingFunctions.hours`,
`minutes`, `seconds`, les conditions `greater`/`lower`…) existaient en ALPHA33 et n'empêchaient pas le démarrage natif.

## Attendu

- Une application native démarre et sert son API en ALPHA34, comme en ALPHA33.
- Plus largement : la détection automatique d'une expression lie la méthode annotée elle-même, et ne la confond pas
  avec une autre méthode du même nom de la même classe, quelle que soit sa visibilité. Une méthode non annotée ne
  devrait pas pouvoir rendre une expression ambiguë.
- Un test qui le garde EN NATIF : le défaut est invisible en JVM, où la suite de palliad comme celle du cadre est
  verte.

## Ce que la fiche ne demande pas

- Elle ne demande pas de choix de correctif particulier : renommer la méthode interne, changer la résolution par
  signature, ou enregistrer autrement les descripteurs AOT. C'est à l'équipe du cadre de trancher.
- Elle ne demande pas de modifier le comportement en JVM.
- Palliad ne contourne pas le défaut de son côté : aucun contournement n'est possible depuis le consommateur, la
  méthode fautive est interne au cadre.

## Reproduire

1. Une application garganttua v3 avec authentification (un domaine authentificateur), construite en natif avec le
   processeur AOT, sur ALPHA34.
2. Démarrer le binaire : chercher `Auth API failed to start` et `Multiple overloads of method invokeAuthenticate` au
   journal.
3. `POST /users/authenticate` (ou la route d'authentification du domaine) : 404.

Chez palliad : `./mvnw -Pprod,native -DskipTests -pl backend/palliad-backend-cloud -am clean package`, binaire
`backend/palliad-backend-cloud/target/palliad` sur une base jetable.

## Réponse de la plateforme — 2026-11-10

**Corrigée, et reproduite d'abord sur votre binaire.** Sur la branche `perf/token-verification`, et dans l'ALPHA34
installée en local (`~/.m2`), que vous pouvez reconstruire telle quelle.

### Reproduit, puis vérifié

Nous avons construit votre binaire depuis votre dernier commit, dans un worktree à part (votre copie de travail n'a
pas été touchée), avec votre commande `./mvnw -Pprod,native -DskipTests -pl backend/palliad-backend-cloud -am clean
package` :

- **ALPHA34 avant correction** : `Auth API failed to start`, puis `Multiple overloads of method invokeAuthenticate …
  match the exact signature`. C'est votre constat.
- **ALPHA34 corrigée** : l'erreur a disparu. `GET /` répond 200 et `POST /users/authenticate` répond **401**
  (`All authentication methods failed`, attendu pour de faux identifiants) au lieu de 404.

Notre propre épreuve native (`api/e2e/native-postgres`) passait, elle, sur l'ALPHA34 fautive. Votre binaire expose
donc au moteur des métadonnées de réflexion que le nôtre n'expose pas. Nous n'avons pas établi lesquelles :
l'hypothèse de la fiche sur les types de paramètres reste plausible.

### Ce qui est fait

1. **La cause directe est supprimée.** Notre méthode interne s'appelle désormais `authenticateThroughOperation` :
   aucune autre méthode de `SecurityAuthenticationExpressions` ne porte le nom d'une expression.
2. **La résolution ne se laisse plus piéger** (`MethodResolver.methodByMethod`, core).
   - Elle compare les paramètres par **assignabilité** : `foo(Object, Object)` accepte ce que déclare
     `foo(String, String)`.
   - Quand plusieurs méthodes passent ce test, elle garde désormais celle dont la signature est **identique**,
     comparée par noms de types puisque les deux méthodes peuvent venir de fournisseurs de réflexion différents.
   - Une méthode non annotée, publique ou non, ne peut donc plus rendre une expression ambiguë.
   - `MethodResolverTest` le prouve en JVM : sans ce changement, il lève exactement votre exception.
3. **Le garde-fou est à la compilation, pas seulement en natif** (`ExpressionNameClashTest`, api-core). Aucune
   méthode d'une classe d'expressions ne peut porter le nom et le nombre de paramètres d'une expression sans être
   elle-même une expression. Lancé sur l'ancien code, il nomme `SecurityAuthenticationExpressions#invokeAuthenticate/4`.
   C'est la règle que votre scanner a vérifiée sur tout le dépôt, et elle est désormais dans la suite du cadre.

Pour le natif lui-même, notre épreuve ne reproduisant pas votre configuration, le garde-fou de compilation est ce qui
empêche ce défaut de revenir.
