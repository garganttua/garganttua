# Aucun index de base n'est posé : l'unicité déclarée ne tient pas sous concurrence, et une requête géo exige un index que personne ne crée

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur de garganttua-api v3, AOT pur, image native, DAO MongoDB)
**Date :** 2026-09-23
**Version constatée :** garganttua-api `3.0.0-ALPHA22` (dépôt à `50278b8`)
**Gravité :** haute — l'invariant le plus fort que le DSL sache exprimer (« un seul abonnement actif
par client ») **ne tient pas**, et rien ne le signale : les deux requêtes réussissent, les deux
lignes existent, aucun journal. Mesuré chez nous à **30 tours sur 30**. Le second volet est plus
discret et sans risque pour les données : une requête géospatiale émise par le DAO Mongo suppose un
index `2dsphere` que ni le cadre ni le DAO ne créent.
**Statut de la constatation :** **MESURÉ** pour le comportement sous concurrence et pour le contenu
des descripteurs AOT engendrés ; **LU** pour tout le reste. Les deux sont distingués à chaque
endroit.

---

## 1. Symptôme

### 1.1 L'unicité déclarée laisse passer un doublon dès qu'il y a deux fils — **MESURÉ**

Banc chez palliad, rejoué le 2026-09-23 sur `3.0.0-ALPHA22` : un domaine d'essai déclare

```java
entity().unicity("cle", UnicityScope.system);
```

et deux fils lâchés sur une barrière créent la même clé. MongoDB de développement, **API réellement
montée** (pipeline CRUD complet, pas un appel direct au DAO), base jetable, trente tours pour que le
résultat ne tienne pas à un hasard d'ordonnancement.

| Sonde | Ce qui est mesuré |
|---|---|
| **séquentiel** — deux créations l'une après l'autre | second enregistrement **REFUSÉ**, `returned CONFLICT` ; **1** document porte la clé. Conforme. |
| **concurrent, SANS index Mongo** — 2 fils, 30 tours | **30 tours sur 30 produisent DEUX lignes de même clé.** Aucun refus, aucun journal. |
| **concurrent, AVEC un index unique Mongo posé à la main** — 2 fils, 30 tours | **0 doublon sur 30**, **30 refus** remontés à l'appelant ; chaîne de causes `ApiException ← MongoWriteException`, `E11000` présent, code d'opération rapporté **`SERVER_ERROR`** (donc HTTP 500). |

Le banc est `backend/palliad-backend-cloud/src/test/java/care/palliad/billing/LedgerProbeTest.java`
chez nous (questions Q6, Q7, Q8). Une exécution antérieure du même banc avait donné 29 sur 30 — d'où
la fourchette « 29 à 30 » ; celle du jour donne 30 sur 30.

L'effet, en une phrase : **la contrainte la plus forte du DSL protège la saisie d'un humain et rien
d'autre.** Deux requêtes simultanées — un double-clic, une reprise de webhook, deux onglets, et
demain deux nœuds — la traversent. Nous refondons la facturation ; « un seul abonnement actif par
client » et « une seule demande de facturation par période » sont des invariants dont la violation
ne se voit qu'à la réconciliation comptable, des semaines plus tard.

### 1.2 Une requête géo exige un index que personne ne crée — **LU**

`MongoFilterConverter` sait traduire `$geoWithin` / `$geoWithinSphere` en
`{<champ>: {$geoWithin: {$geometry: …}}}`, et son propre commentaire porte la condition :

> `api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoFilterConverter.java:100-103`
> — *« Geospatial: `$geoWithin` and `$geoWithinSphere` both resolve to a GeoJSON
> `$geoWithin`/`$geometry` query — a 2dsphere index already evaluates it on the sphere, so 'Sphere'
> is an alias here. **Requires a 2dsphere index on the field.** »*

Et le README du DAO met la création à la charge du consommateur, explicitement :

> `api/dao/dao-mongodb/README.md:217` — *« Index creation is not managed by `MongoDao`. Create
> indexes (unique, TTL, text, geospatial) independently — via `MongoCollection.createIndex(...)`, a
> migration tool, or your Spring configuration. »*

---

## 2. La cause, lue dans les sources

### 2.1 La vérification d'unicité est une LECTURE, puis une ÉCRITURE

`EntityConstraintExpressions.validateUnicity` / `checkUnicityConstraint`
(`api/core/src/main/java/com/garganttua/api/core/expression/EntityConstraintExpressions.java:112-168`)
construit un filtre et interroge le dépôt :

```java
// :164
List<Object> existing = repo.getEntities(Optional.empty(), Optional.of(queryFilter), Optional.empty());
if (!existing.isEmpty()) {
    throw new ApiException("Unicity constraint violated for field '" + fieldAddress + "'");
}
```

C'est une **lecture**. L'écriture arrive beaucoup plus loin, et les deux sont séparées par une
vingtaine de lignes de script :

