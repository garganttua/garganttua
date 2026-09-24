# La portée d'`@EntityUnicity` est perdue : un `tenant` déclaré devient `system`

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur de garganttua-api v3, AOT pur, image native, DAO MongoDB)
**Date :** 2026-09-23
**Version constatée :** garganttua-api `3.0.0-ALPHA22` (dépôt à `50278b8`)
**Gravité :** moyenne, mais l'effet est une panne **fonctionnelle et muette** en multi-tenant : une
unicité voulue *par locataire* devient globale, donc deux équipes ne peuvent plus porter la même
valeur. La seconde reçoit un 409 qui ne dit rien de son vrai motif, et celui qui a écrit
l'annotation ne peut pas deviner que sa portée a été jetée — elle est écrite dans son code.
**Statut de la constatation :** **LU**, pas mesuré. Le défaut se corrige seul et ne dépend d'aucune
autre demande.

---

## 1. Symptôme

```java
public class Location {
    @EntityUnicity(scope = UnicityScope.tenant)   // « unique dans MON équipe »
    private String code;
}
```

Deux locataires différents créent chacun un lieu de code `URG`. Le second est **refusé** — 409
`Unicity constraint violated for field 'code'` — alors que l'annotation demande l'inverse.

Rien ne le signale ailleurs : le démarrage est propre, le résumé de l'API ne montre pas la portée,
et le message du 409 ne nomme que le champ. En mono-tenant, c'est parfaitement invisible : la portée
`system` et la portée `tenant` y donnent le même résultat, toujours.

---

## 2. La cause, lue dans les sources

`EntityAnnotationScanner.applyEntityFields` boucle sur les champs porteurs de `@EntityUnicity` et
appelle la surcharge **sans portée**
(`api/core/src/main/java/com/garganttua/api/core/entity/EntityAnnotationScanner.java:285-287`) :

```java
for (String addr : reflection.findFieldAddressesWithAnnotation(entityClass, IClass.getClass(EntityUnicity.class), true)) {
    entity.unicity(addr);
}
```

`scope()` n'est jamais relu. Et les trois surcharges sans portée de `EntityBuilder` valent
`UnicityScope.system`
(`api/core/src/main/java/com/garganttua/api/core/entity/EntityBuilder.java:217-229`) :

```java
public IEntityBuilder<E> unicity(IField field) throws ApiException {
    return unicity(field, UnicityScope.system);
}

public IEntityBuilder<E> unicity(String fieldName) throws ApiException {
    return unicity(fieldName, UnicityScope.system);
}

public IEntityBuilder<E> unicity(ObjectAddress fieldAddress) throws ApiException {
    return unicity(fieldAddress, UnicityScope.system);
}
```

alors que le défaut de l'annotation est **`tenant`**
(`api/commons/src/main/java/com/garganttua/api/commons/entity/annotations/EntityUnicity.java:12-15`) :

```java
public @interface EntityUnicity {
  UnicityScope scope() default UnicityScope.tenant;
}
```

Les deux défauts sont donc **opposés**, et le pont entre les deux jette l'information. Résultat :
**toute** `@EntityUnicity` produit une unicité `system` — celle qui déclare `scope = tenant`, comme
celle qui ne déclare rien et hérite du défaut `tenant`. Il n'existe aucune façon d'obtenir une
unicité par locataire *par l'annotation* ; seul le DSL y donne accès
(`entity().unicity("code", UnicityScope.tenant)`).

Et la portée est bien utilisée en aval, ce qui est ce qui rend la perte visible :
`checkUnicityConstraint` ajoute le filtre sur le locataire uniquement quand la portée vaut `tenant`
(`api/core/src/main/java/com/garganttua/api/core/expression/EntityConstraintExpressions.java:150-157`) :

```java
IFilter queryFilter = fieldFilter;
if (scope == UnicityScope.tenant && tenantIdAddress != null) {
    Object tenantId = REFLECTION.getFieldValue(entity, tenantIdAddress.toString());
    ...
}
```

### Le README dit l'inverse du code — et c'est le README qui ment

`api/commons/README.md:81` :

> *« `@EntityUnicity` / `@EntityUnicities` — Field(s) with a uniqueness constraint. `scope()` is
> `UnicityScope.tenant` (unique per tenant) or `UnicityScope.system` (globally unique). »*

et `:398` :

