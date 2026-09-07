# garganttua-api — ~40 ms par requête authentifiée, passés en résolution réflexive et en exceptions

**À l'attention de :** garganttua-api / garganttua-core
**Émis par :** autonom (consommateur v3, AOT pur, Javalin partagé, MongoDB local)
**Date :** 2026-09-02
**Version constatée :** `3.0.0-ALPHA16` (mesures identiques sur ALPHA15)
**Gravité :** ÉLEVÉE en effet cumulé — ce n'est pas une lenteur d'un écran, c'est un plancher de
~40 ms sur **chaque** requête authentifiée, quel que soit le travail métier. Un écran qui en
enchaîne six met un quart de seconde à s'afficher avant d'avoir lu une seule donnée utile.
**Nature :** bogue de performance. **MESURÉ** en HTTP, puis **LU** dans les sources pour la cause.

## Symptôme, chiffré

Backend seul (pas de front), MongoDB local, JVM chaude, 25 requêtes par ligne, moyenne :

| requête | temps |
|---|---|
| `GET /api/health` — route écrite à la main, hors DSL, aucune authentification | **2 ms** |
| `GET /<domaine>` **sans** jeton → 401 | 7,8 ms |
| `GET /<domaine>` avec un jeton **invalide** → 401 | 9,0 ms |
| `GET /<domaine>` avec un jeton **valide** → 200, collection VIDE | **48 ms** |
| `GET /<domaine>` avec un jeton valide → 200, collection d'UNE ligne | 47,9 ms |
| `POST /users/authenticate` | 92 ms (bcrypt, attendu, une fois par session) |

Le travail métier n'y est pour rien : le temps ne bouge pas selon que la collection est vide ou
peuplée. **~40 ms se situent entre « le jeton est valide » et « l'opération s'exécute ».**

Trois hypothèses écartées par la mesure, pas par le raisonnement :

- **La relecture du jeton stocké** (`.checkStoredOnVerify(true)`, un `find` Mongo par requête) :
  désactivée, **aucun gain** (48 ms → 41-62 ms, dans le bruit). Ce n'est pas l'accès base.
- **La taille du jeton et le nombre d'autorités** : notre jeton porte 219 autorités et pèse
  **7 674 caractères**. Réduit à 3 autorités et **566 caractères** (autorisation stockée purgée pour
  forcer une nouvelle frappe), la requête met **41 ms**. Un jeton treize fois plus petit ne change
  rien de mesurable.
- **La cryptographie** : la vérification ES256 d'une signature ne coûte pas 40 ms, et le 401 sur
  jeton invalide (9 ms) montre que le décodage et le rejet sont rapides.

## Cause, lue dans les sources

Échantillonnage de piles (25 relevés `jcmd Thread.print` sous 6 charges parallèles), frames de tête
des threads qui traitent une requête. Deux familles dominent, et une seule explique les deux :

```
70 échantillons   java.lang.Throwable.fillInStackTrace (Native Method)
12               com.garganttua.core.reflection.query.DirectAddresses.resolve:34
 5               com.garganttua.core.reflection.query.MemberLookup.addIfNew:85
 3               com.garganttua.core.reflection.methods.ResolvedMethod.getReturnType:162
 2               com.garganttua.core.reflection.methods.ResolvedMethod.matches:77
 2               com.garganttua.core.reflection.query.MemberLookup.getField:26
```

sous une pile constante `ExpressionVariableContext.callIn:64` → `MethodBinder.supply:161` →
`MethodBinder.execute:132` → `MethodInvoker.invoke:118` → `ExecutorChain.execute:121` →
`RuntimeStep.lambda$buildObservedExecutor$0:90`.

### 1. Une exception levée et avalée à chaque résolution

`core/reflection/src/main/java/com/garganttua/core/reflection/query/DirectAddresses.java:31-36`

```java
IField field = null;
try {
    field = objectClass.getDeclaredField(elementName);
} catch (NoSuchFieldException | SecurityException ignored) {
}
```

Chaque fois que l'élément résolu est une **méthode** et non un champ — c'est-à-dire la quasi-totalité
des résolutions du pipeline —, la JVM construit une `NoSuchFieldException` **avec sa trace de pile**,
qui est ensuite jetée. `fillInStackTrace` est le coût dominant relevé par l'échantillonnage. Ce n'est
pas une erreur de conception isolée : c'est du **flot de contrôle par exception**, sur le chemin le
plus chaud du produit.

### 2. Une résolution de membres refaite à chaque requête

`MemberLookup.getMethods` / `addIfNew` parcourent la hiérarchie de classes et **reconstruisent une
signature textuelle** (`buildMethodSignature`, `StringBuilder`) pour dédoublonner, à chaque appel.
Aucun cache n'apparaît dans `MemberLookup` ni dans `DirectAddresses` : `grep -n "Map\|cache\|Cache"`
sur ces deux fichiers ne rend rien.

