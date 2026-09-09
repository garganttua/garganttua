# garganttua-api — une `ApiException` levée par un CAS D'USAGE est rendue `HTTP 200` avec le corps `0`

**À l'attention de :** garganttua-api
**Émis par :** autonom (consommateur v3, AOT pur)
**Date :** 2026-09-08
**Version constatée :** garganttua-api `3.0.0-ALPHA17`
**Gravité :** HAUTE. Ce n'est pas un défaut d'ergonomie : le client lit `200`, donc un SUCCÈS.
L'écran annonce une réussite qui n'a pas eu lieu, et **aucun message de refus n'atteint jamais
l'utilisateur**. Le geste est refusé, rien n'est écrit, et l'interface dit le contraire.
**Nature :** bogue. **MESURÉ** en HTTP, sur une instance réelle.

## Symptôme

Un `contextualUseCase` dont le type de retour déclaré est une entité ou un DTO rend `HTTP 200`
avec le corps `0` — un entier — dès qu'il lève une `ApiException`.

Mesure du 2026-09-08, entrée VALIDE, entité EXISTANTE, refus métier délibéré :

```
POST /invoices/transmit  {"uuid":"<uuid d'une facture au statut DRAFT>"}
  → HTTP 200   content-type: application/json   taille: 1 octet
  → corps : 0
```

Le cas d'usage exécute bien :

```java
throw new ApiException("Cette facture est un brouillon : émettez-la d'abord, "
        + "c'est l'émission qui lui donne son numéro.");
```

Le message n'apparaît nulle part dans la réponse — ni corps, ni en-tête.

La même mécanique rend correctement un DTO quand elle RÉUSSIT, ce qui écarte un défaut de
sérialisation générale :

```
POST /invoice-transmissions/status  {}
  → HTTP 200   608 octets
  → {"available":true,"testMode":true,"platform":"ACUBE","accountId":null,…}
```

Reproduit sur trois autres gestes de deux autres domaines (`missions/close`,
`invoices/mark-paid`, `customers/lookup-siret`) : `200` + `0` à chaque fois.

## Déclaration du cas d'usage

Rien d'exotique — la forme documentée :

```java
contextualUseCase(invoices, "transmit",
        IClass.getClass(InvoiceTransmissionRequest.class),
        IClass.getClass(InvoiceTransmission.class),   // ← type de retour : une ENTITÉ
        transmissionUseCases, "transmit",
        "invoice-update");
```

## Pourquoi cela coûte cher, et pourquoi c'est invisible

`200` est un succès. Un client HTTP normal — ici `HttpClient` d'Angular — ne déclenche donc
**aucun** chemin d'erreur : l'intercepteur d'erreurs ne voit rien, le composant appelle son
`next`, et l'écran affiche ce qu'il affiche après une réussite.

Conséquence pratique, vécue : un utilisateur a cliqué « Transmettre » sur une facture pendant une
demi-journée. Le serveur refusait à chaque fois — d'abord parce qu'une donnée manquait, ensuite pour
une autre raison — et l'écran ne disait **rien**. Le diagnostic n'a été possible qu'en interrogeant
l'API au `curl` et en lisant le journal du serveur.

Le coût est proportionnel au soin mis dans les refus : notre base en compte plusieurs dizaines,
rédigés pour nommer ce qui manque et le geste de remplacement (« émettez-la d'abord », « désactivez-la
plutôt », « 14 journées imputées »). **Aucun** ne parvient à l'utilisateur quand il passe par un cas
d'usage.

Il y a un second effet, plus sournois : trois causes distinctes partagent désormais **un seul
symptôme** (`200` + `0`), et rien ne les distingue côté client —

1. un nom de méthode qui ne se résout pas (déjà documenté chez nous) ;
2. une exception non-`ApiException` levée dans le cas d'usage (déjà documenté chez nous) ;
3. **un refus métier ordinaire** (ceci).

Les deux premières sont des erreurs de programmation qu'on finit par trouver. La troisième est le
fonctionnement NORMAL de l'application, et elle produit le même silence.

## Ce que nous demandons

Une seule chose, au choix de l'équipe :

1. **Rendre une `ApiException` en `4xx` avec un corps qui porte son message** — la forme
   `{"error": "…"}` déjà servie ailleurs par le socle conviendrait, et nos écrans la lisent déjà.
   `422` nous semble juste pour un refus métier ; `400` irait aussi. **C'est ce que nous préférons.**
2. À défaut, **documenter** que le corps `0` signifie « refusé » et exposer le message autrement
   (un en-tête, un corps `{"error":…}` en 200). Nous nous y adapterions, mais un `200` sur un geste
   refusé restera un piège pour tout nouveau consommateur.

Nous n'avons **pas** de préférence sur le code exact, seulement sur le fait qu'il soit dans la
famille `4xx` : un refus rendu en `2xx` est indistinguable d'un succès pour toute bibliothèque HTTP,
et un refus rendu en `5xx` serait rejoué en boucle par les clients qui réessaient (nous avons déjà
traité ce cas dans notre façade publique).

## Contournement en place chez nous

