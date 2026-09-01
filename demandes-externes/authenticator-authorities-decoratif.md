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