| Script | Vérification | Écriture | Lignes entre les deux |
|---|---|---|---|
| `api/core/src/main/resources/scripts/business/CREATE_ONE.gs` | `validateUnicity(@entity, @1, @2)` **:57**, `-> 409` **:58** | `saveEntity(@1, @entity)` **:76**, `-> 500` **:77** | 19 |
| `api/core/src/main/resources/scripts/business/UPDATE_ONE.gs` | `validateUnicity(@storedEntity, @1, @2)` **:64** | `saveEntity(@1, @storedEntity)` **:84** | 20 |

Entre les deux : les crochets `@BeforeCreate` du consommateur, le verrou super-tenant, la sécurité de
l'authentificateur. Autrement dit, la fenêtre n'est pas une inversion d'instructions improbable —
c'est **tout le code métier du consommateur**. Nos 30/30 s'expliquent d'eux-mêmes.

> Nous savons que la synchronisation d'écriture livrée en ALPHA20
> (`apiBuilder.synchronization(...)`, [fiche dédiée](synchronisation-multi-instances-absente-de-lapi.md))
> referme cette fenêtre, et nous l'utiliserons. Elle ne remplace pas un index : elle demande un Redis
> et une politique par domaine, et elle ne protège pas d'un écrivain qui n'est pas l'API — un script
> de migration, un import, une seconde application sur la même base. **Un index est la seule garantie
> que porte la donnée elle-même.**

### 2.2 Aucune création d'index, nulle part

Recherche sur TOUT le dépôt (hors `target/`) :

```
grep -rn "createIndex\|ensureIndex\|createCollection" \
     --include=*.java --include=*.gs --include=*.md --include=*.xml .
```

Trois occurrences, et pas une n'est du code de production :

| Occurrence | Ce que c'est |
|---|---|
| `api/dao/dao-mongodb/README.md:217` | la phrase citée en 1.2 — la documentation qui décline la charge |
| `api/dao/dao-postgresql/src/test/java/com/garganttua/dao/postgresql/parity/ParityLogicTextRegexTest.java:504` | un index texte posé **par un test**, pour pouvoir comparer Mongo et PostgreSQL |
| cette fiche | — |

Et le point d'accroche naturel ne fait rien de tel : `MongoDao.registerDomain`
(`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDao.java:66-78`) **ne lit que la
définition du DTO** — classe, nom du champ uuid, compositions. Il ne regarde jamais
`domainDefinition.entityDefinition()`, donc ni les unicités, ni leur portée, ni le champ géolocalisé.

### 2.3 `@EntityGeolocalized` n'est que mémorisée

`EntityAnnotationScanner:258-261` lit l'annotation et appelle `domain.geolocalized(location)`.
L'adresse voyage jusqu'à `IDomainDefinition.geolocalized()`
(`api/commons/src/main/java/com/garganttua/api/commons/definition/IDomainDefinition.java:35`), et
**deux lecteurs seulement** existent dans tout le dépôt :

- `api/core/src/main/java/com/garganttua/api/core/api/ApiSummary.java:117` — ajoute le mot
  `geolocalized` au résumé de démarrage ;
- `api/commons/src/main/java/com/garganttua/api/commons/context/IDomain.java:136-138` —
  `isGeolocalized()`, dont `grep` ne trouve **aucun appelant de production** (un seul appel, dans
  `GeolocalizedFieldTypeTest`).

L'annotation déclare donc un fait que rien n'exploite, pendant que le DAO qui en a besoin réclame un
index dans un commentaire.

---

## 3. Ce qui aiderait

Nous demandons **une annotation qui pose des index**, et rien de plus large. La forme ci-dessous a
été arrêtée par l'exploitant de palliad après avoir regardé les autres ; elle est présentée comme
telle, c'est-à-dire comme une proposition dont la **propriété** compte plus que la syntaxe.

### 3.0 PRÉALABLE OBLIGATOIRE — les annotations de CHAMP et le processeur AOT

**C'est le cœur de cette fiche.** Demander une annotation de champ à un cadre dont le processeur AOT
ne transporte pas les annotations de champ reviendrait à demander une annotation muette. Le point
doit être traité **avant** le reste, et il répare au passage trois annotations existantes.

**Ce qui est LU.** `AOTFieldSourceGenerator` écrit le tableau d'annotations **en dur, vide** :

```java
// core/aot/aot-annotation-processor/src/main/java/com/garganttua/core/aot/annotation/processor/AOTFieldSourceGenerator.java:82
.append("new Annotation[0], ").append(buildGenericTypeExpr()).append(");\n");
```

