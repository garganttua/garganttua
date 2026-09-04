# Bogue — `.authorities(champ)` sur un authenticator est décoratif

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-24
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** faible en effet, élevée en malentendu — le DSL décrit un comportement qui n'existe pas.

## Symptôme

`.authenticator().authorities("authorities")` donne à lire que le framework prendra les
autorités **sur le champ nommé de l'entité authenticator**. Il n'en fait rien :
`IAuthenticatorDefinition.authorities()` n'est relu nulle part, ni dans le chemin de frappe
du jeton, ni dans celui de la vérification.

Les autorités portées par l'autorisation viennent **uniquement** de l'objet `IAuthentication`
rendu par la méthode `authenticate` de la stratégie
(`SecurityExpressionsSupport.populateAuthorizationEntityFields`, `authResult.authorities()`).

## Conséquence

Un consommateur qui déclare `.authorities(...)` et dont la stratégie ne renseigne pas la
liste obtient des jetons **sans aucune autorité**, sans erreur ni avertissement — et
cherchera du côté du champ, du mapping ou du DTO. Symétriquement, celui qui croit sécuriser
en nommant un champ contrôlé côté entité se trompe : c'est le code de la stratégie qui
décide, et lui seul.

## Attendu

Au choix, et l'un des deux suffit :

1. **Honorer la déclaration** — lire le champ nommé quand la stratégie ne fournit pas de
   liste, ce qui rendrait le DSL vrai ;
2. **Retirer la méthode**, ou la documenter explicitement comme métadonnée sans effet.

## Trace

Relevé pendant l'introduction de l'authentification machine-à-machine (palliad, 2026-08-24).
Vérifié par recherche du lecteur de `IAuthenticatorDefinition.authorities()` dans
`api/core` et `api/commons` : aucun.

---

## Réponse de la plateforme — 2026-09-04

**Traitée, par l'option 1** — la déclaration est honorée. Corrigée sur `main`, à paraître dans
`3.0.0-ALPHA17`.

La vérification de la fiche était bonne : aucun lecteur de `IAuthenticatorDefinition.authorities()`
dans `api/core` ni `api/commons`. Le DSL décrivait un comportement qui n'existait pas.

Entre « honorer » et « retirer », honorer a été retenu parce que la phrase que le DSL donne à lire
est utile : elle décrit le cas ordinaire, où les autorités d'un principal vivent sur un champ de son
entité, et où la stratégie n'a pas à s'en occuper.

### La règle exacte

Le champ déclaré est une **retombée**, pas une source concurrente :

| Ce que rend la stratégie | Ce qui est frappé dans le jeton |
|---|---|
| une liste (même vide) | cette liste — une liste vide est un choix, elle n'accorde rien |
| `null` | le champ déclaré par `.authorities(...)` de l'entité authenticator |
| `null`, et aucun `.authorities(...)` déclaré | rien — comportement inchangé |

La distinction `null` / liste vide reprend celle que `IAuthentication.reconcile` tient déjà
(« `null` = non résolu, vide = autoritatif »). Sans elle, une stratégie qui refuse délibérément
toute autorité se verrait réattribuer celles de l'entité.

Un consommateur dont la stratégie renseigne la liste ne change donc pas de comportement.

### Un point que la fiche ne demandait pas et qui a été ajouté

Un `.authorities("champ")` désignant un champ illisible sur l'entité lève désormais une
`ApiException` qui **nomme la déclaration** :

```
The authenticator declares .authorities("roles") but that field cannot be read on
com.exemple.Account — check the field name, or have the authentication strategy return
the authorities itself.
```

C'était l'autre moitié du malentendu : sans cela, une déclaration honorée mais fausse redevenait un
jeton silencieusement vide.

La javadoc de `IAuthenticatorBuilder.authorities` porte la règle.

**Couvert par :** `AuthenticatorAuthoritiesTest` (5 tests).