Un intercepteur convertit en échec toute réponse `POST` dont le corps est un **nombre** — aucun de
nos cas d'usage n'en rend un. L'écran cesse ainsi d'annoncer une réussite qui n'a pas eu lieu.

**Le motif reste perdu** : nous ne pouvons afficher que « le serveur a refusé ce geste sans en
donner la raison ; rien n'a été enregistré ». C'est mieux que le silence, et très loin du message
que le cas d'usage avait rédigé.

## Ce que nous n'affirmons pas

Nous n'avons pas lu le code du socle : nous ne savons pas si l'exception est avalée dans l'invocation
du cas d'usage, dans la liaison Jackson, ou dans la couche HTTP. Le `0` ressemble à une valeur de
retour par défaut, mais c'est une supposition — la seule chose que nous ayons mesurée est ce que
l'API rend.

---

## Réponse de la plateforme — 2026-09-09

**Traitée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA19`.

### Votre supposition était juste

Vous écriviez, en précisant que vous n'aviez pas lu le socle : *« Le `0` ressemble à une valeur de
retour par défaut, mais c'est une supposition »*. C'est exactement ça, et voici le mécanisme.

`MethodInvoker.invokeMethodSafely` **capture** ce que la méthode a levé au lieu de le relancer :

```java
} catch (InvocationTargetException e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    return new SingleMethodReturn<>(cause, returnType);   // ← stocké, pas levé
}
```

`UseCaseExpressions.invokeUseCase` lisait ensuite le résultat avec `single()`, qui rend la
*valeur* — donc `null`. L'exception ne remontait jamais, le
`! => recordCaughtException(@0, @exception) -> 500` de `USE_CASE.gs` ne se déclenchait pas, et le
workflow sortait avec son code de succès. **Le `0` que vous voyez est ce code de sortie servi comme
charge utile.** Vos trois causes distinctes partageaient bien un seul symptôme, pour cette raison.

### Ce qui a été fait

Un point de lecture unique, `ExpressionUtils.singleOrThrow`, qui fait remonter l'exception capturée.
Une `RuntimeException` passe **verbatim** — votre message arrive intact ; le reste est enveloppé.

### Sur le statut : nous n'avons pas retenu votre demande 1, et voici pourquoi

Vous demandiez un `4xx` par défaut. Une `ApiException` **nue** continue de rendre **500**, par
cohérence avec la règle posée en ALPHA17 pour les crochets : une `ApiException` sans statut choisi
est une panne, et c'est le bon défaut pour un échec imprévu.

Ce qui change pour vous : **le message arrive**, et le `4xx` est à un mot :

```java
throw ApiException.badRequest("Cette facture est un brouillon : émettez-la d'abord.");
// -> HTTP 400, corps {"error":"Cette facture est un brouillon : émettez-la d'abord."}
```

`badRequest`, `unauthorized`, `forbidden`, `notFound`, `notAcceptable`, `conflict`, et
`ApiException.of(code, message)` pour le reste. Votre argument sur le rejeu du `5xx` est juste et
nous le prenons au sérieux — c'est précisément pourquoi ces fabriques existent depuis ALPHA17.

**Le geste chez vous** : remplacer `new ApiException(msg)` par `ApiException.badRequest(msg)` sur vos
refus métier. Vous en comptez « plusieurs dizaines » ; c'est un remplacement mécanique, et le
comportement intermédiaire (500 + message) est déjà meilleur que le silence actuel.

Si ce compromis ne tient pas à l'usage, dites-le : router les cas d'usage vers 400 par défaut reste
techniquement possible, c'est une ligne de `USE_CASE.gs`.

### Ce que votre fiche a permis de trouver, et que personne n'avait signalé

En cherchant la cause, nous avons compté les endroits où le socle invoque du code consommateur à
travers un binder : **cinq, dont quatre lisaient le résultat sans tester l'exception**. Le vôtre
était l'un d'eux. Les trois autres :

| Site | Effet |
|---|---|
| émission d'autorisation personnalisée | message trompeur, cause perdue |
| `reconcile` personnalisé | idem — et c'est ce qui décide de l'identité de l'appelant |
| **`applySecurityOnEntity`** | **l'entité était persistée NON sécurisée** |

Le dernier est grave et sans rapport avec votre fiche : le code lisait
`secured != null ? secured : entity`, donc une méthode de sécurisation qui échouait rendait
l'entité **intacte**, qui partait en base. Une méthode chargée de hacher un mot de passe et qui
échoue faisait donc enregistrer ce mot de passe **en clair**, sans une ligne de journal. Un test
verrouille désormais qu'une écriture dont la sécurisation échoue **ne persiste rien**.

Les cinq sites passent maintenant par le même point de lecture.

### Sur votre contournement

L'intercepteur qui convertit en échec toute réponse dont le corps est un nombre n'a plus lieu
d'être une fois la version passée, et il redevient sans danger : un cas d'usage qui refuse rend
maintenant un statut d'échec. Nous vous suggérons de ne le retirer qu'après avoir vérifié un refus
de bout en bout chez vous.

**Couvert par :** `UseCaseRefusalIntegrationTest` (5 tests) et `ApplySecurityFailureIntegrationTest`
(3 tests).