et `AOTField.getAnnotation` ne fait que parcourir ce tableau
(`core/aot/aot-reflection/src/main/java/com/garganttua/core/aot/reflection/AOTField.java:263-271`) :
il rend donc **toujours `null`** sur un descripteur engendré. `isAnnotationPresent` étant le défaut
de `IAnnotatedElement`
(`core/commons/src/main/java/com/garganttua/core/reflection/IAnnotatedElement.java:40-42`, soit
`getAnnotation(...) != null`), la recherche par annotation ne trouve rien non plus :
`FieldDelegate.findFieldAddressesRecursively`
(`core/reflection/src/main/java/com/garganttua/core/reflection/dsl/FieldDelegate.java:79-106`) teste
exactement cela sur `clazz.getDeclaredFields()` — et c'est cette méthode que `EntityAnnotationScanner`
appelle pour trouver les champs `@EntityUnicity` et `@EntityMandatory`
(`api/core/src/main/java/com/garganttua/api/core/entity/EntityAnnotationScanner.java:282-287`).

**Ce qui est MESURÉ** — sur l'arbre de compilation, pas au banc natif : dans notre build,
**45 fichiers `AOTField_*.java` sur 45** portent `new Annotation[0]`. Par exemple le descripteur que
vous nous demandiez de regarder,
`backend/palliad-backend-common/target/generated-sources/annotations/care/palliad/activity/AOTField_ActivityReferentialCatalogue_Ligne_displayOrder.java` :

```java
private AOTField_ActivityReferentialCatalogue_Ligne_displayOrder() {
    super("displayOrder", "care.palliad.activity.ActivityReferentialCatalogue.Ligne",
          "java.lang.Integer", 1, new Annotation[0], null);
}
```

À l'inverse, une annotation **de TYPE** survit : `AOTClassSourceGenerator` émet
`<Class>.class.getAnnotations()`
(`core/aot/aot-annotation-processor/src/main/java/com/garganttua/core/aot/annotation/processor/AOTClassSourceGenerator.java:172-181`),
c'est-à-dire l'appel réel. C'est pourquoi `@EntityGeolocalized`, `@EntityShared` et
`@EntityHiddenable` — toutes `@Target(TYPE)` — fonctionnent en natif pur, et pourquoi
`@EntityUnicity`, `@EntityMandatory` et `@FieldMappingRule` — toutes `@Target(FIELD)` — sont le cas
douteux.

#### Une NUANCE que nous devons signaler : la perte n'est pas universelle, et sa forme réelle est PIRE

Nous avons commencé cette fiche en pensant que toute annotation de champ était perdue sous AOT.
**Les sources disent autre chose, et il faut le lire avant de décider du correctif.**

`AOTClass.getDeclaredFields()`
(`core/aot/aot-reflection/src/main/java/com/garganttua/core/aot/reflection/AOTClass.java:366-370`) :

```java
public IField[] getDeclaredFields() {
    if (fields.length > 0) return fields.clone();
    IField[] fallback = liveFallback.declaredFields();
    return fallback != null ? fallback : fields.clone();
}
```

et le repli synthétise depuis le `Class` vivant en passant les **vraies** annotations
(`AOTLiveClassFallback.java:208-226` → `AOTField.synthesizeFrom` → `field.getAnnotations()`,
`AOTField.java:60-68`).

Or `DirectBinderGenerator` **n'engendre de descripteur que pour les membres NON privés**
(`core/aot/aot-annotation-processor/src/main/java/com/garganttua/core/aot/annotation/processor/DirectBinderGenerator.java:309-311`
et `:335-339` — *« Drops `private` members — direct binders cannot bypass Java visibility »*). D'où
deux régimes :

