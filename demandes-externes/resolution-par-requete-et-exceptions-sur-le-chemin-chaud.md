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