> *« Prefer `UnicityScope.system` only when the field must be unique across every tenant (e.g. a
> global slug). `UnicityScope.tenant` (the default) is the right choice for names that need to be
> unique only within a tenant. »*

La documentation décrit donc exactement le comportement attendu, y compris le défaut. **C'est le
code qui s'écarte du contrat publié, pas la documentation qui est en retard** — ce qui aggrave le
défaut plutôt que de l'excuser : un consommateur qui lit le README choisit `tenant` en confiance,
ou l'obtient par omission, et obtient `system`.

### La mécanique existe à côté, dans le MÊME fichier

Trois lignes plus haut, `@EntityMandatory` fait ce qu'il faut : la boucle relit l'attribut de
l'annotation avant de le passer au builder
(`EntityAnnotationScanner.java:282-284`, puis `:296-302`) :

```java
for (String addr : reflection.findFieldAddressesWithAnnotation(entityClass, IClass.getClass(EntityMandatory.class), true)) {
    entity.mandatory(addr, declaredMandatoryPolicy(reflection, entityClass, addr));
}
...
private static MandatoryPolicy declaredMandatoryPolicy(IReflection reflection, IClass<?> entityClass,
        String address) {
    return reflection.findField(entityClass, address)
            .map(field -> field.getAnnotation(IClass.getClass(EntityMandatory.class)))
            .map(EntityMandatory::value)
            .orElse(MandatoryPolicy.anyValue);
}
```

`declaredMandatoryPolicy` est le gabarit exact du correctif — y compris son repli documenté quand
l'adresse est imbriquée et que la résolution plate ne l'atteint pas.

---

## 3. Ce qui aiderait

Que `EntityAnnotationScanner` relise `scope()` et le transmette :

```java
for (String addr : reflection.findFieldAddressesWithAnnotation(entityClass, IClass.getClass(EntityUnicity.class), true)) {
    entity.unicity(addr, declaredUnicityScope(reflection, entityClass, addr));
}
```

avec un `declaredUnicityScope` calqué sur `declaredMandatoryPolicy`, et le même repli explicite
quand le champ n'est pas résoluble par son adresse — en disant lequel des deux défauts s'applique
alors, parce que c'est le seul endroit où le choix reste arbitraire.

**Conséquence à annoncer, qui n'est pas neutre :** aujourd'hui toute `@EntityUnicity` produit
`system`. Après le correctif, une annotation nue produira `tenant`, conformément à son défaut
déclaré. Un consommateur qui écrit `@EntityUnicity` sans portée en **s'appuyant sur le comportement
observé** verrait donc sa contrainte s'élargir. Nous ne voyons pas de meilleur choix — honorer la
déclaration est la correction, et la conserver telle quelle laisserait un attribut décoratif — mais
cela mérite une ligne dans les notes de version, et non d'être découvert.

---

## 4. Articulation avec la fiche sur les index

Ce défaut est **aujourd'hui sans conséquence en image native pure**, pour une raison qui n'a rien à
voir avec lui : l'annotation de champ y est de toute façon perdue dès qu'un descripteur `AOTField_*`
existe pour la classe — voir
[`aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md`](aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md),
§3.0. Une portée jetée par le scanner d'annotations ne se remarque pas quand le scanner ne voit déjà
pas l'annotation.

Il **mord dès que ce préalable sera livré**. Les deux doivent donc être corrigés dans cet ordre :
d'abord que l'annotation de champ arrive jusqu'au scanner, ensuite que le scanner cesse d'en jeter
la moitié. Livrer le préalable seul ferait apparaître, du jour au lendemain, des unicités globales
là où les entités déclarent `tenant` — et la fiche des index serait accusée d'une panne qui ne vient
pas d'elle.

Les deux défauts restent **indépendants** : celui-ci se corrige sans toucher à l'AOT, se teste sur
JVM, et le correctif tient en une méthode.

---

## 5. Ce que cette fiche ne demande PAS

- **Pas de changement du défaut de l'annotation.** `@EntityUnicity` doit garder
  `scope() default UnicityScope.tenant` : c'est le défaut publié dans `api/commons/README.md:398`,
  et c'est le bon pour une plateforme multi-tenant.
