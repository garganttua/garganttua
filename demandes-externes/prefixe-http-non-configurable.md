# garganttua-api — les routes générées se montent à la racine, sans préfixe possible

**À l'attention de :** garganttua-api
**Émis par :** autonom (consommateur v3, AOT pur, serveur Javalin PARTAGÉ avec l'application)
**Date :** 2026-09-01
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** faible en effet, MOYENNE en usure — aucune panne que le cadre provoque, mais deux
espaces de noms HTTP à tenir d'accord chez le consommateur, et une panne déjà payée à cause d'eux.
**Nature :** évolution. Établi par **lecture des sources**, pas par mesure.

## Symptôme

Une application qui partage son serveur Javalin avec le cadre se retrouve avec **deux racines** :
le cadre monte ses domaines à la racine (`/invoices`, `/users/authenticate`), et l'application monte
ailleurs les routes que le DSL ne sait pas générer — chez nous sous `/api` : le multipart
(`POST /api/documents/upload`) et tout ce qui rend un flux binaire (huit routes de PDF et de
fichiers). Il n'existe aucun réglage pour ranger les deux au même endroit.

Le coût ne se voit pas d'un coup : il se paie à chaque ajout de domaine, et une fois pour de bon le
jour où quelqu'un se trompe de base.

## Cause

`api/bindings/binding-javalin/src/main/java/com/garganttua/api/binding/javalin/JavalinInterface.java:163`

```java
@Override
public void handle(IDomain<?> domain) {
    String base = "/" + domain.getDomainName();   // ← racine, en dur
    String one  = base + "/{uuid}";
```

Aucun des trois constructeurs (lignes 116, 121, 132 — `()`, `(int port)`, `(Javalin app)`) ne porte
de chemin de montage.

Les **cas d'usage** échappent partiellement à la contrainte : `IUseCaseBuilder.completePath(...)`
(`api/commons/src/main/java/com/garganttua/api/commons/context/dsl/IUseCaseBuilder.java:16`) est
honoré par `toJavalinPath` (`JavalinInterface.java:233`). Le préfixe est donc **atteignable cas
d'usage par cas d'usage, jamais pour le CRUD** — et l'écrire 109 fois (notre compte) garantit
l'oubli, sur un chemin qui répond alors 404 sans rien nommer.

## Ce que ça nous coûte, concrètement

1. **Le front porte deux bases** — la racine pour les domaines, `/api` pour le reste — et chaque
   adaptateur doit choisir. Panne réellement survenue chez nous : un adaptateur appelait
   `/api/social-declarations/schedule` au lieu de la racine. L'écran absorbait l'échec, une liste
   vide se lisant « rien à déclarer » : l'échéancier n'a **jamais** rien affiché, et personne ne
   pouvait le savoir. Un test dédié verrouille aujourd'hui ces appels — c'est l'aveu que la règle ne
   se déduit pas du code.
2. **Le proxy de développement compte 36 règles ancrées**, une par domaine, de la forme
   `^/<domaine>([/?].*)?$`. Un préfixe nu ne suffit pas : `GET /missions.component-QX343J4L.js` — le
   chunk paresseux d'un écran, dont le nom dérive du nom de fichier — commence par `/missions`,
   partait au serveur, revenait en 404, et le routeur abandonnait la navigation **sans rien
   afficher**. Sous un préfixe commun, ces 36 règles deviennent **une**, et cette classe de
   collision entre un chemin d'API et un fichier statique cesse d'exister.
3. **Le catch-all du SPA** doit cohabiter avec des routes d'API à la racine. L'ordre
   d'enregistrement et la forme des chemins y suffisent aujourd'hui, mais c'est un équilibre à
   retenir, pas une propriété du système.
4. **Ajouter un domaine, c'est écrire son chemin dans trois fichiers** : le DSL, le proxy, et la
   liste des chemins que l'intercepteur d'authentification reconnaît. Un oubli répond 401, et rien
   dans le code appelant ne désigne la cause.

Rien de tout cela n'est un défaut du cadre. Tout vient de ce qu'**une application réelle a des
routes que le DSL ne génère pas**, et qu'il n'existe aucun moyen de séparer les deux mondes.

## L'attendu

Un préfixe porté par le **connecteur**, pas par les domaines : le domaine ne doit rien savoir de son
montage HTTP.

```java
new JavalinInterface(sharedApp, "/api");   // /api/invoices, /api/invoices/{uuid}, /api/invoices/issue
new JavalinInterface(sharedApp);           // inchangé : la racine
```

| Cas | Attendu |
|---|---|
| préfixe absent, `null` ou `""` | comportement **strictement** actuel |
| `"api"` sans barre initiale | normalisé en `/api`, jamais refusé |
| `"/api/"` barre finale | normalisée, jamais `/api//invoices` |
| cas d'usage à `pathSuffix` | préfixé comme le reste |
| cas d'usage à `completePath` | **à trancher** — voir plus bas |

Le point à trancher est `completePath`. Deux lectures se défendent : « chemin complet » au sens
littéral (non préfixé, l'appelant écrit `/api/…` lui-même), ou le préfixe du connecteur s'applique à
tout ce qu'il monte. **Notre préférence va au préfixe**, avec `completePath` documenté comme complet
*à l'intérieur* du montage : sans cela, un même connecteur exposerait de nouveau deux espaces de
noms, c'est-à-dire exactement ce que cette fiche cherche à supprimer.

Un détail d'implémentation qui casse en silence s'il est manqué : les cas d'usage littéraux sont
enregistrés **avant** le CRUD pour que `/users/authenticate` ne soit pas avalé par `readOne` comme
`uuid = "authenticate"` (le commentaire de `handle` le dit). Cet ordre doit tenir préfixe compris.

## Ce que la fiche ne demande PAS

- **Pas de préfixe par domaine.** Un seul point de montage pour le connecteur suffit ; une
  granularité fine multiplierait les endroits où le chemin se décide, donc ceux où il se perd.
- **Pas de renommage des domaines.** Chez nous le nom du domaine est **aussi** le nom de la
  collection Mongo : nommer un domaine `api/invoices` échangerait un problème de routage contre une
  migration de données.
- **Pas de réécriture de chemin côté serveur** (règle Jetty `/api/** → /**`). Nous l'avons évaluée
  et écartée : le chemin réel cesserait d'être lisible dans le code qui déclare les routes, et un
  404 dépendrait d'une expression régulière posée ailleurs.
- **Pas de rupture.** L'ajout doit rester purement additif : constructeur supplémentaire, défaut
  inchangé. Aucun consommateur existant ne doit bouger — palliad compris.

## Critères d'acceptation proposés

- [ ] Sans préfixe, les chemins générés sont **identiques** à ceux d'ALPHA15 (non-régression sur un
      domaine à CRUD complet et un cas d'usage).
- [ ] Avec `/api` : `GET /api/<domaine>`, `GET /api/<domaine>/{uuid}` et
      `POST /api/<domaine>/<cas>` répondent ; les chemins racine correspondants rendent **404**.
- [ ] `api`, `/api` et `/api/` produisent le même montage.
- [ ] Un cas d'usage littéral reste enregistré avant `readOne`, préfixe compris.
- [ ] La bannière de démarrage nomme les chemins **effectivement** montés — c'est par elle qu'on
      vérifie ce qui est en ligne.

## Ce que nous faisons en attendant

Rien : nous gardons les deux bases, et deux tests les verrouillent côté front — l'un vérifie que
chaque domaine déclaré est relayé par le proxy et connu de l'intercepteur, l'autre que les cas
d'usage partent bien à la racine. C'est une discipline, pas une garantie : rien ne fait échouer
l'écran qui l'oublie, seulement une suite de tests qu'il faut penser à étendre.