| Forme de la classe | `fields` engendré dans `AOTClass_*` | Ce que `getDeclaredFields()` rend | Annotations de champ |
|---|---|---|---|
| tous les champs `private` (l'entité Lombok ordinaire) | `new AOTField[0]` | le repli sur le `Class` vivant | **conservées** |
| au moins un champ non privé (une constante `public static final`, typiquement) | un tableau ne contenant QUE les non-privés | ce tableau seul | **perdues** — et les champs privés **disparaissent entièrement** |

Autrement dit : aujourd'hui les annotations de champ survivent **par le repli**, c'est-à-dire par le
chemin même que l'AOT pur existe pour supprimer, et jamais par le chemin AOT. Et quand le chemin AOT
prend la main, il ne les affaiblit pas — il **ment sur la classe**.

Nous l'avons déjà payé, avant cette fiche et sans faire le lien : une constante `public static final`
posée sur une entité `@Reflected` mappée par le DSL fait échouer `entity().id("id")` avec
`ReflectionException: Object element id not found in class …`, et **toute l'API refuse de démarrer**
— visible au démarrage seulement, jamais à la compilation. La règle que nous en avons tirée (« aucune
constante dans une entité mappée ») est un contournement de ce défaut précis.

**Ce point est LU, PAS mesuré au banc natif** : nous n'avons pas construit d'image native portant une
annotation de champ lue par le cadre pour l'y voir disparaître — nos entités passent toutes par le
DSL et n'utilisent ni `@EntityUnicity` ni `@EntityMandatory`, ce qui est précisément pourquoi le
défaut ne nous a pas encore mordus. **Nous vous demandons de le confirmer ou de nous démentir** avant
d'engager la suite ; et si vous nous démentez, de dire lequel des deux régimes ci-dessus s'applique
en image native. Le repli suppose `Class.forName` + `getDeclaredFields()` enregistrés dans l'image,
ce que le commentaire de `AOTLiveClassFallback.java:17-19` attribue à `GarganttuaAotFeature` « for
seeded types » — donc pas nécessairement pour une entité de consommateur.

**Ce que nous demandons, dans cet ordre :**

1. **Que le processeur AOT émette les annotations RÉELLES des champs**, comme il le fait déjà pour
   les types. Cela répare du même coup `@EntityUnicity`, `@EntityMandatory` et `@FieldMappingRule`
   sur le chemin AOT, aujourd'hui muettes dès qu'un descripteur de champ existe. Tant qu'à y
   toucher : un `getDeclaredFields()` qui ne rend qu'une partie des champs déclarés est une source
   de pannes muettes indépendante de cette fiche, et nous l'avons rencontrée.
2. **Puis `@EntityIndexed` sur le champ.** Pas avant.

### 3.1 `@EntityIndexed`, SUR LE CHAMP

Sur le gabarit exact de `EntityUnicity` / `EntityMandatory` — `@Target(FIELD)`, `@Retention(RUNTIME)`,
plus la méta-annotation `@Indexed` de `com.garganttua.core.reflection.annotations` :

```java
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EntityIndexed {
    boolean unique() default false;
    UnicityScope scope() default UnicityScope.tenant;  // tenant → index composite (tenantId, champ)
    IndexKind kind() default IndexKind.standard;       // standard | geo | text
    String name() default "";                          // vide → nom dérivé, stable
}
```

Au minimum : **unique oui/non** ; **portée** (système, ou locataire → index composite
`tenantId + champ`, **préfixe tenant d'abord**, pour que l'index serve aussi les lectures filtrées
par locataire) ; **type** (standard, géo, texte) ; et un **nom dérivé par défaut et stable** — stable
parce qu'un nom qui change d'une version à l'autre fait créer un second index identique au lieu de
retrouver le premier.

**Pas le nom `@Indexed`.** Il est déjà pris par
`com.garganttua.core.reflection.annotations.Indexed`
(`core/commons/src/main/java/com/garganttua/core/reflection/annotations/Indexed.java:44-48`), qui est
une méta-annotation de **compilation** : posée sur une définition d'annotation, elle fait écrire au
processeur un index `META-INF/garganttua/index/` des classes et méthodes porteuses. Vérifié :
**138 définitions d'annotations du dépôt la portent** (dont 92 dans `api/commons`) — `EntityUnicity`,
`EntityMandatory`, `EntityUnicities`, `EntityMandatories` et `EntityGeolocalized` comprises.
Réutiliser le nom pour une annotation d'index de base de données créerait, dans le même produit, une
homonymie entre « indexé à la compilation » et « indexé en base ».

### 3.2 Le garde-fou — sans lui, l'oubli reste muet

**Seule l'annotation pose des index.** Une unicité déclarée n'en pose PAS, et c'est un choix assumé
de notre côté : le §4 dit pourquoi.

Mais un consommateur qui écrit `unicity(...)` croit poser un invariant, et nos 30/30 disent qu'il se
trompe. **Au montage de l'API, un `WARN` doit nommer chaque champ sous unicité qui ne porte PAS
d'annotation d'index** — domaine, champ, portée, et la déclaration qui manque. Sans cet
avertissement, l'oubli a exactement la forme de la panne que cette fiche décrit : silencieuse,
constatée des semaines plus tard, dans les données.

L'endroit existe : `ApiSummary`
(`api/core/src/main/java/com/garganttua/api/core/api/ApiSummary.java:108-124`) compose déjà le résumé
de démarrage par domaine, drapeaux compris.

### 3.3 Le traitement de l'échec — c'est ce point qui décide du déploiement

Sur une base qui porte **déjà** des doublons, `createIndex(unique)` échoue. C'est le cas normal :
c'est précisément la base qu'on veut protéger, et elle est dans cet état parce que la protection
manquait.

Si cet échec est traité comme `PgDao` traite le sien
(`api/dao/dao-postgresql/src/main/java/com/garganttua/dao/postgresql/PgDao.java:110-116`) :

```java
} catch (ApiException e) {
    // registerDomain cannot throw a checked exception: surface it unchecked, with its message.
    throw new IllegalStateException("PostgreSQL schema of domain '" + this.domainName
            + "' is not usable: " + e.getMessage(), e);
}
```

— alors **l'application entière refuse de démarrer**. Vérifié, rien ne rattrape cette exception sur
le chemin : `Domain.doInit` appelle `DomainInterfaceSupport.registerDomainOnDaos`
(`api/core/src/main/java/com/garganttua/api/core/domain/Domain.java:182`), qui boucle sans `try`
(`api/core/src/main/java/com/garganttua/api/core/domain/DomainInterfaceSupport.java:30-36`), et
`AbstractLifecycle.onInit` appelle `doInit()` nu
(`core/lifecycle/src/main/java/com/garganttua/core/lifecycle/AbstractLifecycle.java:143-163`).

Le résultat serait absurde : **l'instance refuserait de démarrer à cause des doublons que le
correctif existe pour empêcher** — à la montée de version, sur la base de production, sans que
personne ait rien changé. C'est un correctif qui se retourne contre celui qui l'applique.

**Ce que nous demandons :** un index qui ne peut pas être créé **laisse démarrer**, et le dit en
`WARN`, en nommant le domaine, le champ, et **la requête d'agrégation qui liste les doublons**, prête
à coller :

```
WARN  Unique index on 'subscriptions.customerId' could not be created: duplicate values already
      exist. The uniqueness constraint is NOT enforced by the database for this domain.
      List the duplicates with:
      db.subscriptions.aggregate([{$group:{_id:"$customerId",n:{$sum:1},ids:{$push:"$_id"}}},
                                  {$match:{n:{$gt:1}}}])
```

Le refus de démarrer reste légitime, mais **dans un mode strict explicite** — celui qui l'a demandé
sait pourquoi son instance s'arrête.

### 3.4 Le gabarit existe déjà à côté : `PgSchemaManager`

Le DAO PostgreSQL fait au démarrage, pour le schéma, ce que nous demandons pour les index de Mongo,
et il a déjà tranché les mêmes questions :

| Propriété | Où |
|---|---|
| DDL au démarrage, **depuis `registerDomain`** | `api/dao/dao-postgresql/src/main/java/com/garganttua/dao/postgresql/PgDao.java:97-122` |
| purement **additif** — rien n'est supprimé, renommé ni retypé ; un type divergent est un `WARN` | `PgSchemaManager.java:36-39` |
| **verrou entre instances** : deux instances qui démarrent ensemble ne se courent pas dessus (`pg_advisory_xact_lock` sur le nom de table) | `PgSchemaManager.java:42-46`, `:57`, `:185` |
| deux modes, `CREATE` et `VALIDATE` — l'un crée ce qui manque, l'autre refuse de démarrer et liste le DDL manquant | `PgSchemaManager.java:79-92`, `PgSchemaValidator.java:88-92` |
| un réglage unique pour choisir, `postgresql.schema.auto` | `api/starters/starter-postgresql/README.md:46`, `PostgresAutoConfiguration.java:69-70` |

Nous demandons **le même gabarit pour Mongo**, sous un réglage `mongodb.index.auto`, avec la seule
divergence du §3.3 : le mode « crée ce qui manque » ne doit pas faire échouer le démarrage sur un
index unique impossible, parce qu'un `CREATE TABLE IF NOT EXISTS` ne peut pas échouer à cause des
données, là où un `createIndex(unique)` le peut.

> **Honnêteté sur ce précédent :** `PgSchemaManager` crée des **tables et des colonnes**, pas des
> index secondaires — le seul index qu'il produise est la `PRIMARY KEY` sur la colonne uuid
> (`api/dao/dao-postgresql/src/main/java/com/garganttua/dao/postgresql/schema/PgDdl.java:45-55`).
> Nous ne prétendons donc pas que PostgreSQL fait déjà ce que Mongo ne fait pas : **les deux DAO sont
> logés à la même enseigne**, et une unicité déclarée n'est pas plus tenue par l'un que par l'autre.
> C'est la **mécanique** de `PgSchemaManager` que nous citons comme gabarit, pas sa couverture.

### 3.5 Le point d'accroche reçoit DÉJÀ tout le nécessaire

`IDao.registerDomain(IDomainDefinition)`
(`api/commons/src/main/java/com/garganttua/api/commons/dao/IDao.java:17`) porte déjà, sans qu'aucune
signature change :

| Ce dont la création d'index a besoin | Où c'est déjà |
|---|---|
| les champs sous unicité **et leur portée** | `domainDefinition.entityDefinition().unicities()` → `List<Pair<ObjectAddress, UnicityScope>>` (`api/commons/src/main/java/com/garganttua/api/commons/definition/IEntityDefinition.java:43`) |
| le champ tenant côté entité | `IEntityDefinition.tenantId()` (`:29`) |
| le champ tenant **côté DTO** — c'est celui-là qui nomme la clé du document | `IDtoDefinition.tenantId()` (`api/commons/src/main/java/com/garganttua/api/commons/definition/IDtoDefinition.java:16`) |
| le champ géolocalisé | `IDomainDefinition.geolocalized()` (`:35`) |
| traduire un nom de champ d'entité en nom de champ de document | `MongoDao.translateToDtoField`, qui existe et lit déjà `@FieldMappingRule` (`api/dao/dao-mongodb/src/main/java/com/garganttua/dao/mongodb/MongoDao.java:183-201`) |

Aujourd'hui `MongoDao.registerDomain` n'utilise rien de tout cela. Il n'y a pas de tuyau à poser : il
y a une définition à lire jusqu'au bout.

---

## 4. Pourquoi nous ne demandons PAS que l'unicité déclarée pose l'index

C'est le geste évident, et nous l'écartons — décision de l'exploitant de palliad, pour trois raisons,
et la troisième suffirait :

1. **La sémantique n'est pas la même** : la vérification ignore un champ nul, un index unique non
   (§5.1).
2. **Une unicité déclarée existe déjà** dans des bases qui portent des doublons. En faire une
   création d'index, c'est transformer une montée de version en panne de démarrage sur des instances
   dont personne n'a touché la configuration.
3. **Un index NON unique ne peut JAMAIS échouer sur des données existantes** ; un index unique le
   peut. Les deux ne peuvent donc pas être gouvernés par la même déclaration — c'est l'argument le
   plus fort de cette fiche. Une annotation explicite laisse celui qui la pose regarder ses données
   d'abord.

---

## 5. Pièges lus, à connaître avant d'écrire le correctif

**5.1 — La vérification ignore un champ nul, un index unique non.**
`checkUnicityConstraint` sort dès que la valeur est nulle
(`api/core/src/main/java/com/garganttua/api/core/expression/EntityConstraintExpressions.java:143-146`) :

```java
Object fieldValue = REFLECTION.getFieldValue(entity, fieldAddress.toString());
if (fieldValue == null) {
    return;
}
```

Aujourd'hui N documents peuvent donc porter `null` sur un champ unique. Un index unique MongoDB, lui,
refuse le **second** document nul. Créer l'index sur une telle collection échoue, et même quand il se
crée il change le contrat. La piste est `partialFilterExpression`
(`{champ: {$exists: true, $ne: null}}`), qui reproduit exactement la sémantique actuelle — mais c'est
une décision, elle doit être écrite ; et si le choix est autre, il doit être écrit aussi.

**5.2 — Les noms de champs ne sont PAS traduits sur le chemin des filtres.**
`FilterMapper.map` (`api/core/src/main/java/com/garganttua/api/core/filter/FilterMapper.java:40-43`)
passe le filtre tel quel :

```java
// For now, pass through the filter as-is without field name mapping
// A full implementation would map entity field addresses to DTO field addresses
IFilter clonedFilter = filter.clone();
```

La javadoc de la classe le redit (`:17-23`). Nous ne demandons **pas** de régler cela (§6) — nous le
signalons parce qu'un index doit être posé sur le nom **du document**, et qu'un correctif qui
poserait l'index sur le nom de l'entité produirait un index que les requêtes n'utilisent jamais :
aucune erreur, juste un balayage complet à chaque lecture, et l'unicité non tenue **malgré** l'index.
`MongoDao.translateToDtoField` (§3.5) est le bon côté du mur.

**5.3 — Le précédent direct d'une annotation qui ne fait rien en silence.**
`@EntityUnicities` et `@EntityMandatories`
(`api/commons/src/main/java/com/garganttua/api/commons/entity/annotations/EntityUnicities.java`,
`…/EntityMandatories.java`) sont **déclarées et lues nulle part** :
`grep -rn "EntityUnicities\|EntityMandatories" --include=*.java .` ne rend que leur propre
définition. Elles sont pourtant documentées comme actives dans `api/commons/README.md:15` et
`:80-81`. Une annotation qu'on pose de bonne foi et qui ne fait rien est exactement le risque que
`@EntityIndexed` courrait si le §3.0 n'était pas traité d'abord.

---

## 6. Ce que cette fiche ne demande PAS

- **Pas de changement de la sémantique de l'unicité déclarée.** `validateUnicity` doit continuer à
  faire ce qu'elle fait, y compris son 409 et son passage sur un champ nul. Nous en dépendons, et
  elle fait bien la moitié du travail.
- **Pas de création d'index à partir de l'unicité déclarée** (§4). Seule l'annotation pose des
  index ; l'unicité déclarée mérite un avertissement, pas un effet de bord.
- **Pas de suppression ni de modification d'un index existant.** Purement additif, comme
  `PgSchemaManager`. Un index posé à la main par un exploitant, ou par une migration, ne doit jamais
  être touché — y compris quand sa définition diffère de celle que l'annotation décrit : dans ce cas
  un `WARN` qui nomme l'écart, et rien d'autre.
- **Pas de gestion d'index pour un DAO qui ne sait pas indexer.** Un DAO qui ne traite pas
  l'annotation doit l'ignorer en silence — c'est déjà le contrat de `registerDomain` (*« no-op for
  DAOs that don't care »*, `DomainInterfaceSupport.java:30`). Nous ne demandons cela que pour
  MongoDB.
- **Pas de résolution de la traduction des filtres.** §5.2 est signalé, pas demandé.
- **Pas de verrouillage optimiste, pas de champ de version, pas de transaction.** L'index suffit à
  l'invariant qui nous occupe, et la synchronisation d'ALPHA20 couvre déjà la fenêtre côté API.
- **Pas de changement de défaut.** Une application qui ne pose aucune `@EntityIndexed` doit continuer
  à démarrer exactement comme aujourd'hui, sans un index de plus et sans une requête de plus au
  démarrage — à l'avertissement du §3.2 près, qui est du journal.

---

## Réponse de la plateforme — 2026-09-23

**Traitée en entier, préalable compris.** Sur `main`, à paraître dans `3.0.0-ALPHA24`.

Vous demandiez d'abord qu'on vous confirme ou qu'on vous démente sur le §3.0. **Nous vous
confirmons, et le défaut est plus large que votre fiche.** Voici d'abord cela, parce que c'est ce
que vous attendiez pour engager la suite.

### 1. Le préalable — confirmé, élargi, et corrigé

**Confirmé.** Les trois générateurs écrivaient `new Annotation[0]` en dur, et un descripteur
engendré rendait donc toujours zéro annotation.

**Plus large que vous ne le disiez, sur deux points.**

- Ce n'est pas seulement le champ : `AOTMethodSourceGenerator` et `AOTConstructorSourceGenerator`
  faisaient exactement la même chose. Toute annotation de **méthode** et de **constructeur**
  disparaissait aussi. C'est probablement la moitié la plus dangereuse : une entité
  `@Reflected(queryAllDeclaredMethods = true)` produit un tableau de méthodes non vide, et toute
  détection d'annotation sur méthode y voyait le vide.
- Votre nuance sur les deux régimes est juste, mais la perte est **plus étroite** que vous ne le
  craigniez : elle ne touche que la vue d'ENSEMBLE (`getDeclaredFields()`). Une recherche par NOM
  (`findDeclaredField`, `getField(name)`) retombait déjà sur la classe vivante, tableau partiel ou
  non. C'est ce qui explique que votre panne se manifeste à `find(address)` et non à `address(...)`.

**Votre §3.0, point 4, reproduit tel quel.** Une constante `public static final` sur une entité
mappée fait bien échouer `entity().id("id")`. Le point d'étranglement exact est
`MemberLookup.getField`, qui parcourt `getDeclaredFields()` — la vue tronquée. L'exception est
non vérifiée et personne ne la rattrape, donc l'API refuse de démarrer.

**Ce que nous avons fait, et qui va au-delà de votre demande.** Vous demandiez que les annotations
réelles soient émises. Nous l'avons fait — `AOTAnnotations.ofField/ofMethod/ofConstructor`, appelé
dans le constructeur du descripteur, sur le gabarit de ce que le générateur de classe fait déjà pour
les types, et rendant un tableau vide plutôt que de lever si le membre n'est pas résoluble : un
descripteur ne doit jamais faire échouer un chargement de classe. Mais nous avons aussi traité la
cause de votre régime n°2 : **le générateur émet désormais des descripteurs pour les membres
PRIVÉS**, en accès réflexif (les liaisons directes restent réservées aux membres non privés, elles
ne peuvent pas contourner la visibilité). Les vues d'ensemble redeviennent donc **complètes**, et
les annotations des champs privés sont enfin portées par le chemin AOT, plus par le repli.

**Conséquence pour vous :** votre règle interne « aucune constante dans une entité mappée » n'a plus
lieu d'être. Nous vous suggérons de la lever après une montée en ALPHA24, pas avant.

**Un défaut que vous n'aviez pas vu, trouvé en vérifiant le vôtre.** Une classe imbriquée
`@Reflected` s'enregistrait sous son nom **pointé** (`Outer.Inner`) alors que la recherche utilise
`clazz.getName()`, donc le nom **binaire** (`Outer$Inner`). Son descripteur n'était **jamais**
trouvé : elle retombait silencieusement sur la réflexion vivante — c'est-à-dire sur le chemin même
que l'AOT pur existe pour supprimer. Corrigé, avec un test qui le prouve.

**Ce que nous ne pouvons PAS vous affirmer.** Aucune image native n'a été construite pour cette
livraison : tout ce que nous disons du natif est une lecture de code, pas une mesure — nous vous
devons la même distinction que celle que vous tenez. Ce que la lecture établit : les descripteurs
sont `initialize-at-build-time`, donc l'appel s'exécute à la construction de l'image et les objets
d'annotation sont figés dans le tas, exactement comme ceux que `<Class>.class.getAnnotations()`
produit déjà au niveau type ; et `GarganttuaAotFeature` enregistre déjà les champs, méthodes et
constructeurs de toutes les classes de l'index `@Reflected`, ce qui couvre vos entités dès lors que
votre build passe l'`IndexedAnnotationProcessor`. **Une mesure de votre côté sur une vraie image
reste la seule chose qui tranchera.** Elle nous intéresse.

Trois limites du natif restent ouvertes, et nous préférons les nommer :
- les annotations de **paramètres** restent récupérées à l'exécution, donc invisibles en AOT pur ;
- un membre dont la signature nomme un type qu'un descripteur de premier niveau ne peut pas désigner
  (classe imbriquée privée) perd silencieusement ses annotations — aucun cas actif aujourd'hui dans
  le réacteur, et le repli est un tableau vide, jamais une exception ;
- une classe imbriquée **privée** annotée `@Reflected` engendre toujours un descripteur qui ne
  compile pas. Antérieur à cette livraison, non traité.

### 2. `@EntityIndexed` — livrée dans la forme que vous proposiez

Sur le gabarit d'`EntityUnicity`, avec vos quatre attributs et votre nom (pas `@Indexed`, pour la
raison que vous donnez) :

