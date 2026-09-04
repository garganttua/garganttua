# Bogue — `OperationRequest.caller()` reconstruit l'appelant au lieu d'utiliser celui que la vérification a réconcilié

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-24
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** moyenne — conduit un consommateur à écrire un contrôle d'autorisation qui a l'air de fonctionner et qui ne protège rien.

## Symptôme

Un cas d'usage qui reçoit `@Caller ICaller` et compare `caller.tenantId()` à une valeur du
corps de la requête compare en réalité l'appelant à **l'en-tête `X-Tenant-Id` que l'appelant
a lui-même choisi**, et non à l'équipe portée par son jeton.

Reproduit sur palliad : le même administrateur maître est refusé avec `X-Tenant-Id: 0`, et
accepté en annonçant `X-Tenant-Id: <équipe>` — sur un contrôle dont l'intention écrite était
« on n'émet un jeton d'enrôlement que pour sa propre équipe ».

## Cause

`api/core/src/main/java/com/garganttua/api/core/service/OperationRequest.java:51-61`

```java
@Override
public ICaller caller() {
    return new Caller(
            arg(TENANT_ID).orElse(null),
            arg(REQUESTED_TENANT_ID).orElse(null),
            ...
            (List<String>) arg(AUTHORITIES).orElse(null));
}
```

L'appelant est **reconstruit** à partir des arguments de la requête. Or l'étape de
vérification publie déjà un appelant réconcilié — `VERIFY_AUTHORIZATION.gs` le pose dans le
contexte après avoir relu l'autorisation stockée. Les deux ne coïncident pas : le premier
porte ce que le client a envoyé, le second ce que le serveur a vérifié.

## Conséquence

Tout contrôle d'appartenance écrit dans un cas d'usage à partir de `@Caller` est décoratif
tant que la valeur comparée vient du même endroit que celle qu'on veut contrôler. Le
consommateur ne peut pas le deviner : `ICaller` est précisément le type qu'on lui donne pour
savoir « qui appelle ».

## Attendu

`caller()` rend l'appelant réconcilié par la vérification quand il existe (argument
`"caller"` ou équivalent publié par `VERIFY_AUTHORIZATION.gs`), et ne retombe sur la
reconstruction que pour les opérations réellement anonymes.

## Contournement chez le consommateur

Aucun de propre. On peut relire l'autorisation stockée soi-même, mais c'est refaire le
travail de l'étape de vérification à l'intérieur du cas d'usage.

## Trace

Constaté pendant l'introduction de l'authentification machine-à-machine des serveurs
on-prem (palliad, 2026-08-24), sur `EnrolmentUseCases.issueEnrolmentToken` et
`EnrolmentUseCases.revoke`.

---

## Réponse de la plateforme — 2026-09-04

**Traitée, exactement comme demandée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA17`.

Le diagnostic était juste, et le correctif tient en trois lignes — parce que **la moitié du travail
était déjà faite**. `VERIFY_AUTHORIZATION.gs:83` publie l'appelant réconcilié depuis toujours :

```
_caller <- reconcileCaller(@_authResult, :arg(@0, "caller"), @0, @2, @3)
setRequestArg(@0, "caller", @_caller)
```

`caller()` ne le lisait simplement pas. Il rebâtissait un `Caller` à partir de `TENANT_ID`,
`CALLER_ID`, `OWNER_ID` — les valeurs de la couche protocole, c'est-à-dire les en-têtes. Il préfère
désormais l'appelant réconcilié et ne retombe sur la reconstruction que pour une opération qui n'est
jamais passée par la vérification : un appel réellement anonyme, une invocation interne du cadre, un
harnais de test.

### Plus large que ce que la fiche mesure

Vous décrivez `@Caller` dans un cas d'usage. **Cinq** fournisseurs passent par `request.caller()` —
`CallerSupplier` (celui de `@Caller`), `TenantSupplier`, `LoginSupplier`, `AuthoritiesSupplier`,
`OwnerIdSupplier`. Un seul correctif les redresse tous les cinq ; tout contrôle écrit à partir de
l'un d'eux était décoratif de la même façon.

Les deux implémentations d'`IOperationRequest` étaient touchées (`OperationRequest` côté core,
`MapBackedOperationRequest` côté commons via `MapBackedCaller`) ; les deux sont corrigées.

### Ce qui est ajouté à l'API

Une seule chose, additive : `IOperationRequest.CALLER`, la clé typée sous laquelle l'appelant
vérifié voyage. Elle existait en chaîne nue (`"caller"`), redéclarée en privé là où on en avait
besoin. Sa javadoc dit ce que les autres clés sont vraiment — les valeurs du protocole, la
prétention du client — pour que le prochain lecteur ne refasse pas l'erreur.

### Ce que votre code va voir changer

`caller().tenantId()` rend maintenant l'équipe **du jeton**. Si un endroit de palliad lisait cette
valeur en attendant l'équipe **demandée** par l'en-tête, elle est toujours là, séparément :
`caller().requestedTenantId()`. C'est ce que `reconcile` distingue déjà — l'appelant réconcilié
porte les deux, ce qui change est lequel des deux est l'identité.

Le contrôle d'`EnrolmentUseCases.issueEnrolmentToken` que la fiche cite devrait donc protéger ce
qu'il dit protéger. À vérifier chez vous : c'est le genre de correctif dont l'effet ne se voit qu'en
rejouant le scénario qui l'a révélé — l'administrateur maître refusé avec `X-Tenant-Id: 0` et
accepté en annonçant l'équipe.

**Couvert par :** `ReconciledCallerTest` (4 tests).
