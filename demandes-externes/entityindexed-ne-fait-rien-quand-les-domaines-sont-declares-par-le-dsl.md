# `@EntityIndexed` ne fait rien quand les domaines sont déclarés par le DSL, et ne le dit pas

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur de garganttua-api v3, AOT pur, image native, DAO MongoDB)
**Date :** 2026-09-24
**Version constatée :** garganttua-api `3.0.0-ALPHA24` (dépôt à `2f05bf0`)
**Gravité :** haute — et la gravité ne tient pas à la taille du correctif, elle tient à la FORME de
la panne. L'annotation **ne rate pas, elle ne fait rien** : le démarrage réussit, aucun journal ne
sort, et la base n'a simplement pas l'index. Le consommateur croit l'unicité tenue par le serveur de
base de données ; elle est tenue par personne. C'est exactement l'état que la fiche
[aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas](aucun-index-nest-pose-et-lunicite-declaree-ne-tient-pas.md)
demandait de refermer, et qui se rouvre pour qui déclare ses domaines à la main. **Mesuré chez nous
à 30 doublons sur 30 tours** — le chiffre d'avant la livraison, à l'identique.
**Statut de la constatation :** **MESURÉ** pour le comportement (index absents, doublons, et le
contre-essai par le DSL) ; **LU** pour la cause, établie dans les sources de ce dépôt et citée
ligne à ligne.

---

## 1. Symptôme

### 1.1 Les neuf clés annotées ne produisent aucun index — **MESURÉ**

Banc chez palliad le 2026-09-24, sur `3.0.0-ALPHA24`, MongoDB de développement, base jetable, **API
réellement montée** (pipeline CRUD complet, pas un appel direct au DAO). Les neuf entités de notre
registre de facturation portent, sur leur clé métier :

```java
@EntityIndexed(unique = true, scope = UnicityScope.system)
private String cleMetier;
```

| Sonde | Ce qui est mesuré |
|---|---|
| **index en base après montage de l'API** | **0 index créé sur 9 attendus.** Aucun `gg_*` dans `listIndexes`. |
| **concurrence, 2 fils, 30 tours** | **30 tours sur 30 produisent DEUX lignes de même clé.** Aucun refus, aucun journal. |
| **la même déclaration passée par le DSL** — `entity().index(champ, true, UnicityScope.system)` | **9 index sur 9**, nommés `gg_<champ>_system_standard_unique`, `unique: true`, avec leur `partialFilterExpression` — et **0 doublon sur 30 tours**. |

Le contre-essai est la partie qui tranche : **même base, même entité, même champ, même portée, même
version du cadre.** Seule change la façon de déclarer. Ce n'est donc ni un défaut du DAO, ni un
défaut de `MongoIndexManager`, ni une mauvaise portée — les deux chemins ne se rejoignent
simplement jamais.

### 1.2 Rien ne le signale — **MESURÉ**

Démarrage complet, journal à `DEBUG` : aucune ligne ne nomme `EntityIndexed`, aucune ne dit qu'une
annotation n'a pas été lue, aucune ne dit qu'un domaine déclare zéro index. Le résumé de démarrage
affiche les domaines et leurs réglages comme d'habitude. L'API monte, sert, et écrit.

C'est ce point qui fait la gravité, plus que l'absence d'index elle-même : **une annotation qui
échoue bruyamment se corrige dans la minute ; une annotation qui ne fait rien se corrige le jour où
un comptable trouve deux abonnements actifs pour le même client.**

---

## 2. La cause, lue dans les sources de ce dépôt

### 2.1 `@EntityIndexed` n'a qu'UN lecteur, et c'est le scanner de paquets — **LU**

Une recherche sur tout le réacteur ne trouve qu'un seul site de lecture hors tests :

> `api/core/src/main/java/com/garganttua/api/core/entity/EntityAnnotationScanner.java:290-291`
>
> ```java
> for (String addr : reflection.findFieldAddressesWithAnnotation(entityClass, IClass.getClass(EntityIndexed.class), true)) {
>     applyDeclaredIndex(reflection, entity, entityClass, addr);
> }
> ```

`applyDeclaredIndex` (`:340-351`) fait ensuite exactement ce qu'il faut — il rappelle
`entity.index(address, declared.unique(), declared.scope(), declared.kind(), declared.name())`.
Le pont vers le DSL existe, il est juste, il n'est jamais emprunté.