```java
@EntityIndexed(unique = true)                    // portée tenant par défaut -> index (tenantId, champ)
@EntityIndexed(unique = true, scope = system)    // index global
@EntityIndexed(kind = geo)                       // 2dsphere
```

- **Nom dérivé stable**, fonction pure de la déclaration : `gg_<champ>_<portée>_<type>_<unique|idx>`,
  par exemple `gg_email_tenant_standard_unique`. Il ne bouge pas d'une version à l'autre, donc on
  retrouve l'index au lieu d'en créer un second.
- **Portée tenant = index composite, locataire EN PREMIER**, comme vous le demandiez, pour qu'il
  serve aussi les lectures filtrées par locataire.
- **Nom de champ traduit côté document** via `translateToDtoField` — votre §5.2 : nous n'avons pas
  touché à la traduction des filtres, et l'index est posé du bon côté du mur.
- **Déclarable aussi par le DSL**, sans annotation (`entity().index(...)`, neuf surcharges).

### 3. Votre §5.1 — vous aviez raison sur la sémantique, tort sur la formule

La sémantique est bien celle que vous décrivez, et nous la reproduisons : plusieurs documents nuls
ou absents restent admis, comme `validateUnicity` les admet.

Mais la formule que vous proposiez, `{champ: {$exists: true, $ne: null}}`, **est refusée par
mongod** (erreur 67 : un `partialFilterExpression` n'accepte pas `$ne`). L'index utilise donc
`{champ: {$type: [… tous les types sauf null …]}}`, qui a exactement le même effet. Mesuré, pas
supposé.

### 4. Le §3.3 — le point qui décidait du déploiement

Tenu tel que vous le demandiez : **un index unique impossible à créer ne fait PAS échouer le
démarrage.** L'avertissement nomme le domaine, le champ, et la requête d'agrégation prête à coller
qui liste les doublons.

Le mode se règle par `mongodb.index.auto` : `create` (défaut), `none`, `strict`. Le mode strict est
celui qui refuse de démarrer — celui qui l'a demandé sait pourquoi son instance s'arrête.

Une divergence assumée avec le gabarit `PgSchemaManager` que vous citiez : **pas de verrou entre
instances.** La cause côté PostgreSQL est que `CREATE TABLE IF NOT EXISTS` n'est pas atomique ;
`createIndex` l'est, il est idempotent et sérialisé par le serveur. Ajouter un verrou aurait été
recopier la forme sans la raison.

Purement **additif**, comme vous le demandiez : rien n'est supprimé, renommé ni retypé. Un index
existant dont la définition diffère de celle qui est déclarée donne un avertissement qui nomme
l'écart, et rien d'autre.

### 5. La mesure — votre 30 sur 30

Rejouée ici, dans les mêmes conditions : 30 tours, deux fils synchronisés sur une barrière écrivant
la même valeur dans le même locataire, par le chemin d'écriture réel.

| | Avant | Après |
|---|---|---|
| tours ayant produit un doublon | 30 / 30 | **0 / 30** |
| écritures refusées par la base | 0 | 30 / 30 |

La portée est mesurée aussi : deux locataires portent la même valeur sous portée `tenant`, et le
second est refusé **par la base** sous portée `system`.

### 6. Ce qu'il faut retenir, et qui peut surprendre

**Une `@EntityUnicity` seule ne crée aucun index** — c'est ce que vous demandiez au §4, et nous
l'avons suivi. Pour que la base tienne la contrainte, il faut écrire **en plus**
`@EntityIndexed(unique = true)` avec la **même portée**. À défaut, le `WARN` du §3.2 nomme le champ
au démarrage et dit que la base ne tient rien.

Deux conséquences de ce choix, à connaître :
- tant que vous n'ajoutez pas l'annotation, la course lecture-puis-écriture que vous décrivez reste
  entièrement ouverte : l'avertissement ne protège rien, il prévient ;
- l'avertissement considère une unicité tenue seulement par un index unique de **même adresse ET
  même portée**. Une unicité `tenant` adossée à un index unique `system` — donc plus strict — est
  quand même signalée. Délibéré : ce n'est pas la contrainte déclarée.

Enfin, `@EntityGeolocalized` reste ce qu'elle était : une déclaration que le résumé de démarrage
affiche. C'est `@EntityIndexed(kind = geo)` qui pose le `2dsphere` dont votre §1.2 a besoin. Les
lier serait exactement l'effet de bord que votre §4 refuse.