- **Pas de changement du défaut des surcharges du DSL.** `unicity(String)`, `unicity(IField)` et
  `unicity(ObjectAddress)` doivent continuer à valoir `UnicityScope.system`. Nous appelons ces
  surcharges — c'est notre seule façon d'écrire une unicité aujourd'hui — et en changer le sens
  transformerait silencieusement toutes nos contraintes globales en contraintes par locataire, ce
  qui est exactement la panne décrite ici, dans l'autre sens.
- **Pas d'unification des deux défauts.** Qu'ils divergent est défendable : une annotation posée sur
  une entité multi-tenant et un appel de DSL écrit à la main n'ont pas le même contexte. Ce qui ne
  l'est pas, c'est que le pont entre les deux jette l'information au lieu de la transmettre.
- **Pas de création d'index**, ni d'aucun autre effet de bord de l'unicité : c'est l'objet de
  l'autre fiche, et elle demande explicitement que cela n'arrive pas.
- **Pas de rupture d'API.** Tout est déjà en place : `IEntityDefinition.unicities()` rend déjà
  `List<Pair<ObjectAddress, UnicityScope>>`
  (`api/commons/src/main/java/com/garganttua/api/commons/definition/IEntityDefinition.java:43`), et
  la surcharge à deux arguments existe déjà
  (`api/core/src/main/java/com/garganttua/api/core/entity/EntityBuilder.java:232-259`). Il manque un
  appel, pas un contrat.

---

## Réponse de la plateforme — 2026-09-23

**Corrigée, exactement comme demandé.** Sur `main`, à paraître dans `3.0.0-ALPHA24`.

Votre lecture est juste de bout en bout : la boucle, les trois surcharges, les deux défauts
opposés, et jusqu'au gabarit à recopier. Nous n'avons rien trouvé à rectifier — c'est la première
fiche de ce dossier dont le correctif est littéralement celui qu'elle écrit.

### Ce que vous obtenez

`EntityAnnotationScanner.applyEntityFields` appelle désormais `entity.unicity(addr,
declaredUnicityScope(...))`, avec un `declaredUnicityScope` calqué sur `declaredMandatoryPolicy`.
Une `@EntityUnicity(scope = tenant)` produit une unicité par locataire ; une annotation nue aussi,
puisque c'est son défaut déclaré.

Les trois choses que vous demandiez de ne PAS changer n'ont pas bougé : le défaut de l'annotation
reste `tenant`, les surcharges du DSL sans portée restent `system`, et la divergence entre les deux
est maintenue — elle est maintenant écrite dans la javadoc du DSL, avec sa raison, pour que
personne ne la « répare » par symétrie.

**Le repli.** Quand l'adresse est imbriquée et que la recherche plate ne la résout pas, la portée
retenue est celle de l'**annotation** (`tenant`), pas celle du DSL. L'annotation est ce que l'auteur
a écrit ; une annotation illisible se lit donc comme ce qu'elle aurait dit par défaut. C'est le seul
endroit où le choix restait arbitraire, vous l'aviez vu, il est tranché et documenté. Ce cas n'est
pas épinglé par un test : fabriquer une adresse imbriquée non résoluble demandait une entité qui
sort du périmètre de la fiche.

### La conséquence, annoncée comme vous le demandiez

Elle est dans les notes de version d'ALPHA24 : **une `@EntityUnicity` nue passe de `system` à
`tenant`.** Un consommateur qui s'appuyait sur le comportement observé verra sa contrainte
s'élargir. Comme vous, nous ne voyons pas de meilleur choix — honorer la déclaration EST la
correction.

### Votre §5.3, traité au passage

`@EntityUnicities` et `@EntityMandatories` étaient déclarées, documentées comme actives, et lues
nulle part. **Elles sont supprimées**, et les lignes du README qui les annonçaient sont réécrites.
Nous n'avons pas voulu les rendre actives : personne ne les utilise (rien dans le dépôt ni chez vous
ne les nomme), et une annotation répétable qui apparaît au moment où sa cousine change de sens
aurait ajouté un second piège au premier. Si vous en avez l'usage, redemandez-les : ce sera alors
une fonctionnalité, pas une réparation.

### Votre §4 — l'ordre avec la fiche des index

Vous aviez raison de l'écrire, et raison sur l'ordre. Le préalable AOT est livré **dans la même
version** que ce correctif, donc le scénario que vous redoutiez — le préalable seul, faisant
apparaître d'un coup des unicités globales là où les entités déclarent `tenant` — ne se produit pas.
Les deux arrivent ensemble, et dans le bon sens.