### 2.2 Le scanner sort immédiatement quand aucun paquet n'est déclaré — **LU**

> `api/core/src/main/java/com/garganttua/api/core/entity/EntityAnnotationScanner.java:110-113`
>
> ```java
> public void scan() throws ApiException {
>     if (this.packages.isEmpty()) {
>         return;
>     }
> ```

Et son unique point d'appel :

> `api/core/src/main/java/com/garganttua/api/core/api/ApiBuilder.java:385`
>
> ```java
> new com.garganttua.api.core.entity.EntityAnnotationScanner(this, this.packages).scan();
> ```

`this.packages` (`ApiBuilder.java:88`) n'est alimenté que par `withPackage(...)` / `withPackages(...)`
(`:296-309`) et `packages(String...)` (`:489-496`). **Un consommateur qui n'appelle aucune de ces
trois méthodes a un ensemble vide, donc un scanner qui rend la main à la première ligne, donc une
annotation qui n'est lue nulle part.** C'est notre cas : `withPackage` / `packages` n'apparaît dans
aucune source de nos six modules back.

### 2.3 Et c'est la forme que la documentation met en avant — **LU**

Ce n'est pas un usage exotique de notre part. L'exemple de démarrage minimal du dépôt déclare ses
domaines à la main, sans un seul paquet :

> `api/javalin/README.md:82-96`
>
> ```java
> IApi api = ApiBuilder.builder()
>     .superTenantId("SUPER_TENANT")
>     .domain(IClass.getClass(User.class))
>         .entity()
>             .id("id").uuid("uuid").tenantId("tenantId")
>         .up()
>     …
> ```

Même forme dans `api/interface/interface-rest/README.md:193`. C'est la forme que nous employons
partout, et elle est présentée comme la voie normale.

### 2.4 Rien dans la documentation ne pose le balayage comme condition — **LU**

Les quatre endroits qui décrivent `@EntityIndexed` présentent l'annotation et le DSL comme **deux
écritures d'une même chose**, avec un « ou » :

| Source | Ce qui est écrit |
|---|---|
| `api/dao/dao-mongodb/README.md:165` | *« what the entity declared through `@EntityIndexed` on a field, **or** `entity().index(...)` on the DSL »* |
| `api/starters/starter-mongodb/README.md:76` | *« A domain declares an index with `@EntityIndexed` on the entity field, **or** with `entity().index(...)` on the DSL »* |
| `api/commons/README.md:82` | décrit les quatre attributs, sans condition |
| `api/commons/src/main/java/com/garganttua/api/commons/entity/annotations/EntityIndexed.java:10-59` | le javadoc entier — la raison d'être, la stabilité du nom dérivé, la portée composite — **et pas un mot sur le balayage** |

Un lecteur qui s'appuie sur l'une de ces quatre pages choisit l'annotation en pensant choisir une
notation, pas un chemin d'exécution.

### 2.5 Les deux tests qui couvrent l'annotation passent tous deux par le balayage — **LU**

C'est l'angle mort qui explique que le défaut ait pu être livré vert :

- `api/core/src/test/java/com/garganttua/api/core/integ/indexscan/EntityIndexAnnotationScanTest.java:160`
  — `((ApiBuilder) builder).withPackage(SCANNED_PACKAGE);` **avant** les `builder.domain(...)` des
  lignes 162 et 167 ;
- `api/dao/dao-mongodb/src/test/java/com/garganttua/dao/mongodb/e2eindex/IndexedApiFixture.java:191`
  — idem, `withPackage(SCANNED_PACKAGE)` ligne 191, `domain(...)` lignes 194 et 199. Le commentaire
  de la ligne 57 le dit même explicitement : *« The fixture lives in its own package so that
  `withPackage` scans these two entities »*.

Les deux bancs déclarent donc **et** les paquets **et** les domaines. Aucun n'éprouve la
déclaration par le DSL seul — c'est-à-dire la configuration de l'exemple de votre propre README.

### 2.6 Une seconde condition, qu'il faut connaître avant de croire qu'un `packages(...)` suffirait — **LU**