Autrement dit, le pipeline **re-résout** par réflexion, requête après requête, ce que l'index AOT a
déjà calculé à la compilation.

## L'attendu

Par ordre de rapport gain/risque :

1. **Ne pas lever pour dire « ce n'est pas un champ ».** `IClass` peut exposer un
   `Optional<IField> findDeclaredField(String)` (ou un `hasDeclaredField`) et `DirectAddresses`
   l'utiliser. Changement local, sans effet observable ailleurs, et il retire la frame la plus vue.
2. **Mémoriser la résolution.** `(IClass, elementName) → List<ObjectAddress>` et
   `(IClass, name) → List<IMethod>` sont **immuables pour la vie de la JVM** : une
   `ConcurrentHashMap` suffit. C'est ce que l'index AOT promet déjà, et que le chemin d'exécution
   n'emprunte apparemment pas.
3. **Dire où passe le temps.** `HotPathProbe` existe et couvre déjà quelques étages
   (`entity.runAfterGet`, `entity.doInjection`) mais n'est lisible que depuis le processus. Une
   route de diagnostic — ou une ligne au démarrage quand `garganttua.perf.probe=true` — rendrait ce
   genre d'enquête vérifiable par le consommateur sans échantillonner des piles.

## Ce que la fiche ne demande PAS

- **Pas de suppression de la vérification ni de la relecture du jeton.** La révocation immédiate
  (`checkStoredOnVerify`) a un vrai prix de sécurité si on la retire, et la mesure montre qu'elle ne
  coûte rien : ce n'est pas le levier.
- **Pas d'allègement des autorités.** Mesuré ci-dessus : sans effet. Nous les garderons pour ce
  qu'elles apportent, indépendamment de la performance.
- **Pas de cache de résultats métier.** Il ne s'agit pas de mémoriser des données, seulement la
  forme des classes — qui, elle, ne change jamais.

## Ce que nous faisons en attendant

Rien : il n'y a pas de contournement côté consommateur. Le chemin est entièrement interne au socle,
entre la vérification du jeton et l'exécution de l'opération. C'est précisément pourquoi cette fiche
existe — nous pouvons mesurer, pas corriger.

## Reproduire

```bash
# 1. un backend, une base vide, une JVM chaude
curl -s -o /dev/null -w '%{time_total}\n' http://localhost:8080/api/health
JWT=$(curl -s -D - -o /dev/null -X POST http://localhost:8080/users/authenticate \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: 0' \
  -d '{"login":"...","credentials":"..."}' | grep -i '^x-authorization:' | cut -d' ' -f2 | tr -d '\r')
for i in $(seq 1 25); do curl -s -o /dev/null -w '%{time_total}\n' \
  http://localhost:8080/<domaine> -H "Authorization: Bearer $JWT" -H 'X-Tenant-Id: 0'; done

# 2. les piles, sous charge parallèle
for k in 1 2 3 4 5 6; do ( for i in $(seq 1 250); do curl -s -o /dev/null \
  http://localhost:8080/<domaine> -H "Authorization: Bearer $JWT" -H 'X-Tenant-Id: 0'; done ) & done
for s in $(seq 1 25); do jcmd <pid> Thread.print >> dumps.txt; sleep 0.2; done
```

---

## Réponse de la plateforme — 2026-09-04

