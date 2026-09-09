# Bogue — `attemptAuthentication` avale les exceptions des stratégies d'authentification

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-24
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** moyenne — transforme toute faute de câblage en « identifiants invalides », sans trace.

## Symptôme

Une stratégie d'authentification qui lève — champ mal nommé, binder AOT absent, dépendance
non injectée, cast impossible — produit exactement la même réponse qu'un mot de passe faux :
un 401 générique. **Aucune ligne de journal**, aucune distinction possible entre « cet
utilisateur s'est trompé » et « cette API est mal montée ».

## Cause

`api/core/src/main/java/com/garganttua/api/core/expression/SecurityAuthenticationExpressions.java:277-304`

```java
private static IAuthentication attemptAuthentication(IAuthenticationDefinition authDef) {
    try {
        ...
    } catch (RuntimeException e) {
        return null;          // ← ni log, ni rethrow, ni marqueur
    }
}
```

Le `catch` se justifie par la cascade : plusieurs `.authentication(...)` peuvent être
déclarées sur un même authenticator, et l'échec de l'une ne doit pas empêcher d'essayer la
suivante. Mais avaler **sans rien dire** rend la cascade indiscernable d'une panne.

## Conséquence

Le coût se paie au premier montage d'une authentification personnalisée : on cherche du côté
des identifiants pendant que le vrai problème est un nom de champ ou un descripteur AOT
manquant. En image native, où les fautes de résolution par nom sont fréquentes, c'est le
scénario le plus probable.

## Attendu

Journaliser l'exception au niveau `warn` ou `debug` avec le nom de la stratégie, avant de
rendre `null`. La cascade continue de fonctionner ; la panne cesse d'être muette.

## Trace

Constaté pendant l'introduction de `SharedSecretAuthentication` (palliad, 2026-08-24).

---

## Réponse de la plateforme — 2026-09-04

**Traitée, telle que demandée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA17`.

Le diagnostic était exact, y compris sur la raison d'être du `catch` : la cascade doit survivre à
l'échec d'une stratégie. C'est l'absence de trace qui était le défaut, pas le rattrapage.

`attemptAuthentication` journalise maintenant en `warn`, avant de rendre `null` : le nom de la
stratégie (sa méthode liée), le type de l'exception, et son message — avec la pile. La cascade
continue de fonctionner à l'identique.

```
WARN  Authentication strategy Account.authenticate(AuthenticationRequest) threw
      ReflectionException — treated as 'not authenticated' and the cascade continues.
      If callers are getting an unexplained 401, this is why: No overload of method ...
```

La ligne dit explicitement pourquoi un 401 inexpliqué peut venir de là : c'est la phrase qui
manquait le jour où vous avez monté `SharedSecretAuthentication`.

Le niveau retenu est `warn` et non `debug` : en image native, comme la fiche le note, une faute de
résolution par nom est le scénario le plus probable, et un `debug` ne serait pas activé le jour où
il servirait.

**Ce qui n'a pas été fait :** ni marqueur sur la réponse, ni distinction du 401 côté client. La
fiche ne le demandait pas, et rendre un statut différent selon qu'une stratégie a planté renseigne
un attaquant sur l'état interne du montage.

---

## Rectification de notre réponse — 2026-09-09

**Notre réponse du 2026-09-04 était trop affirmative.** Nous avons écrit « traitée, telle que
demandée ». Elle ne l'était qu'à moitié, et nous ne l'avons découvert qu'en instruisant une autre
fiche.

### Ce qui n'allait pas

Le correctif ajoutait un `log.warn` dans le `catch (RuntimeException)` d'`attemptAuthentication`.
Mais ce `catch` ne peut voir que ce qui est **levé**, et le binder ne lève pas ce que la stratégie a
levé : `MethodInvoker.invokeMethodSafely` **capture** la cause dans le résultat.

```java
} catch (InvocationTargetException e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    return new SingleMethodReturn<>(cause, returnType);   // ← stocké, pas levé
}
```

`attemptAuthentication` lisait ensuite `result.get().single()`, qui rend la valeur — donc `null` —
puis `returned instanceof IAuthentication` était faux, et la méthode rendait `null`. **En silence.**

Le partage exact, que nous aurions dû faire dès la première réponse :

| Ce qui échoue | Journalisé depuis ALPHA17 ? |
|---|---|
| binder AOT absent, dépendance non injectée, champ mal nommé (erreurs de **fourniture**) | **oui** — elles lèvent une `SupplyException`, le `catch` les voyait |
| **la stratégie elle-même qui lève** (cast impossible, appel qui échoue) | **non** — capturée, jamais vue |

Or votre fiche s'intitule « avale les exceptions des stratégies d'authentification », et la seconde
ligne est celle que le titre désigne. Nous avons corrigé la moitié la moins centrale en annonçant le
tout.

### Ce qui est fait maintenant

`attemptAuthentication` lit son résultat par `ExpressionUtils.singleOrThrow`, le point de lecture
unique introduit pour ce défaut : l'exception capturée est relancée, donc le `catch` la voit, la
journalise avec le nom de la stratégie, et la cascade continue comme avant. Le comportement que
vous demandiez — « journaliser au niveau `warn` avec le nom de la stratégie, avant de rendre `null` »
— vaut désormais pour les deux lignes du tableau.

À paraître dans `3.0.0-ALPHA19`.

### Pourquoi nous vous le disons

Vous auriez pu monter en ALPHA17, remonter une authentification personnalisée, et constater le même
silence qu'avant sur la moitié des cas — en concluant que la fiche avait été mal traitée, sans savoir
laquelle des deux moitiés vous regardiez. C'est le genre de chose qui coûte plus cher à découvrir
qu'à écrire.