Ajouter un paquet ne nous aurait rien donné non plus. `pairEntitiesWithDtos`
(`EntityAnnotationScanner.java:132-160`) ne retient une classe que si elle porte `@Entity` **et**
qu'une classe `@Dto(entityClass = …)` correspondante existe ; sans quoi la classe est écartée
(`log.warn("No @Dto found for @Entity {}; skipping", …)`). Nos entités portent `@Reflected` et rien
d'autre, et nos DTO sont construits par le DSL.

**Conséquence, et c'est ce qui rend le défaut structurel plutôt qu'accidentel :** `@EntityIndexed`
n'est pas une annotation « que le balayage active en plus » — c'est une annotation **réservée au
style d'assemblage par annotations**. Le style d'assemblage par DSL, qui est l'autre moitié du
produit, ne la lit jamais.

### 2.7 Le contrôle qui AURAIT pu l'attraper regarde délibérément ailleurs — **LU**

`ApiSummary.warnUnicitiesWithoutIndex` (`api/core/src/main/java/com/garganttua/api/core/api/ApiSummary.java:104-119`)
parcourt `entity.unicities()` et signale celles qu'aucun index n'adosse. Il fonctionne bien, et son
message est bon. Mais il part de l'**unicité** : une entité qui déclare `@EntityIndexed` sans
déclarer d'unicité — le cas des neuf nôtres, l'index unique en base ÉTANT la contrainte — ne
traverse jamais la boucle. Le contrôle ne peut pas voir ce qui n'est pas entré dans la définition.

---

## 3. Ce qui aiderait

Nous proposons, nous ne tranchons pas : la forme appartient à la plateforme, et les trois pistes
ci-dessous ne sont pas exclusives — la troisième est utile même si les deux premières sont retenues.

### 3.1 Que l'annotation soit lue à la CONSTRUCTION du domaine, quel que soit le style

La forme qui refermerait le défaut plutôt que de le signaler : au moment où un domaine est construit
— par `apiBuilder.domain(IClass)` comme par le scanner —, les champs de l'entité sont relus et
`@EntityIndexed` produit les mêmes règles que `applyDeclaredIndex` produit aujourd'hui. Le pont
existe déjà et il est court ; ce qui manque est le second appelant.

Le critère d'acceptation tient en une phrase : **une entité annotée déclarée par
`apiBuilder.domain(IClass.getClass(X.class))`, sans aucun paquet, doit porter les mêmes
`EntityIndexRule` que la même entité découverte par le balayage.** Un test symétrique des deux
existants, sans `withPackage`, le dirait.

Deux points que nous signalons parce qu'ils nous ont retenus de demander cette piste seule :
- si l'annotation devient lisible sans `@Entity` ni `@Dto`, la règle d'éligibilité change — et la
  bonne réponse là-dessus est la vôtre, pas la nôtre ;
- une entité qui déclarerait à la fois l'annotation et `entity().index(...)` sur le même champ ne
  doit produire qu'un index. Le nom dérivé étant une fonction pure de la déclaration
  (`EntityIndexRule.derivedName`, `api/commons/src/main/java/com/garganttua/api/commons/entity/EntityIndexRule.java:70-78`),
  deux règles identiques portent déjà le même nom et `createIndex` est idempotent — mais deux règles
  qui ne diffèrent que par `unique` donneraient bien deux index, et c'est voulu.

### 3.2 À défaut : un AVERTISSEMENT au montage, nommant toute entité annotée sans règle

Si la première piste ne convient pas — parce que le balayage doit rester la condition, ce qui est
une décision recevable —, alors ce qui doit disparaître est le **silence**, pas la condition. Un
`WARN` au montage, nommant le domaine, la classe d'entité et le champ :

> *le champ `X.cleMetier` porte `@EntityIndexed` mais aucune règle d'index n'en découle : cette
> annotation n'est lue que par le balayage de paquets, que cette API n'active pas. Déclarez l'index
> par `entity().index(...)`, ou déclarez le paquet.*

**`ApiSummary` est le bon endroit**, et c'est même l'argument principal de cette piste : le contrôle
y existe déjà, dans le même fichier, à la même étape, avec le même ton — `warnUnicitiesWithoutIndex`
dit « vous avez déclaré une contrainte que la base ne tient pas ». Ce qui manque est la phrase
symétrique : « vous avez déclaré un index qui n'est arrivé nulle part ». Le squelette,
`isBackedByUniqueIndex` compris (`ApiSummary.java:121-127`), est déjà écrit.

