# garganttua-api — `afterGet` ne s'exécute que sur la lecture UNITAIRE, jamais sur la collection

**À l'attention de :** garganttua-api
**Émis par :** autonom (consommateur v3, AOT pur)
**Date :** 2026-09-01 (fiche CORRIGÉE le jour même — voir « Rectification » en fin de page)
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** MOYENNE, et elle tient à l'asymétrie : le seul usage naturel de ce crochet est de
retirer un secret avant qu'il ne parte sur le réseau, et il ne couvre alors qu'une route sur deux —
une demi-protection qu'on croit entière.
**Nature :** question de contrat, peut-être un bogue. **MESURÉ** en HTTP.

## Symptôme

Un crochet `afterGet` s'exécute sur `GET /<domaine>/{uuid}` et **jamais** sur `GET /<domaine>`.

Mesure, même démarrage, même crochet, deux domaines (un domaine métier ordinaire et un domaine
`@Key`), crochet libre `(Object, ICaller)` câblé par
`entity().afterGet(hook).withParam(0, HookEntitySupplierBuilder).withParam(1, CallerSupplierBuilder)` :

```
GET /customers                      → 200   crochet exécuté : 0 fois
GET /customers/{uuid}               → 200   crochet exécuté : 1 fois
GET /authorizationsigningkeys       → 500   crochet exécuté : 0 fois
GET /authorizationsigningkeys/{uuid}→ 500*  crochet exécuté : 1 fois
```

*(le 500 de ce domaine est une autre affaire — Jackson ne sait pas sérialiser un `IKey` ; une fois
le crochet en place pour vider ce champ, la route unitaire rend 200 et la route de collection reste
en 500.)*

## Pourquoi l'asymétrie coûte

Notre usage : retirer la matière cryptographique d'une entité `@Key` avant qu'elle ne parte sur le
réseau — le DTO d'un domaine de clés est aussi sa forme de persistance, il porte la moitié privée.

Avec le comportement actuel, le crochet protège la lecture unitaire et **pas** la lecture de
collection. Un consommateur qui écrit `afterGet` pour un motif de sécurité obtient donc une
protection à moitié, et rien ne le lui dit : ce n'est visible qu'en exerçant les deux routes. Chez
nous la collection échoue par ailleurs, ce qui masque le trou ; sur une entité dont tous les champs
se sérialisent, le secret sortirait par `GET /<domaine>` pendant que `GET /<domaine>/{uuid}` le
retire.

## Ce que nous demandons

Une seule chose, au choix de l'équipe :

1. **Que le crochet s'exécute sur toute lecture rendue au client**, collection comprise — c'est le
   comportement qu'un lecteur du DSL attend, et le seul qui rende `afterGet` utilisable pour une
   règle de sécurité ; ou
2. **Que le contrat soit écrit noir sur blanc** — « `afterGet` s'exécute sur la lecture unitaire
   seule » — dans la javadoc de `IEntityBuilder.afterGet`, avec la phrase qui manque :
   *ne l'utilisez pas pour retirer un secret, la lecture de collection ne passe pas par lui.*

La première est de loin préférable : la seconde laisse le point d'extension en place avec un usage
naturel qui est un piège.

## Deux précisions qui décident de l'utilité d'un correctif

1. **Les lectures INTERNES du framework doivent-elles déclencher le crochet ?** Notre cas dit non,
   et c'est le comportement actuel — le pipeline de signature relit la clé par le même domaine, et
   un crochet qui la viderait là empêcherait toute signature. Si `afterGet` était étendu à la
   collection, il faudrait garantir que cette exemption tient : c'est elle qui rend le crochet
   utilisable ici.
2. **L'entité rendue est-elle celle qui sera persistée ?** Si le crochet mute l'instance qu'une
   écriture ultérieure réutilise, vider un champ pour le transport l'effacerait en base. La
   documentation devrait le dire ; à défaut, le crochet ne peut s'employer que sur une copie.

## Piège annexe, rencontré en chemin

La forme « méthode d'entité » (`entity().afterGet("nom")`) doit rendre **`Void`** et non `void` :
`runAfterGet` invoque avec `IClass.getClass(Void.class)`. Avec `void`, le démarrage meurt sur

```
ReflectionException: No overload of method stripMaterialAfterGet in ownertype … matches the
specified signature (returnType=class java.lang.Void, parameterTypes=[])
```

— message exact, mais qui ne dit pas que `void` est refusé au profit de `Void`.

## Ce que la fiche ne demande PAS

- Pas de nouveau point d'extension : `afterGet` existe et fonctionne, c'est sa portée qui surprend.
- Pas de changement des crochets d'écriture (`beforeCreate` / `beforeUpdate`), qui fonctionnent.
- Pas de déclenchement sur les lectures internes du framework (voir précision 1).

## Ce que nous faisons en attendant

Le crochet est en place pour la lecture unitaire, ET les données sensibles ne sont rendues à l'écran
que par une **projection** dédiée, portée par un cas d'usage, qui ne recopie que ce qui peut sortir.
C'est la projection qui protège ; le crochet ferme la porte qui restait entrouverte.

## Rectification

La première version de cette fiche affirmait que `afterGet` **n'était jamais** invoqué. C'était
faux, et l'erreur venait de la mesure : les seules lectures exercées étaient une lecture de
COLLECTION (qui, on le sait maintenant, ne déclenche pas le crochet) et deux lectures qui ne passent
pas par le pipeline HTTP — un accès direct au dépôt et la résolution interne de la clé par le
framework. Aucune lecture unitaire n'avait été exercée. La fiche a été refaite après avoir posé le
même crochet sur un domaine ordinaire et exercé les quatre routes.
