# garganttua-api — les crochets `afterGet` ne sont jamais appelés

**À l'attention de :** garganttua-api
**Émis par :** autonom (consommateur v3, AOT pur)
**Date :** 2026-09-01
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** ÉLEVÉE — le DSL donne à lire une protection qui n'existe pas, et le seul usage naturel
de ce crochet est de retirer un secret avant qu'il ne franchisse la frontière HTTP.
**Nature :** bogue. **MESURÉ** en HTTP, sur les deux formes de câblage.

## Symptôme

Un crochet `afterGet` déclaré sur une entité ne s'exécute pas. L'API démarre, le crochet est
manifestement RÉSOLU — une signature fautive tue le démarrage, voir plus bas — et il n'est jamais
appelé : aucune trace, et l'entité rendue par la lecture est intacte.

Les deux formes ont été essayées, sur le même domaine, le même démarrage, la même lecture :

```java
// 1. Crochet LIBRE, paramètres fournis explicitement (idiome des beforeCreate/beforeUpdate,
//    qui fonctionnent, eux)
domain.entity()
    .afterGet(hook)                                   // hook = (Object, ICaller)
        .withParam(0, new HookEntitySupplierBuilder(entityClass))
        .withParam(1, new CallerSupplierBuilder())
    .up()
  .up();

// 2. Méthode portée par l'ENTITÉ, nommée
domain.entity().afterGet("stripMaterialAfterGet").up().up();   // public Void stripMaterialAfterGet()
```

Mesure, identique dans les deux cas :

```
GET /<domaine>            → l'entité revient INTACTE
journal                   → 0 exécution du crochet (trace posée en première ligne)
```

## Ce que nous voulions en faire, et pourquoi ça compte

Retirer la matière cryptographique d'une entité `@Key` avant qu'elle ne parte sur le réseau. Le DTO
d'un domaine de clés est aussi sa forme de PERSISTANCE : il porte la moitié privée, celle qui signe
les jetons. `afterGet` est exactement le point prévu pour cela — et c'est un point de SÉCURITÉ :
un crochet qui ne tire pas est une barrière de papier, et sur ce cas-là elle est pire que rien,
puisqu'on croit la clé privée retirée.

Nous avons donc retiré le crochet et alimenté l'écran par une projection rendue par un cas d'usage.
La demande ci-dessous porte sur le crochet lui-même.

## Cause probable

`api/core/src/main/java/com/garganttua/api/core/expression/EntityLifecycleExpressions.java:76` —
`runAfterGet` existe, itère `entityDef.afterGetMethodBuilders()` puis les crochets libres. Nous
n'avons pas trouvé, dans le pipeline de lecture, l'étape qui l'appelle : `CrudExpressions` mentionne
les `afterGet` (lignes 165 et 179) pour dire qu'une projection n'est poussée au DAO que sur un
domaine « propre, sans afterGet », ce qui suppose qu'ils existent — mais la lecture que nous
exerçons n'en déclenche aucun.

Même famille, semble-t-il, que
[crochets-de-suppression-ne-tirent-jamais](crochets-de-suppression-ne-tirent-jamais.md) : le crochet
est déclaré, résolu, et jamais invoqué.

## Piège annexe, rencontré en chemin

La forme « méthode d'entité » doit rendre **`Void`** et non `void` : `runAfterGet` invoque avec
`IClass.getClass(Void.class)`. Avec `void`, le démarrage meurt sur

```
ReflectionException: No overload of method stripMaterialAfterGet in ownertype … matches the
specified signature (returnType=class java.lang.Void, parameterTypes=[])
```

— message exact, mais qui ne dit pas que `void` est refusé au profit de `Void`. C'est aussi la
preuve que le crochet EST résolu au démarrage : il est lu, puis ignoré.

## L'attendu

`afterGet` s'exécute sur chaque entité rendue par une lecture, dans les deux formes de câblage.

Deux précisions qui décident de l'utilité du correctif :

1. **Le crochet doit-il s'exécuter sur les lectures INTERNES du framework ?** Notre cas dit non : le
   pipeline de signature relit la clé par le même domaine, et un crochet qui la viderait là
   empêcherait toute signature. Un crochet qui ne tire que sur les lectures issues d'une requête
   HTTP serait exactement ce qu'il nous faut ; s'il doit tirer partout, il faut au moins pouvoir le
   savoir depuis le crochet (l'`ICaller` interne est distinguable).
2. **L'entité rendue est-elle celle qui est ensuite persistée ?** Si le crochet mute l'instance que
   le cache ou une écriture ultérieure réutilise, vider un champ pour le transport l'effacerait en
   base. La documentation devrait le dire ; à défaut, le crochet ne peut être employé qu'avec une
   copie.

## Ce que la fiche ne demande PAS

- Pas de nouveau point d'extension : `afterGet` existe, il est documenté par le DSL, il suffit qu'il
  s'exécute.
- Pas de changement des crochets d'écriture (`beforeCreate` / `beforeUpdate`), qui fonctionnent.

## Ce que nous faisons en attendant

Une **projection** : les données sensibles ne sont rendues que par un cas d'usage dédié, qui ne
recopie que ce qui peut sortir. C'est la même parade que pour la suppression de documents, où le
crochet ne tirait pas non plus — un cas d'usage remplace le CRUD.