Une difficulté que nous devons signaler plutôt que de la laisser découvrir : au moment du résumé, la
définition ne porte que ce qui y est ENTRÉ. Constater l'annotation suppose donc de relire la classe
d'entité à ce moment-là, ce qui est une lecture réflexive de plus au démarrage. C'est le coût de
cette piste, et il est réel en AOT pur.

### 3.3 Dans tous les cas : que la documentation dise la condition

Indépendamment des deux précédentes, les cinq endroits du §2.4 devraient porter la condition
d'activation. Le javadoc de l'annotation d'abord, puisque c'est lui qu'on lit au moment où l'on
choisit. Un « ou » entre deux formes dont l'une ne marche que sous condition est ce qui nous a
coûté la journée : nous avons choisi l'annotation parce qu'elle se lit mieux, et nous l'avons crue
équivalente parce que la documentation l'écrit comme équivalente.

### 3.4 L'ironie utile, parce qu'elle désigne un GENRE et pas un accident

Ce défaut a la même forme que celui que votre réponse à la fiche précédente a traité sous notre
§5.3 : `@EntityUnicities` et `@EntityMandatories`, déclarées, documentées actives, et **lues nulle
part** — supprimées en ALPHA24 pour cette raison. `@EntityIndexed` n'est pas lue nulle part, elle
est lue sur un seul des deux chemins d'assemblage ; l'effet pour celui qui est sur l'autre chemin
est le même, et il est plus traître, puisque l'annotation *marche* dans les tests et dans les
exemples annotés.

Ce n'est pas un reproche — c'est une suggestion de contrôle : **une annotation publique dont le
lecteur unique est conditionnel mériterait, à l'échelle du réacteur, une vérification qui la refuse
ou la signale.** Nous n'avons pas de forme à proposer pour ce contrôle-là ; nous signalons le genre.

---

## 4. Ce que cette fiche ne demande PAS

- **De changer le DSL.** `entity().index(champ, unique, portée)` fonctionne, il est mesuré ici à
  9 index sur 9 et 0 doublon sur 30, et c'est ce que palliad emploie depuis. Les neuf surcharges
  livrées en ALPHA24 sont exactement ce qu'il nous fallait. Rien à y toucher.
- **D'imposer le balayage de paquets.** Nous ne demandons pas que `.packages(...)` devienne
  obligatoire, ni qu'il soit activé par défaut, ni qu'une API sans paquet soit refusée. Le style
  d'assemblage par DSL est un choix, pas un oubli.
- **De créer des index à partir de l'unicité déclarée.** Déjà écarté par nous au §4 de la fiche
  précédente, pour la raison qui vaut toujours : un index est un effet de bord sur la base, il doit
  être demandé. Votre livraison a suivi ce choix et nous n'y revenons pas.
- **De rendre `@EntityIndexed` prioritaire sur le DSL, ou l'inverse.** La question ne se pose pas
  chez nous : nous avons retiré les annotations.
- **De traiter cela comme une régression d'ALPHA24.** Ce n'est pas une régression : l'annotation
  est neuve, et c'est sa première rencontre avec un consommateur qui n'assemble pas par annotations.
- **Une mesure en image native.** Tout ce qui précède est mesuré sur JVM. Nous ne prétendons rien
  du natif ici, et nous vous devons la même distinction que celle que vous tenez.

---

## 5. Ce que nous avons fait en attendant

Les neuf annotations ont été **retirées** de nos entités, et non laissées en place « pour le jour
où ». La raison tient en une phrase, et elle explique aussi pourquoi cette fiche existe : *une
déclaration décorative se lit comme une garantie, et celle-ci aurait fini par justifier la
suppression de la ligne qui, elle, agit.* Les neuf index sont déclarés par le DSL, à côté de leur
unicité, avec un commentaire qui dit pourquoi.

---

## Réponse de la plateforme — 2026-10-15

**Corrigée par votre piste 3.1, élargie à toute la famille, sous une condition que nous posons :
la détection automatique.** Sur `main`, à paraître dans `3.0.0-ALPHA25`. Votre lecture de la cause
est exacte ligne pour ligne, contre-essai compris ; rien à rectifier.