**Traitée, les trois pistes.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA17`.

Les deux causes lues dans les sources étaient exactes. Mesuré sur le chemin de résolution isolé
(fournisseur de réflexion JVM, meilleur de 3, JVM chaude, deux exécutions indépendantes) :

| variante | ns par résolution |
|---|---|
| ALPHA16 | **1 395 – 1 670** |
| sans l'exception de contrôle, sans mémo | 1 037 – 1 406 |
| version corrigée (les deux) | **173 – 178** |

Soit **8 à 9 fois moins**. Le retrait de l'exception vaut à lui seul un quart environ ; la
mémoïsation fait le reste. La hiérarchie de la classe sondée est plus courte que la vôtre, donc ce
partage vous sera probablement plus favorable encore sur le second levier.

### 1. Ne plus lever pour dire « ce n'est pas un champ »

Fait comme proposé — `IClass` porte désormais `Optional<IField> findDeclaredField(String)`, et les
deux fournisseurs le résolvent par balayage, sans exception. La méthode a une implémentation par
défaut qui conserve le `try/catch`, pour qu'une implémentation tierce d'`IClass` reste valide.

**Une précision qui compte pour vous : il y avait DEUX sites qui levaient, pas un.** Le vôtre —
`DirectAddresses.java:31-36` — et, en dessous, `AOTLiveClassFallback.declaredField`, où
`AOTClass.getDeclaredField` se replie sur la classe vivante quand le descripteur AOT ne porte pas
le nom. Sur un déploiement **AOT pur comme le vôtre**, un nom qui n'est pas un champ construisait
donc **deux** `NoSuchFieldException` par résolution : celle du repli, puis celle qu'`AOTClass`
relève. Corriger le seul fichier que la fiche cite aurait laissé la première en place — c'est-à-dire
l'essentiel de vos 70 échantillons `fillInStackTrace`. Les deux sont retirés.

### 2. Mémoriser la résolution

Fait, avec une nuance sur la clé. Mémoriser `(IClass, elementName) → List<ObjectAddress>`, comme la
fiche le propose, ne tient pas : l'adresse rendue dépend aussi de l'**adresse de base**, qui change
à chaque descente dans un champ imbriqué — la mémo rendrait `owner.details.name` là où on attend
`name`. Ce qui est mémorisé est donc la **forme** — combien de surcharges portent ce nom, et s'il
existe un champ homonyme — la seule part qui dépende réellement de la classe ; les adresses se
rebâtissent à chaque appel, ce qui ne coûte rien. Un test verrouille précisément ce point.

`MemberLookup` mémorise en plus ses trois recherches (`getField`, `getMethod`, `getMethods`), et son
parcours de hiérarchie a été aplati : il construisait une liste et un ensemble de signatures **à
chaque niveau** avant de les fusionner vers le haut. Une `ConcurrentHashMap` suffit, comme vous
l'écriviez.

### 3. Dire où passe le temps

Fait dans la forme la moins coûteuse que la fiche propose — pas de route de diagnostic, qui serait
une surface exposée pour un besoin d'exploitation.

- **Le chemin de résolution est instrumenté.** Il ne l'était pas : c'est exactement pourquoi il vous
  a fallu échantillonner des piles. Deux compteurs, `reflection.resolveAddress` et
  `reflection.resolveAddresses`, apparaissent maintenant dans le rapport.
- **Le drapeau s'annonce.** Avec `-Dgarganttua.perf.probe=true`, une ligne au démarrage dit que la
  sonde est active et ce qui suivra.
- **Le rapport tombe tout seul.** À l'arrêt de la JVM, l'attribution complète part dans le journal :
  libellé, temps total, nombre d'appels, moyenne par appel. Plus une ligne de code à écrire.

Le coût quand la sonde est éteinte reste nul — mesuré : 178 ns avant instrumentation, 173 après,
soit du bruit. Le drapeau est un `static final` résolu une fois, la branche est repliée à la
compilation.

### Ce que cela ne dit pas

**Nous n'avons pas reproduit vos ~40 ms.** La mesure ci-dessus porte sur le chemin de résolution
isolé, pas sur une requête HTTP complète, et pas sur votre application. Votre échantillonnage
désigne ce chemin comme dominant, donc le gain devrait s'y voir largement — mais c'est à vous de le
constater. **Si le plancher subsiste après ALPHA17, la fiche mérite d'être rouverte avec les
nouveaux relevés** : la sonde vous donnera cette fois l'attribution directement, sans `jcmd`.

### Ce qui n'a pas été touché

Conformément à ce que la fiche ne demande pas : ni la vérification du jeton, ni sa relecture
(`checkStoredOnVerify`), ni les autorités, ni le moindre cache de données métier. Seule la **forme
des classes** est mémorisée, et elle ne change pas.

**Couvert par :** `ResolutionMemoBehaviourTest` (10 tests, dont l'indépendance à l'adresse de base,
l'immuabilité de la liste partagée et la résolution concurrente) et
`FindDeclaredFieldBehaviourTest` (5 tests). Réacteur complet vert : 4 006 tests, 0 échec.

---

## Suite — 2026-09-07 : ce que les bancs de mesure ont tranché

`garganttua-api` et `garganttua-core` portent désormais des bancs de performance
(`-Dgarganttua.perf=true`). Ils ont servi à reprendre vos hypothèses une par une, dépôt en mémoire,
comparaisons entrelacées jugées sur le plancher.

**Vos deux hypothèses écartées le sont pour de bon, et on sait maintenant pourquoi vous ne pouviez
pas les voir** : `checkStoredOnVerify` coûte +11 µs, et 219 autorités contre 3 coûtent −0 µs — trois
ordres de grandeur sous votre plancher. Votre méthode était bonne.

**Une hypothèse de NOTRE côté est morte aussi** : nous pensions que vos entités, faute de
`@Reflected` complet, retombaient sur la réflexion vivante à chaque résolution. Mesuré, le repli est
mémoïsé : descripteur complet ou superficiel, facteur 1,00. Nous vous l'aurions suggéré comme
correctif ; cela vous aurait fait perdre du temps.

**Ce qui reste est le mécanisme que votre fiche désigne**, chiffré sur le chemin AOT : dire « ce
n'est pas un champ » en levant coûte **19,6×** le dire en rendant vide (4,507 µs contre 0,230 µs), et
l'absence est la réponse ordinaire. Il manque un seul facteur pour boucler l'arithmétique — le
nombre de résolutions par requête, que seul votre déploiement peut compter.

**C'est l'objet de [mesure-demandee-compteur-de-resolutions](mesure-demandee-compteur-de-resolutions.md).**
