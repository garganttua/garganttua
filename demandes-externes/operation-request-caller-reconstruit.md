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
