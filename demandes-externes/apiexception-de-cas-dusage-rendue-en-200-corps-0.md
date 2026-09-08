# garganttua-api — une `ApiException` levée par un CAS D'USAGE est rendue `HTTP 200` avec le corps `0`

**À l'attention de :** garganttua-api
**Émis par :** autonom (consommateur v3, AOT pur)
**Date :** 2026-09-08
**Version constatée :** garganttua-api `3.0.0-ALPHA17`
**Gravité :** HAUTE. Ce n'est pas un défaut d'ergonomie : le client lit `200`, donc un SUCCÈS.
L'écran annonce une réussite qui n'a pas eu lieu, et **aucun message de refus n'atteint jamais
l'utilisateur**. Le geste est refusé, rien n'est écrit, et l'interface dit le contraire.
**Nature :** bogue. **MESURÉ** en HTTP, sur une instance réelle.

## Symptôme

Un `contextualUseCase` dont le type de retour déclaré est une entité ou un DTO rend `HTTP 200`
avec le corps `0` — un entier — dès qu'il lève une `ApiException`.

Mesure du 2026-09-08, entrée VALIDE, entité EXISTANTE, refus métier délibéré :

```
POST /invoices/transmit  {"uuid":"<uuid d'une facture au statut DRAFT>"}
  → HTTP 200   content-type: application/json   taille: 1 octet
  → corps : 0
```

Le cas d'usage exécute bien :

```java
throw new ApiException("Cette facture est un brouillon : émettez-la d'abord, "
        + "c'est l'émission qui lui donne son numéro.");
```

Le message n'apparaît nulle part dans la réponse — ni corps, ni en-tête.

La même mécanique rend correctement un DTO quand elle RÉUSSIT, ce qui écarte un défaut de
sérialisation générale :

```
POST /invoice-transmissions/status  {}
  → HTTP 200   608 octets
  → {"available":true,"testMode":true,"platform":"ACUBE","accountId":null,…}
```

Reproduit sur trois autres gestes de deux autres domaines (`missions/close`,
`invoices/mark-paid`, `customers/lookup-siret`) : `200` + `0` à chaque fois.

## Déclaration du cas d'usage

Rien d'exotique — la forme documentée :

```java
contextualUseCase(invoices, "transmit",
        IClass.getClass(InvoiceTransmissionRequest.class),
        IClass.getClass(InvoiceTransmission.class),   // ← type de retour : une ENTITÉ
        transmissionUseCases, "transmit",
        "invoice-update");
```

## Pourquoi cela coûte cher, et pourquoi c'est invisible

`200` est un succès. Un client HTTP normal — ici `HttpClient` d'Angular — ne déclenche donc
**aucun** chemin d'erreur : l'intercepteur d'erreurs ne voit rien, le composant appelle son
`next`, et l'écran affiche ce qu'il affiche après une réussite.

Conséquence pratique, vécue : un utilisateur a cliqué « Transmettre » sur une facture pendant une
demi-journée. Le serveur refusait à chaque fois — d'abord parce qu'une donnée manquait, ensuite pour
une autre raison — et l'écran ne disait **rien**. Le diagnostic n'a été possible qu'en interrogeant
l'API au `curl` et en lisant le journal du serveur.

Le coût est proportionnel au soin mis dans les refus : notre base en compte plusieurs dizaines,
rédigés pour nommer ce qui manque et le geste de remplacement (« émettez-la d'abord », « désactivez-la
plutôt », « 14 journées imputées »). **Aucun** ne parvient à l'utilisateur quand il passe par un cas
d'usage.

Il y a un second effet, plus sournois : trois causes distinctes partagent désormais **un seul
symptôme** (`200` + `0`), et rien ne les distingue côté client —

1. un nom de méthode qui ne se résout pas (déjà documenté chez nous) ;
2. une exception non-`ApiException` levée dans le cas d'usage (déjà documenté chez nous) ;
3. **un refus métier ordinaire** (ceci).

Les deux premières sont des erreurs de programmation qu'on finit par trouver. La troisième est le
fonctionnement NORMAL de l'application, et elle produit le même silence.

## Ce que nous demandons

Une seule chose, au choix de l'équipe :

1. **Rendre une `ApiException` en `4xx` avec un corps qui porte son message** — la forme
   `{"error": "…"}` déjà servie ailleurs par le socle conviendrait, et nos écrans la lisent déjà.
   `422` nous semble juste pour un refus métier ; `400` irait aussi. **C'est ce que nous préférons.**
2. À défaut, **documenter** que le corps `0` signifie « refusé » et exposer le message autrement
   (un en-tête, un corps `{"error":…}` en 200). Nous nous y adapterions, mais un `200` sur un geste
   refusé restera un piège pour tout nouveau consommateur.

Nous n'avons **pas** de préférence sur le code exact, seulement sur le fait qu'il soit dans la
famille `4xx` : un refus rendu en `2xx` est indistinguable d'un succès pour toute bibliothèque HTTP,
et un refus rendu en `5xx` serait rejoué en boucle par les clients qui réessaient (nous avons déjà
traité ce cas dans notre façade publique).

## Contournement en place chez nous

Un intercepteur convertit en échec toute réponse `POST` dont le corps est un **nombre** — aucun de
nos cas d'usage n'en rend un. L'écran cesse ainsi d'annoncer une réussite qui n'a pas eu lieu.

**Le motif reste perdu** : nous ne pouvons afficher que « le serveur a refusé ce geste sans en
donner la raison ; rien n'a été enregistré ». C'est mieux que le silence, et très loin du message
que le cas d'usage avait rédigé.

## Ce que nous n'affirmons pas

Nous n'avons pas lu le code du socle : nous ne savons pas si l'exception est avalée dans l'invocation
du cas d'usage, dans la liaison Jackson, ou dans la couche HTTP. Le `0` ressemble à une valeur de
retour par défaut, mais c'est une supposition — la seule chose que nous ayons mesurée est ce que
l'API rend.
