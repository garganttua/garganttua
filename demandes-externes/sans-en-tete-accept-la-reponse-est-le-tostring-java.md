# Sans en-tête `Accept`, la réponse est le `toString()` Java de l'objet

**À l'attention de :** garganttua-api (`core`, `binding-javalin`)
**Émis par :** palliad (v3, AOT pur, JVM et binaire natif GraalVM 25)
**Date :** 2026-11-11
**Version constatée :** garganttua `3.0.0-ALPHA34`, construite en local sur `21dc694`
**Gravité :** MOYENNE. Un client qui n'envoie pas d'en-tête `Accept` (un script Python `urllib`, un outil de supervision,
certaines bibliothèques HTTP) reçoit un **200** dont le corps n'est pas la donnée : `care.palliad.activity.entity.Activity@444b578c`.
Rien n'échoue côté serveur, le client croit avoir réussi, et la réponse divulgue au passage le nom des classes Java. Les
navigateurs et `curl` envoient tous un `Accept` : le défaut ne se voit donc qu'avec un client minimal.

## Symptôme

**Mesuré** (back palliad en JVM, et la même chose en natif sur le banc de charge) :

| Requête | `Accept` absent | `Accept: */*` |
|---|---|---|
| `GET /activities/{uuid}` (lecture CRUD) | `200 text/plain` — `care.palliad.activity.entity.Activity@444b578c` | `200 application/json` — l'entité |
| `POST /activities/search` (cas d'usage) | `200 text/plain` — `care.palliad.activity.dto.ActivitySearchResult@5e75575b` | `200 application/json` — le résultat |

Rencontré en écrivant une épreuve en Python (`urllib.request`, qui n'envoie pas d'`Accept`) : le corps n'était pas du JSON.

## Cause

**Lu**, partiellement :

- `SerializationExpressions.negotiateSerializer` (`api/core/src/main/java/com/garganttua/api/core/expression/SerializationExpressions.java`,
  l. 117-125) prévoit le cas : un `Accept` absent ou vide rend le sérialiseur par défaut.
- Pourtant la sortie arrive non sérialisée à `JavalinProtocol.buildResponse`
  (`api/bindings/binding-javalin/src/main/java/com/garganttua/api/binding/javalin/JavalinProtocol.java`, l. 173-186), dont
  le repli pour tout ce qui n'est pas un `byte[]` est `ctx.result(String.valueOf(output))` — d'où le `toString()`, étiqueté
  `text/plain` par Javalin.

Nous n'avons pas établi à quel endroit du pipeline la négociation est sautée quand l'en-tête manque.

## Attendu

- Sans `Accept`, la réponse est sérialisée par le sérialiseur par défaut (JSON), comme le prévoit déjà
  `negotiateSerializer` — pour les lectures CRUD comme pour les cas d'usage.
- Plus largement : `buildResponse` ne rend jamais le `toString()` d'un objet qui n'est ni un `byte[]` ni une chaîne. Une sortie
  non sérialisée est une erreur du pipeline, mieux signalée par un 500 qu'habillée en 200.

## Ce que la fiche ne demande pas

- Elle ne demande pas de changer la négociation quand un `Accept` est présent (un `Accept` qu'aucun sérialiseur ne
  satisfait garde son comportement actuel).
- Elle ne choisit pas l'endroit du correctif.

## Reproduire

```
curl -s -H "Accept:" -H "Authorization: Bearer <jeton>" http://<hôte>/<domaine>/<uuid>
```

`-H "Accept:"` retire l'en-tête que `curl` envoie d'ordinaire : la réponse est `text/plain` et contient `<classe>@<hash>`.

## Réponse — 2026-11-11, à paraître dans `3.0.0-ALPHA34`

**L'endroit où la négociation est sautée** : l'étape `serialize` du pipeline
(`api/core/src/main/java/com/garganttua/api/core/domain/DomainWorkflowAssembler.java`, `buildSerializeStage`) avait
pour garde `notNull(:arg(@0, "accept"))`. Sans en-tête, elle ne tournait pas. `negotiateSerializer` n'était donc jamais
appelé avec un `Accept` vide, et l'objet brut allait jusqu'au transport. Le cas prévu dans `negotiateSerializer` était
juste, mais inaccessible.

### Ce qui est fait

1. **`serialize` tourne pour toute requête venue d'un transport** : sa garde est maintenant
   `or(notNull(rawRequest), notNull(accept))`.
   - Sans `Accept`, c'est le sérialiseur par défaut (JSON) qui répond, pour le CRUD comme pour les cas d'usage, qui
     partagent ce pipeline.
   - Un appel programmatique sans transport qui pose `accept` reçoit toujours des octets ; sans `accept`, il reçoit
     toujours l'objet.
   - Un `Accept` présent garde exactement son comportement, `406` compris.
2. **`JavalinProtocol.buildResponse` n'habille plus une sortie non sérialisée.**
   - Elle accepte un `byte[]`, une `String` ou rien.
   - Tout autre objet est refusé : l'étape de réponse en fait un **500** `{"error":"The response was not serialized"}`,
     et le journal du serveur nomme la classe. Le corps, lui, ne nomme aucune classe.
3. **L'épreuve HTTP sans `Accept`**, sur un vrai serveur Javalin (`GarganttuaApplicationE2ETest.WithoutAccept`). La
   requête est écrite à la main sur une socket, pour qu'aucune bibliothèque n'ajoute l'en-tête.
   - `GET /widgets` (lecture CRUD) et `POST /widgets/echo` (cas d'usage) répondent `200 application/json`, avec la
     donnée.
   - Lancée sur l'ancienne garde, l'épreuve échoue sur les deux requêtes : le garde-fou du point 2 répond alors 500, ce
     qui montre au passage qu'il tient.

Pour palliad, rien à changer. Votre épreuve Python `urllib` doit recevoir du JSON.
