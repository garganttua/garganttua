# Le moteur de scripts passe son temps dans des tables de hachage

**À l'attention de :** garganttua-core (`script`, `runtime`, `reflection`, `aot`) et garganttua-api (`dao-mongodb`)
**Émis par :** palliad (v3, AOT pur, natif G1 + PGO et JVM HotSpot)
**Date :** 2026-11-05
**Version constatée :** garganttua `3.0.0-ALPHA33`
**Gravité :** structurelle. Le cadre est le premier consommateur de processeur d'une requête, loin devant la base de
données. C'est le levier le plus large sur la capacité de tous les consommateurs, pas seulement de palliad.

## Symptôme (mesuré)

JVM HotSpot, 4 vCPU / 2 Go, MongoDB, parcours réels de palliad, Java Flight Recorder (23 735 échantillons) :

| Part du processeur, appels imbriqués compris | |
|---|---|
| sous `com.garganttua.core.runtime.RuntimeStep` | **84 %** |
| sous le pilote MongoDB | 11 % |
| sous Jetty / Javalin | 10 % |
| sous Jackson | 4 % |

Le temps **propre** (le cadre en haut de pile) se concentre dans des structures du JDK : 47 % dans les collections et
les chaînes. Premiers appelants dans le cadre, par méthode chaude :

| Méthode chaude | Part des échantillons | Premiers appelants garganttua |
|---|---|---|
| `HashMap.getNode` | 5,5 % | `MongoDocumentReader.readRawValue` (31 %), `IKeyBsonBridge.isKeyDocument` |
| `ConcurrentHashMap.computeIfAbsent` | 4,6 % | `HotPathProbe.end` (voir la dernière section), `MemberLookup.getField`, `MemberLookup.getMethods`, `DirectAddresses$ShapeKey.hashCode` |
| `MethodHandleObjectFieldAccessorImpl.set` | 3,7 % | `AOTField.set` (100 %) |
| `String.equals` | 2,1 % | `MongoDocumentReader.readRawValue` (51 %) |
| `ConcurrentHashMap.get` | 2,0 % | `AOTRegistry.get` (74 %), `PropertyProvider.getProperty` |
| `HashMap.putVal` | 3,8 % | `MongoDao.find`, `MethodResolver.methodByMethod`, `ObjectAddress.detectLoop` |

## Cause (lu dans les piles, à confirmer)

Trois familles de coût par appel, là où un coût par **type** suffirait :
- **résolution des membres** (`MemberLookup`, `DirectAddresses`, `AOTRegistry`) : une recherche par nom et un
  `computeIfAbsent` à chaque appel, avec le hachage d'une clé composite, au lieu d'une poignée résolue une fois et
  gardée par le pas de script ou par le type ;
- **affectation de champ** par `MethodHandle` générique (`AOTField.set`) plutôt que par l'accesseur direct que le
  processeur AOT sait engendrer ;
- **lecture d'un document MongoDB** (`readRawValue`) : recherche des clés par chaîne, champ par champ.

## Attendu

Mettre en cache, par pas de script et par type, ce qui ne dépend que du type : membres résolus, poignées d'accès,
convertisseurs de champs. Pour la lecture BSON, un lecteur par type qui connaît l'ordre et le nom de ses champs.

**Ces caches restent locaux, et c'est voulu.** Ils ne dépendent que du code compilé et des types. Ils sont donc
identiques sur tous les nœuds d'une même version, et ne s'invalident jamais pendant la vie du processus. Un cache
distribué (Redis) n'apporterait que le coût d'un aller-retour réseau, là où l'on cherche à gagner des microsecondes.
Les caches de **données** (autorisations, autorités, configuration), eux, doivent être distribués : voir
`un-cache-du-cadre-local-et-distribue.md`.

## Ce que la fiche ne demande pas

- Ne pas renoncer à l'AOT pur ni réintroduire la réflexion d'exécution.
- Ne pas changer la sémantique des scripts.

## Note sur la mesure : `HotPathProbe` se dispute ses compteurs

Avec `-Dgarganttua.perf.probe=true`, le débit tombe de 2 à 10 % à 1 et 2 vCPU, mais de **25 à 30 % à 4 vCPU**. Les
libellés sont construits par concaténation à chaque appel (`"step.total:" + stepName`), même sonde éteinte, et chaque
`end` passe par `COUNTERS.computeIfAbsent`. Des compteurs résolus une fois par pas de script éviteraient la
contention et feraient de la sonde un outil utilisable en charge. Le collecteur de palliad
(`PerfObservability.record`) a le même défaut, et il sera corrigé côté palliad.
