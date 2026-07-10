<!-- Source: showcase-site open-source pages (garganttua.com/open-source). Presentation/marketing content, ported verbatim. -->

# Garganttua

Garganttua regroupe trois bibliothèques Java open-source formant une chaîne de dépendances (core → api → events), sous licence MIT. **Garganttua Core** est la fondation (injection, réflexion, expressions, scripting, workflow) ; **Garganttua API** génère des APIs REST déclaratives multi-tenant et sécurisées ; **Garganttua Events** assure le routage d'événements.

- [Garganttua Core](#garganttua-core)
- [Garganttua API](#garganttua-api)
- [Garganttua Events](#garganttua-events)

---

## Garganttua Core

**Garganttua Core · 3.0.0-ALPHA08 · MIT**

### Le framework Java modulaire pour les apps qui ne peuvent pas se permettre d'être lourdes.

34 modules indépendants. Zéro dépendance circulaire. Java 25. GraalVM native-ready.

- **Mots-clés :** Modulaire · Léger · Native-ready
- **Badges :** 3.0.0-ALPHA08 · MIT · 842 fichiers · 285 suites de tests
- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Découvrir les modules](/open-source/core#modules)

---

### Pourquoi Core ?

#### Trois engagements qui changent tout

#### Modulaire par conception

Utilisez uniquement ce dont vous avez besoin. Chaque module est indépendant, avec des dépendances minimales et une architecture en couches strictement acyclique.

Pas de monolithe caché. Pas de dépendances transitives surprises. Vous choisissez votre surface d'API.

#### Performant par défaut

Indexation des annotations à la compilation plutôt que classpath scanning au démarrage. Génération de binders directs éliminant Method.invoke() sur les chemins critiques.

Compatible GraalVM Native Image pour un démarrage en millisecondes.

#### Pensé pour les développeurs

APIs fluides et type-safe avec le pattern Hierarchical Builder. REPL interactif pour prototyper vos scripts. Documentation module par module.

Convention over configuration, avec override explicite quand il faut.

---

### Architecture

#### Une architecture en 7 couches

Chaque module respecte une hiérarchie stricte : les couches basses ne dépendent jamais des couches hautes. Le résultat : un graphe de dépendances prévisible, sans surprises et sans cycles.

#### Foundation

Interfaces, builders, suppliers, lifecycle, mutex

Modules : `commons` · `dsl` · `supply` · `lifecycle` · `mutex`

#### Infrastructure

Réflexion (IClass, force access), conditions, exécution, crypto, configuration multi-format

Modules : `reflection` · `runtime-reflection` · `condition` · `execution` · `crypto` · `configuration`

#### Framework

Injection, properties, runtime, mapping, expressions, bootstrap

Modules : `injection` · `properties` · `runtime` · `mapper` · `expression` · `bootstrap`

#### Application

Scripts, workflows, console

Modules : `script` · `console` · `workflow`

#### Build Tools

Plugin Maven pour scripts

Modules : `script-maven-plugin`

#### Integration

Spring, Reflections, Redis, GraalVM native

Modules : `spring` · `reflections` · `mutex-redis` · `native`

#### AOT

Compilation anticipée, scanner & processor d'annotations, plugin Maven

Modules : `aot-commons` · `aot-reflection` · `aot-annotation-scanner` · `aot-annotation-processor` · `aot-maven-plugin`

---

### Showcase

#### Les modules clés

#### garganttua-injection

**Couche : Framework**

Conteneur d'injection de dépendances léger et compatible JSR-330. Scopes singleton et prototype, injection de propriétés, contextes enfants, détection de cycles, et instanciation zéro-réflexion optionnelle via binders compilés.

```java
IInjectionContext context = InjectionContext.builder()
    .provide(reflectionBuilder)
    .withPackage("com.garganttua")
    .propertyProvider(Predefined.PropertyProviders.garganttua.toString())
        .withProperty(String.class, "app.name", "MyApp")
        .up()
    .autoDetect(true)
    .build()
    .onInit()
    .onStart();
```

#### garganttua-expression

**Couche : Framework**

Langage d'expressions basé sur ANTLR4 avec évaluation type-safe. Appels de fonctions, invocations de méthodes, constructeurs, références de variables, et types génériques. L'évaluation produit des ISupplier<T> pour le calcul différé.

```java
// Appel de fonction avec arguments
context.expression("add(8, add(42, 30))")

// Transtypage avec résolution dynamique du type retour
context.expression("cast(String.class, @myVar)")

// Référence de variable + constructeur
context.expression(":(String.class, @myVar)")
```

#### garganttua-script

**Couche : Application**

Moteur de scripting complet avec sa propre grammaire ANTLR4, fonctions utilisateur, exécution conditionnelle via if(), gestion des erreurs, et synchronisation via mutex. Extension .gs, arguments positionnels, et fat JAR exécutable.

```gs
// Fonctions utilisateur avec scope isolé
validate = (data) => (
    result <- checkFormat(@data)
)
output <- validate(@input)

// Exécution conditionnelle
result <- if(equals(@mode, "prod"), (
    data <- fetchProd()
), (
    data <- fetchDev()
))

// Gestion d'erreur immédiate
result <- parse_json(@0) ! Exception.class => log("parse failed")
```

#### garganttua-workflow

**Couche : Application**

DSL fluide pour orchestrer des pipelines multi-étapes. Génère automatiquement du code Garganttua Script à partir de l'API builder. Les scripts inline sont isolés dans des groupes pour éviter les collisions de fonctions entre stages.

```java
Workflow workflow = WorkflowBuilder.create()
    .name("deploy-pipeline")
    .stage("build")
        .script("scripts/compile.gs")
        .script("scripts/test.gs")
        .up()
    .stage("deploy")
        .when("equals(@env, \"prod\")")
        .script("scripts/deploy.gs")
        .up()
    .build();

workflow.execute(input);
```

#### garganttua-configuration

**Couche : Infrastructure**

Chargement de configuration multi-format (JSON, YAML, XML, TOML, Properties) avec population automatique de builders. Mapping intelligent des clés (camelCase, kebab-case, snake_case), conversion de types, et intégration DI.

```java
var populator = ConfigurationBuilder.builder()
    .provide(reflectionBuilder)
    .withMappingStrategy("SMART")
    .strict(true)
    .build();

populator.populate(serverBuilder,
    new FileConfigurationSource("config.yaml"));
```

#### garganttua-reflection

**Couche : Infrastructure**

Façade de réflexion pluggable avec IReflection, IClass<T>, et providers priorisés. Binders type-safe avec évaluation lazy via ISupplier. Navigation par adresse objet (dot-notation) et résolution de méthodes. Accès forcé aux membres privés.

```java
// Construire la façade composite avec priorités
IReflection reflection = ReflectionBuilder.builder()
    .withProvider(new RuntimeReflectionProvider(), 10)
    .withScanner(new MyAnnotationScanner(), 10)
    .build();

// Accès à un champ imbriqué via dot-notation
Object value = reflection.getFieldValue(obj, "inner.secret");
```

---

### Du builder au runtime

#### En 4 étapes

#### 01 — Définissez

Créez vos builders avec le DSL fluide. Annotations @Configurable, @Step, @Expression pour déclarer vos intentions.

#### 02 — Compilez

L'annotation processor génère les index et les binders directs. Zéro configuration : le build Maven fait tout.

#### 03 — Assemblez

Le conteneur d'injection découvre vos beans, résout les dépendances, injecte les propriétés. Démarrage en millisecondes.

#### 04 — Exécutez

Lancez vos workflows, scripts, ou runtimes. Gestion d'erreurs, fallbacks, et routage conditionnel inclus.

---

### Chiffres clés

| Valeur | Label |
|--------|-------|
| 34 | modules indépendants |
| 842 | fichiers source Java |
| 285 | suites de tests |
| ~104k | lignes de code |
| 0 | dépendance circulaire |

---

### Différenciateur technique

#### Élimination de la réflexion sur les chemins critiques

La plupart des frameworks Java utilisent Method.invoke() et Constructor.newInstance() au runtime, avec un coût significatif en performance et en compatibilité native.

Garganttua Core prend une approche différente : un annotation processor génère des classes de binders directs à la compilation. Le résultat :

- Les fonctions @Expression appellent directement la méthode Java cible, sans passer par Method.invoke()
- Les beans @Singleton et @Prototype sont instanciés par new ClassName(...), pas par Constructor.newInstance()
- Configuration GraalVM Native Image simplifiée : @Reflected remplace @Native, moins de metadata à déclarer
- Démarrage plus rapide : pas de scanning au classpath, les index META-INF/garganttua/index/ sont pré-générés

**Activable par défaut. Désactivable via : -Dgarganttua.direct.binders=false**

**Compilation :**

```
@Expression("add")
static int add(int, int)
```

```
Annotation Processor
genère AddMethodBinder
```

→

**Runtime :**

```
addBinder.invoke(a, b)
= direct method call
NO Method.invoke()
```

```
DirectBinderRegistry
charge le binder
```

---

### Sous le capot

#### Stack technique

**Socle**

| Composant | Version |
|-----------|---------|
| Java | 25 |
| Maven | 3.8+ |
| ANTLR4 | 4.13.0 |
| Jackson | 2.15 |
| JLine3 | 3.25 |
| JUnit | 5.9 |

**Compatible**

| Composant | Version |
|-----------|---------|
| GraalVM | Native Image |
| Spring | 6.2 (binding optionnel) |
| Redis | mutex distribué |

---

### Quick start

#### Démarrage rapide

**Maven**

```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-injection</artifactId>
    <version>3.0.0-ALPHA08</version>
</dependency>
```

**Injection**

```java
// Construire la façade de réflexion (dépendance de build)
var reflectionBuilder = ReflectionBuilder.builder()
    .withProvider(new RuntimeReflectionProvider(), 10);

// Créer un contexte d'injection avec scan de package
var context = InjectionContext.builder()
    .provide(reflectionBuilder)
    .withPackage("com.garganttua")
    .propertyProvider(Predefined.PropertyProviders.garganttua.toString())
        .withProperty(String.class, "app.name", "MyApp")
        .up()
    .autoDetect(true)
    .build()
    .onInit()
    .onStart();

// Récupérer et utiliser un bean
var service = context.getBean(UserService.class);
service.process();

context.onStop();
```

**Workflow**

```java
var workflow = WorkflowBuilder.create()
    .name("data-pipeline")
    .variable("source", dataSource)
    .stage("extract")
        .script("scripts/extract.gs")
        .up()
    .stage("transform")
        .script("scripts/transform.gs")
        .up()
    .stage("load")
        .script("scripts/load.gs")
        .up()
    .build();

workflow.execute(input);
```

**Script (.gs)**

```gs
#!/usr/bin/env garganttua-script

// Fonction utilisateur réutilisable
fetchData = (url) => (
    response <- http_get(@url) ! Exception.class => log("request failed")
    result <- parse_json(@response)
)

// Exécution conditionnelle
data <- if(equals(@0, "prod"), (
    result <- fetchData("https://api.prod.com/data")
), (
    result <- fetchData("https://api.dev.com/data")
))

log(concatenate("Received: ", @data))
```

**Console REPL**

```shell
$ java -jar garganttua-console-3.0.0-ALPHA08-executable.jar

garganttua> help()
Available functions: help(), vars(), clear(), load(), man(), syntax(), exit()

garganttua> result <- concatenate("Hello", " World")
garganttua> vars()
result = "Hello World"

garganttua> man("concatenate")
concatenate(String, String) -> String
  Concatenates two strings.
```

---

### Cas d'usage

#### Pour quoi l'utiliser ?

#### Outils ligne de commande

Combinez garganttua-script et GraalVM Native Image pour créer des CLI tools Java qui démarrent en millisecondes. Le REPL interactif accélère le prototypage. Le fat JAR exécutable se déploie sans dépendances externes.

Modules concernés : `script` · `console` · `expression` · `bootstrap`

#### Microservices légers

Utilisez garganttua-injection comme alternative à Spring quand vous avez besoin d'un conteneur DI sans la surface d'API d'un framework full-stack. Injection JSR-330, lifecycle management, et configuration multi-format dans une empreinte mémoire réduite.

Modules concernés : `injection` · `configuration` · `lifecycle` · `bootstrap`

#### Pipelines de traitement

Orchestrez des workflows multi-étapes avec gestion d'erreurs, fallbacks, et exécution conditionnelle via if(). Le module workflow génère du code script optimisé à partir de votre API builder, avec isolation automatique des fonctions entre stages.

Modules concernés : `workflow` · `script` · `runtime` · `execution` · `condition`

---

### Open source

#### Open-source sous licence MIT

Garganttua Core est entièrement open-source. Le code, la documentation, et les tests sont publics et ouverts aux contributions.

- **Star le projet sur GitHub** — [github.com/garganttua/garganttua](https://github.com/garganttua/garganttua)
- **Contribuer** — Les pull requests sont bienvenues. Consultez les issues "good first issue" pour commencer. — [issues](https://github.com/garganttua/garganttua/issues)
- **Reporter un bug** — Ouvrez une issue sur le tracker GitHub. — [new issue](https://github.com/garganttua/garganttua/issues/new)
- **Contact** — [jeremy.colombet@garganttua.com](mailto:jeremy.colombet@garganttua.com)

---

### Prêt à explorer Core ?

842 fichiers source, 285 suites de tests. Lisez le code, ouvrez une issue, ouvrez une PR.

- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Retour aux projets](/open-source)

---

## Garganttua API

**Garganttua API · 3.0.0-ALPHA08 · MIT**

### Construire des APIs multi-tenant et sécurisées sans compromis.

Pipeline scriptable. Sécurité pluggable. Multi-tenancy native. API fluide. Framework-agnostic.

- **Mots-clés :** Multi-tenant · Sécurisé · Scriptable
- **Badges :** 3.0.0-ALPHA08 · MIT · Java 25 · GraalVM-ready
- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Découvrir le framework](/open-source/api#piliers)

---

### Pourquoi API ?

#### Trois engagements qui changent tout

#### Multi-tenancy native

L'isolation tenant est intégrée au cœur du framework, pas ajoutée après coup. Entités publiques, privées, partagées, propriétaires, masquables : la matrice d'accès couvre tous les cas d'un SaaS multi-tenant.

Super-tenant pour l'administration globale. Super-owner pour le bypass de propriété. Filtrage automatique à chaque requête. Désactivable en une ligne : multiTenant(false).

#### Sécurité pluggable

Composez votre chaîne d'authentification : login/password, PIN, challenge-response, token. Combinez avec une autorisation JWT configurable (RSA, HMAC). Changez de stratégie sans toucher au code métier.

Pas de lock-in sur Spring Security. Chaque opération définit ses propres exigences de sécurité.

#### Pipeline scriptable

Chaque requête traverse un pipeline en 8 étapes, du transport brut jusqu'à l'exécution. Chaque étape est un script modifiable indépendamment.

Hooks de lifecycle, binders, et workflows pour personnaliser sans tout réécrire. Mettez à jour la logique métier sans recompiler.

---

### Architecture

#### Un pipeline en 8 étapes

Chaque requête traverse un pipeline déterministe. Les étapes 1 à 3 gèrent le transport et le contexte. Les étapes 4 à 6 appliquent les règles métier et la sécurité. Les étapes 7 et 8 routent vers le bon handler et exécutent la logique.

**Chaque étape est un script. Modifiable. Testable. Indépendant.**

| # | Étape | Famille | Description |
|---|-------|---------|-------------|
| 1 | protocol | Transport | Extraction des données brutes (headers, body, params) |
| 2 | data_format | Transport | Désérialisation vers DTO / objets domaine |
| 3 | caller | Contexte | Construction du contexte appelant (tenantId, ownerId, autorités) |
| 4 | operation | Contexte | Détection de l'opération CRUD / use case / workflow |
| 5 | business | Métier | Validation tenant / owner / contraintes métier |
| 6 | security | Sécurité | Authentification et autorisation |
| 7 | multiplex | Routage | Routage vers le bon handler d'exécution |
| 8 | execution | Exécution | Scripts CRUD, use cases, workflows, ou authentification |

**execution se ramifie en :**

- CRUD (create, readAll, readOne, update, deleteOne, deleteAll)
- Use Cases
- Workflows
- Authentication

---

### Différenciateur clé

#### Isolation des données, intégrée au framework

Chaque entité déclare sa stratégie de visibilité via des annotations. Le framework génère automatiquement les filtres d'accès appliqués à chaque requête.

#### 5 annotations combinées librement

- `@EntityPublic` — Visible par tous, sans filtre tenant
- `@EntityTenant` — Appartient à un tenant (filtre tenantId)
- `@EntityOwned` — Appartient à un utilisateur (filtre ownerId)
- `@EntityShared` — Partageable entre tenants (champ shareWith)
- `@EntityHiddenable` — Masquable (champ hidden, visible au propriétaire)

#### Deux rôles privilégiés

- **Super-tenant** : accède à toutes les données, tous les tenants
- **Super-owner** : accède à toutes les entités, tous les propriétaires

Le filtre est appliqué *avant* l'exécution. Pas de risque d'oubli. Pas de fuite de données entre tenants.

#### Matrice d'accès

| Configuration | Public | Tenant | Owned | Hidden. |
|---------------|--------|--------|-------|---------|
| Blog public | ✓ | | | ✓ |
| Données tenant | | ✓ | | |
| Documents perso | | ✓ | ✓ | |
| Posts partagés | | ✓ | | ✓ |

#### Pas besoin de multi-tenancy ?

```java
ApiContextBuilder.builder()
    .multiTenant(false)    // désactive tout le tenant
    ...
```

Le framework passe en mode mono-tenant. Mode strict : toute tentative d'appeler superTenantId() ou tenant(true) lève une exception.

---

### Expérience développeur

#### Tout se configure via un DSL fluide

Un seul point d'entrée. Type-safe. Navigable avec `up()`. Pas de fichiers XML, pas d'annotations dispersées, pas de magie de classpath scanning.

```java
IApiContext context = ApiContextBuilder.builder()
    .superTenantId("SUPER_TENANT")               // Identifiant du tenant admin
    .domain(User.class)                          // Domaine auto-nommé "users"
        .entity()
            .id("id")                            // Clé primaire
            .uuid("uuid")                        // Identifiant métier (auto-généré)
            .tenantId("tenantId")                // Champ d'isolation tenant
            .ownerId("ownerId")                  // Champ de propriété
        .up()
        .dto(UserDto.class)                      // DTO avec mapping automatique
            .id("id")
            .uuid("uuid")
            .db(new MongoDao())                  // Connecteur de persistance
        .up()
        .security()                              // Sécurité du domaine
            .authentication()
                .loginPassword()                 // Méthode d'authentification
            .up()
            .authorization()
                .jwt()                           // Autorisation JWT
                .signable()
                .refreshable()
            .up()
        .up()
        .creation(true)                          // Active le CRUD create
        .readAll(true)                           // Active le CRUD readAll
        .readOne(true)                           // Active le CRUD readOne
        .workflow("deleteAll")
            .security().disable(true).up()       // Désactive la sécurité
        .up()
    .up()
    .build();                                    // Build immutable

context.onInit();
context.onStart();
```

#### Requêtes fluides : du builder à l'exécution

Construisez et exécutez vos requêtes en une seule chaîne fluide. Les raccourcis CRUD évitent la construction manuelle des `OperationDefinition`.

```java
// Depuis un domaine
products.request()
    .createOne(myProduct)                   // Operation + body en un appel
    .caller(caller)                         // Contexte appelant
    .execute();                             // Build + execute

// Lecture avec filtres
products.request()
    .readAll()                              // Operation readAll
    .filter(myFilter)                       // Filtre optionnel
    .page(pageable)                         // Pagination
    .sort(sort)                             // Tri
    .caller(caller)
    .execute();

// Raccourci depuis le contexte API
context.request("products")                 // Accès direct par nom de domaine
    .deleteOne("uuid-123")
    .caller(caller)
    .execute();

// Build en deux temps (inspection avant exécution)
IRequest request = products.request()
    .updateOne("uuid-123", body)
    .caller(caller)
    .build();                               // IRequest inspectable

IOperationResponse response = request.execute();
```

---

### Sécurité

#### Composez votre stratégie d'authentification

#### Login / Password

Authentification classique par identifiant et mot de passe. Encodage configurable (Bcrypt, SHA, PBKDF2). Champ "enabled" pour désactiver un compte sans le supprimer.

```java
.authentication()
    .loginPassword()
    .authenticator(User.class)
.up()
```

#### Code PIN

Authentification par code numérique avec compteur d'erreurs. Blocage automatique après N tentatives échouées. Idéal pour les applications mobiles ou les terminaux.

```java
.authentication()
    .pin()
    .authenticator(User.class)
.up()
```

#### Challenge-Response

Le serveur génère un défi avec une durée de validité. Le client répond avec la solution. Adapté aux scénarios MFA ou aux intégrations avec des systèmes externes.

```java
.authentication()
    .challenge()
    .authenticator(User.class)
.up()
```

#### JWT Authorization

Signature et validation de tokens JWT. Algorithmes RSA ou HMAC. Refresh tokens pour le renouvellement sans ré-authentification. Claims customisables.

```java
.authorization()
    .jwt()
    .signable()
    .refreshable()
.up()
```

---

### Architecture modulaire

#### Architecture en couches strictes

Chaque couche dépend uniquement des couches inférieures. Le module `commons` est un pur contrat : zéro logique, zéro dépendance externe. Les starters et la persistance sont optionnels et interchangeables.

#### garganttua-api-commons

**Couche : Contrats**

Couche de spécification pure : 40+ annotations (`@Entity*`, `@Authentication*`, `@Authorization*`), interfaces, enums et definitions immutables. Aucune logique métier — tout le reste en dépend.

#### garganttua-api-core

**Couche : Moteur**

Le cœur du framework : builders DSL, contexts runtime, pipeline scriptable, repository, method binders et expressions.

#### garganttua-api-bindings

**Couche : Bindings**

Wrappers autour des bibliothèques externes, un sous-module par lib : jackson, slf4j, jsonpath, mongodb, javalin.

#### garganttua-api-dao

**Couche : Persistance**

Abstractions DAO indépendantes du stockage, avec l'implémentation `garganttua-api-dao-mongodb`.

#### garganttua-api-starters

**Couche : Intégration**

Starters Spring Boot et Javalin : REST controllers auto-générés, adaptation entre l'injection Garganttua Core et le conteneur hôte.

#### garganttua-core

**Couche : Fondation**

reflection, injection, script, workflow, mapper.

> **Modules en migration (hors réacteur)** — `garganttua-api-security`, `garganttua-api-interface`, `garganttua-api-javalin` et `garganttua-api-native-image` sont temporairement commentés du réacteur, en attente de migration vers les conventions de la plateforme.

---

### Du builder au runtime

#### En 4 étapes

#### 01 — Configurez

Déclarez vos domaines, entités, DTOs et règles de sécurité via le DSL fluide. Un seul point d'entrée, type-safe, avec autocompletion IDE.

#### 02 — Buildez

Le framework construit les définitions immutables, génère les scripts de pipeline, wire les binders de lifecycle et les méthodes de sécurité.

#### 03 — Démarrez

Le contexte API initialise les domaines, démarre les interfaces (REST, etc.), crée les entités de startup, et ouvre les endpoints.

#### 04 — Servez

Chaque requête traverse le pipeline en 8 étapes. Filtrage tenant automatique. Sécurité appliquée. CRUD, use cases, ou workflows exécutés.

---

### Chiffres clés

| Valeur | Label |
|--------|-------|
| 8 | étapes de pipeline |
| 40+ | annotations déclaratives |
| 448 | fichiers source Java |
| 126 | tests d'intégration |
| 0 | dépendance à Spring (core) |

---

### Quick start

#### Démarrage rapide

**Maven**

```xml
<dependency>
    <groupId>com.garganttua</groupId>
    <artifactId>garganttua-api-core</artifactId>
    <version>3.0.0-ALPHA08</version>
</dependency>
```

**API CRUD en 20 lignes**

```java
// 1. Définir le domaine
IApiContext context = ApiContextBuilder.builder()
    .superTenantId("SUPER_TENANT")
    .domain(Product.class)
        .entity().id("id").uuid("uuid").tenantId("tenantId").up()
        .dto(ProductDto.class).id("id").uuid("uuid")
            .db(new MongoDao("products")).up()
        .creation(true).readAll(true).readOne(true)
        .update(true).deleteOne(true)
    .up()
    .build();

// 2. Démarrer
context.onInit();
context.onStart();

// 3. Utiliser
context.request("products")
    .createOne(myProduct)
    .caller(caller)
    .execute();
```

**Multi-tenant + sécurité**

```java
ApiContextBuilder.builder()
    .superTenantId("ADMIN")
    .domain(Document.class)
        .entity()
            .id("id").uuid("uuid")
            .tenantId("tenantId").ownerId("ownerId")
        .up()
        .dto(DocumentDto.class)
            .id("id").db(new MongoDao("documents")).up()
        .security()
            .authentication().loginPassword()
                .authenticator(User.class).up()
            .authorization().jwt()
                .signable().refreshable().up()
        .up()
        .creation(true).readAll(true).readOne(true)
        .update(true).deleteOne(true)
    .up()
    .build();
```

**Spring Boot**

```properties
# application.properties
com.garganttua.api.spring.scanPackages=com.myapp.domain
com.garganttua.api.spring.superTenantId=0
com.garganttua.api.interface.spring.rest.requestedTenantIdHeaderName=X-Tenant-Id

# Les endpoints REST sont auto-générés :
# POST   /products        -> create
# GET    /products        -> readAll
# GET    /products/{uuid} -> readOne
# PUT    /products/{uuid} -> update
# DELETE /products/{uuid} -> deleteOne
# DELETE /products        -> deleteAll

# Swagger/OpenAPI généré automatiquement.
```

---

### Cas d'usage

#### Pour quoi l'utiliser ?

#### Plateformes SaaS multi-tenant

Isolez les données de chaque client sans écrire une seule ligne de code de filtrage. Entités publiques, privées, partagées entre tenants, ou appartenant à un utilisateur spécifique. Le framework applique les filtres à chaque requête.

Idéal pour : CRM, ERP, marketplaces, outils collaboratifs

#### APIs sécurisées

Combinez login/password, PIN, challenge-response et JWT sans coupler votre code métier à un framework de sécurité. Chaque endpoint définit ses propres exigences. Changez de stratégie d'authentification sans refactoring.

Idéal pour : fintech, healthtech, applications réglementées

#### Backends mobiles et web

Générez automatiquement les endpoints REST et la documentation Swagger via le module Spring Boot. CRUD complet avec gestion du tenant et du owner via headers HTTP. Pagination, tri, et filtrage inclus.

Idéal pour : apps mobiles, SPAs, applications React/Next.js

---

### Comparaison

#### Pourquoi Garganttua API ?

| Fonctionnalité | Garganttua API | Spring Data REST | JHipster |
|----------------|:--------------:|:----------------:|:--------:|
| Multi-tenancy native | ✓ | — | ~ |
| Matrice de visibilité entités | ✓ | — | — |
| Sécurité pluggable | ✓ | ~ | ~ |
| Pipeline scriptable | ✓ | — | — |
| Framework-agnostic | ✓ | — | — |
| DSL type-safe | ✓ | — | ~ |
| GraalVM native-ready | ✓ | ~ | ~ |
| Zéro génération de code | ✓ | ✓ | — |
| Hooks de lifecycle | ✓ | ~ | ~ |
| Géolocalisation | ✓ | — | — |

✓ supporté nativement · ~ partiel · — non supporté

---

### Sous le capot

#### Stack technique

**Socle**

| Composant | Version |
|-----------|---------|
| Java | 25 |
| Maven | 3.8+ |
| Jackson | 2.17 |
| JUnit | 5 |
| Garganttua Core | 3.0.0-ALPHA08 (injection, reflection, script, workflow) |

**Compatible**

| Composant | Version |
|-----------|---------|
| GraalVM | Native Image |
| Spring Boot | 3.3 (intégration optionnelle) |
| MongoDB | via module DAO |
| Swagger | OpenAPI auto-généré |

---

### Open source

#### Open-source sous licence MIT

Garganttua API est entièrement open-source. Le code, la documentation, et les tests sont publics et ouverts aux contributions.

- **Star le projet sur GitHub** — [github.com/garganttua/garganttua](https://github.com/garganttua/garganttua)
- **Contribuer** — Les pull requests sont bienvenues. Consultez les issues "good first issue" pour commencer. — [issues](https://github.com/garganttua/garganttua/issues)
- **Reporter un bug** — Ouvrez une issue sur le tracker GitHub. — [new issue](https://github.com/garganttua/garganttua/issues/new)
- **Contact** — [jeremy.colombet@garganttua.com](mailto:jeremy.colombet@garganttua.com)

---

### Prêt pour l'API multi-tenant ?

Pipeline scriptable, sécurité pluggable, multi-tenancy native. Tout est sur GitHub.

- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Retour aux projets](/open-source)

---

## Garganttua Events

**Garganttua Events · 3.0.0-ALPHA08 · MIT**

### Le routage d'événements qui compile en workflows, pas en boîte noire.

Framework d'event-processing pluggable, multi-tenant et multi-cluster, construit sur Garganttua Core. Les messages traversent des pipelines configurables, définis par expressions — et chaque route compile en un véritable Workflow Garganttua Core.

- **Mots-clés :** Pluggable · Multi-tenant · Expression-based
- **Badges :** 3.0.0-ALPHA08 · MIT · Java 25 · Kafka-ready
- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Découvrir les connecteurs](/open-source/events#modules)

---

### Pourquoi Events ?

#### Trois engagements qui changent tout

#### Routes = workflows

Chaque `RouteDef` compile en un `IWorkflow` Garganttua Core. Les stages deviennent des scripts inline (`exchange <- expression(args)`) : vous héritez gratuitement de l'observabilité, de la gestion d'erreurs et du moteur d'exécution du cœur, sans réinventer un runtime événementiel.

Pas de moteur parallèle. Le routage réutilise les primitives du core.

#### Piloté par expressions

Les stages ne sont pas des classes Java de traitement, mais des fonctions annotées `@Expression` (`protocol_in`/`protocol_out`, `filter_in`/`filter_out`…). La configuration passe par de simples `Map<String,String>` et le contexte est porté par des records immutables — l'`Exchange`, enveloppe du message, se copie via `withXxx()`.

Vous écrivez la logique métier ; le moteur enrobe le reste.

#### Multi-tenant, multi-cluster

L'isolation tenant et le partitionnement en clusters sont intégrés au moteur, pas ajoutés après coup. Le modèle **auto-wrap** injecte `protocol_in` / `protocol_out` (quand le dataflow est `encapsulated`) et le `produce` final autour des stages déclarés : l'auteur de la route n'écrit que le métier.

---

### Architecture

#### Un moteur qui assemble Asset → Tenant → Cluster

Le moteur (`Engine`) organise le traitement en trois niveaux — **Asset**, **Tenant**, **Cluster** — et chaque contexte (`ContextDef`) produit un `ClusterRuntime`. Les threads consommateurs relaient les messages vers `workflow.execute()`.

**Chaque route est un workflow. Compilable. Observable. Isolé par tenant.**

#### Auto-wrap des pipelines

Autour des stages métier déclarés, le moteur injecte automatiquement `protocol_in` / `protocol_out` (dataflow `encapsulated`) puis le `produce` final. L'auteur de la route ne décrit que la transformation utile.

#### Concurrence et ordre

Chaque souscription définit sa `concurrency` : les messages s'exécutent sur un pool de workers, sauf si le dataflow impose `garanteeOrder` — auquel cas le traitement redevient séquentiel.

#### Dead-letter intégré

`RouteDef.exceptions` redirige un `Exchange` en échec vers une souscription d'erreur, pour ne perdre aucun message défaillant.

#### Contexte JSON

Les contextes de traitement sont lus et écrits en JSON (`JsonContextReader` / `JsonContextWriter`), chargeables depuis une source `file`, `resource` ou `json`.

---

### Architecture modulaire

#### Un cœur, des connecteurs interchangeables

Le graphe de dépendances est simple : `api` ← `expressions`, `core`, `connector-*`. Les connecteurs sont des SPI (`IConnector` étend `ILifecycle`) que l'on branche selon le transport.

#### garganttua-events-api

**Couche : Contrats**

Interfaces, records, enums et exceptions. L'`Exchange` (record immutable, copies via `withXxx()`) est l'enveloppe du message ; `ContextDef`, `RouteDef`, `RouteStageDef`, `TopicDef` portent la configuration.

#### garganttua-events-expressions

**Couche : Expressions**

Les fonctions `@Expression` qui composent les pipelines : `protocol_in`/`protocol_out`, `filter_in`/`filter_out`, et les briques de transformation des routes.

#### garganttua-events-core

**Couche : Moteur**

Le moteur (`Engine`), les builders DSL, la compilation route-as-workflow et le contexte JSON. `EngineBuilder` déclare ses dépendances sur l'injection et le contexte d'expressions du core.

#### garganttua-events-connector-kafka

**Couche : Connecteur**

Connecteur Kafka avec création automatique des topics et gestion des consumer groups.

#### garganttua-events-connector-bus

**Couche : Connecteur**

Connecteur in-memory basé sur BigQueue, idéal pour les tests et le développement local.

#### garganttua-events-connector-mail

**Couche : Connecteur**

Connecteur email (Angus Mail), producer-only.

#### Autres connecteurs & starters

**Couche : Connecteur / Intégration**

`garganttua-events-connector-websocket`, `garganttua-events-connector-observability`, `garganttua-events-connector-api`, ainsi que `garganttua-events-starters` pour l'amorçage applicatif.

---

### Chiffres clés

| Valeur | Label |
|--------|-------|
| 10 | modules |
| 102 | fichiers source Java |
| 17 | suites de tests |
| 3 | niveaux (Asset · Tenant · Cluster) |
| 0 | moteur d'exécution parallèle réinventé |

---

### Cas d'usage

#### Pour quoi l'utiliser ?

#### Ingestion et routage Kafka

Consommez des topics Kafka, filtrez et transformez les messages via des pipelines d'expressions, puis reproduisez vers d'autres topics. Création automatique des topics et gestion des consumer groups incluses.

Modules concernés : `events-core` · `events-expressions` · `connector-kafka`

#### Pipelines multi-tenant

Isolez le traitement par tenant et par cluster au sein d'un même moteur. Chaque route compile en workflow observable, avec dead-letter sur échec via `RouteDef.exceptions`.

Modules concernés : `events-core` · `events-api` · `connector-bus`

#### Notifications et intégrations

Produisez des emails (connecteur mail, producer-only) ou poussez des événements vers des clients WebSocket en bout de pipeline, sans coder de runtime d'envoi.

Modules concernés : `connector-mail` · `connector-websocket` · `connector-observability`

---

### Open source

#### Open-source sous licence MIT

Garganttua Events est entièrement open-source. Le code, la documentation, et les tests sont publics et ouverts aux contributions.

- **Star le projet sur GitHub** — [github.com/garganttua/garganttua](https://github.com/garganttua/garganttua)
- **Contribuer** — Les pull requests sont bienvenues. Consultez les issues "good first issue" pour commencer. — [issues](https://github.com/garganttua/garganttua/issues)
- **Reporter un bug** — Ouvrez une issue sur le tracker GitHub. — [new issue](https://github.com/garganttua/garganttua/issues/new)
- **Contact** — [jeremy.colombet@garganttua.com](mailto:jeremy.colombet@garganttua.com)

---

### Prêt à router vos événements ?

Framework pluggable, multi-tenant, où chaque route compile en workflow Garganttua Core. Tout est sur GitHub.

- [Voir sur GitHub](https://github.com/garganttua/garganttua)
- [Retour aux projets](/open-source)
