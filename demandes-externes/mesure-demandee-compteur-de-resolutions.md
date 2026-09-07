# Demande de mesure — le compteur de résolutions d'une requête réelle

**À l'attention de :** autonom
**Émise par :** garganttua (la plateforme)
**Date :** 2026-09-07
**Se rapporte à :** [resolution-par-requete-et-exceptions-sur-le-chemin-chaud](resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md)
**Ce qui est demandé :** UN nombre, celui que nous ne pouvons pas produire d'ici.

> **Pourquoi une fiche de la plateforme dans un dossier de consommateurs.** Ce dossier est prévu
> pour l'autre sens. Mais la demande qui suit prolonge une fiche qui vit ici, et une demande posée
> ailleurs ne serait pas lue — c'est exactement l'argument que le README de ce dossier fait pour
> les fiches des consommateurs. Elle est donc ici, avec la direction annoncée dans l'en-tête.

## Ce que nous avons cherché depuis votre fiche

Nous avons monté des bancs de performance sur `garganttua-api` et `garganttua-core` et repris vos
hypothèses une par une. Toutes les mesures ci-dessous sont faites **dépôt en mémoire**, donc elles
portent sur le cadre seul, sans base ni réseau. Comparaisons entrelacées, jugées sur le plancher
(le minimum, seule statistique que le bruit ne peut pas gonfler).

| Piste | Verdict | Mesure |
|---|---|---|
| Relecture du jeton stocké (`checkStoredOnVerify`) | **vous aviez raison** | +11 µs |
| Nombre d'autorités (3 contre 219) | **vous aviez raison** | −0 µs |
| Cryptographie / vérification du jeton | écartée | `verifyAuthorization` : 25–45 µs |
| Le pipeline CRUD lui-même | écartée | ~1 ms de plancher, lecture d'une collection vide |
| Descripteur AOT superficiel (**notre** hypothèse) | **fausse** | facteur 1,00 |

Les deux premières lignes confirment vos relevés, et disent en plus **pourquoi** vous ne pouviez pas
les voir : trois ordres de grandeur sous le plancher que vous observez. Votre méthode était bonne.

La dernière ligne était notre piste, et elle est morte : nous pensions que vos entités, faute de
`@Reflected` complet, retombaient sur la réflexion vivante à chaque résolution. Mesuré,
`AOTLiveClassFallback` mémoïse sa classe vivante — descripteur complet ou superficiel, le coût est
identique (0,192 µs contre 0,192 µs). Nous vous le disons parce que nous vous l'aurions
probablement suggéré comme correctif, et cela vous aurait fait perdre du temps.

## Ce qui reste, et ce qui manque

La seule piste vivante est celle de votre fiche : **le flot de contrôle par exception, multiplié par
le nombre de résolutions par requête.** Mesuré sur le chemin AOT, celui que vous exécutez :

```
findDeclaredField("absent")  rend vide   0,230 µs
getDeclaredField("absent")   lève        4,507 µs      → 19,6×
```

Et l'absence est la réponse **ordinaire** : la quasi-totalité des éléments que le pipeline traverse
sont des méthodes, pas des champs. Avant `3.0.0-ALPHA17`, chaque manque construisait **deux**
exceptions sur le chemin AOT (`AOTLiveClassFallback` puis `AOTClass`), et rien n'était mémoïsé :
c'était payé à **chaque** requête.

Il manque un seul facteur pour boucler : **combien de résolutions par requête ?**

Sur notre banc, une lecture unitaire d'une entité à 11 champs en compte **321**. À ce compte-là,
l'ancien code coûtait ~480 µs par requête et le nouveau ~56 µs — un gain réel, mais **cent fois trop
petit pour expliquer vos 40 ms**. Pour y arriver il faudrait de l'ordre de **30 000 résolutions par
requête**.

Est-ce plausible chez vous ? Peut-être — DTO plus riches, compositions, 36 domaines. Mais nous ne
pouvons pas le postuler : **ça se compte, et seul votre déploiement peut le compter.**

## Ce que nous demandons

Sur `3.0.0-ALPHA17`, une requête authentifiée représentative, avec la sonde active :

```bash
java -Dgarganttua.perf.probe=true -jar votre-application.jar
# … exercez UNE requête, ou un nombre connu de requêtes …
# le rapport d'attribution part dans le journal à l'arrêt de la JVM
```

La ligne qui nous intéresse :

```
label                          total(ms)      calls      avg(us)
reflection.resolveAddresses      68,353      32096          2,1
```

**Le nombre d'appels, divisé par le nombre de requêtes exercées.** C'est tout.

Deux précisions sur la lecture de ce rapport, apprises en le produisant :

- **le compteur d'appels est fiable, les millisecondes ne le sont pas** à ce volume. La sonde appelle
  `System.nanoTime()` à chaque étage instrumenté ; sur nos 32 000 appels par centaine de requêtes,
  elle multiplie par cinq le temps de la requête. Les proportions se lisent, les durées non ;
- exercez un **nombre connu** de requêtes après une phase de chauffe, sinon le compteur agrège aussi
  le démarrage, où la résolution est légitimement dense.

## Ce que le nombre nous dira

- **~30 000 par requête** → le mécanisme est confirmé, et votre plancher a dû chuter en ALPHA17.
  Nous saurons quoi optimiser ensuite, et où.
- **~300 comme chez nous** → les 40 ms sont ailleurs, et nous avons éliminé d'ici tout ce qui
  pouvait l'être. Il faudra regarder MongoDB, la sérialisation, ou Javalin — et ce sera à faire
  chez vous, avec la sonde comme point de départ plutôt qu'avec `jcmd`.

Dans les deux cas c'est une réponse, et aucune des deux ne nous est accessible sans vous.

## Ce que la fiche ne demande PAS

- **Pas de re-mesure des 40 ms.** Votre relevé initial est bon, nous ne le remettons pas en cause.
- **Pas d'échantillonnage de piles.** C'est précisément ce que la sonde remplace ; si elle vous
  oblige encore à sortir `jcmd`, dites-le, c'est qu'elle est mal placée.
- **Pas de changement chez vous.** Un drapeau au démarrage, une requête, une ligne de journal.