### La règle retenue

**Les annotations d'entité sont lues quand l'api détecte automatiquement (`autoDetect(true)`), quel
que soit le style d'assemblage.** Un domaine trouvé par le balayage et un domaine déclaré à la main
par `apiBuilder.domain(...)` reçoivent le même traitement. Une api construite sans détection
automatique n'en lit aucune — comme elle ne détecte ni sérialiseur, ni protocole, ni configuration
automatique.

C'est votre §3.4 qui a décidé de la forme. Lire la seule `@EntityIndexed` en style DSL aurait
refermé votre cas et laissé le même piège sous `@EntityUnicity`, `@EntityMandatory`, les crochets
`@EntityBefore*` / `@EntityAfter*`, `@AuthorizeCreate` / `@AuthorizeUpdate` : toutes étaient lues
par le balayage seul. La condition est maintenant une, et elle vaut pour toute la famille.

### Ce que vous obtenez

`ApiBuilder.doAutoDetection` passe au scanner les classes d'entité que le DSL a déjà déclarées.
Celles que le balayage n'a pas trouvées reçoivent tout le câblage d'entité du scanner — marqueurs
de type, champs, crochets, rôle de clé — **sauf l'étape DTO**, qui appartient au DSL. Votre §2.6 ne
s'applique donc plus à ce chemin : une entité déclarée par le DSL n'a besoin ni de `@Entity` ni d'un
`@Dto` pour que ses annotations de champ soient lues.

Les deux points que vous signaliez au §3.1 sont tranchés :

- **Déclarer deux fois produit une seule règle.** Une unicité, une obligation ou un index identiques
  déclarés par le DSL et par l'annotation sont enregistrés une fois ; deux index qui ne diffèrent que
  par `unique` restent deux, comme vous le vouliez. Même chose pour un crochet : la même méthode
  liée deux fois au même crochet ne s'exécute qu'une fois.
- **Le DSL l'emporte sur les valeurs uniques.** `@EntityId`, `@EntityUuid`, `@EntityTenantId` ne
  remplissent la déclaration que si le DSL n'en a pas fait. Les drapeaux CRUD de `@Entity` ne
  peuvent que fermer une opération, jamais rouvrir ce que le DSL a fermé (c'était déjà le cas).

Le critère d'acceptation que vous posiez, symétrique des deux bancs existants et sans `withPackage`,
est `api/core/src/test/java/com/garganttua/api/core/integ/indexscan/EntityAnnotationsOnDslDomainTest.java` :
une entité de votre forme (ni `@Entity` ni `@Dto`) déclarée à la main porte la règle
`gg_businessKey_system_standard_unique` et son unicité `system` ; le DSL garde son `id` face à un
`@EntityId` sur un autre champ ; la double déclaration donne une règle ; sans détection automatique,
aucune.

### Ce qui n'est pas fait

- **Votre piste 3.2, l'avertissement.** Il aurait fallu relire chaque classe d'entité pour constater
  une annotation que la configuration demande de ne pas lire — le coût réflexif que vous nommiez, et
  une contradiction avec la règle elle-même. La condition est dite à la place (ci-dessous).
- **Les annotations de DTO** (`@DtoId`, `@DtoUuid`, `@DtoTenantId`) d'un DTO déclaré par le DSL ne
  sont pas lues : le DSL d'un DTO exige déjà ces déclarations, il n'y a pas de silence à refermer.

### Votre §3.3, la documentation

La condition est écrite là où vous la cherchiez : le javadoc de `@EntityIndexed`, le README de
`api/commons` (en tête de la section des annotations d'entité, pour toute la famille), et les deux
« ou » de `dao-mongodb` et `starter-mongodb`.

### À vérifier chez vous

**Si votre `ApiBuilder` n'est pas en détection automatique, rien ne change pour vous**, et c'est
voulu. Vous avez retiré les annotations, donc rien ne change non plus s'il l'est — sauf si d'autres
annotations d'entité dorment sur vos classes : elles seront désormais appliquées. C'est la seule
conséquence de comportement de cette version pour un consommateur en style DSL, et elle est
annoncée dans le README de ce dossier.
