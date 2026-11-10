# L'événement d'API ne porte ni l'adresse IP, ni la session, ni l'agent utilisateur de la requête

**À l'attention de :** garganttua-api (`core` : `DomainEventBuilder` ; liaisons HTTP)
**Émis par :** palliad (v3, AOT pur, natif GraalVM, liaison Javalin)
**Date :** 2026-11-02
**Version constatée :** garganttua-api `3.0.0-ALPHA30`
**Gravité :** manque fonctionnel. Le journal des accès aux données de santé de palliad (certification HDS) doit dire
**depuis où** un dossier a été consulté : sans adresse ni session, on ne peut ni enquêter sur un accès suspect, ni
distinguer deux sessions du même compte. Palliad ne peut pas le reconstituer : son code ne voit pas la requête HTTP
d'une opération CRUD.

## Symptôme

Les événements reçus par le connecteur `api` disent qui (`userId`), quoi, quand, mais jamais depuis quelle adresse ni
dans quelle session.

## Cause

**Lu** :

- `api/commons/src/main/java/com/garganttua/api/commons/event/IEvent.java`, l. 15-67 : aucun accesseur d'origine.
- `api/core/src/main/java/com/garganttua/api/core/domain/DomainEventBuilder.java`, l. 42-52 : l'événement est rempli
  depuis les arguments de la requête d'opération (`ENTITY_UUID`, `TENANT_ID`, `OWNER_ID`, `CALLER_ID`, l'appelant) ;
  aucun ne porte l'origine.
- Aucune lecture de `ctx.ip()`, d'un `X-Forwarded-For` ou du `User-Agent` dans les sources de `api` (recherche sur
  `remoteAddr`, `.ip()`, `X-Forwarded-For`, `userAgent` : zéro résultat).

## Attendu

1. Que la liaison HTTP pose sur la requête d'opération un argument d'**origine** : adresse du client, agent
   utilisateur, et un identifiant de session ou de jeton (l'identifiant de l'autorisation ou de sa signature, **jamais
   le jeton lui-même**).
2. Que `DomainEventBuilder` le recopie dans l'`IEvent` (accesseur `getOrigin()` ou trois accesseurs), et que
   `ApiEventCodec` (garganttua-events) l'encode.
3. Pour l'adresse derrière un mandataire inverse (palliad est derrière Caddy, puis derrière la répartition de charge de
   l'hébergeur) : une liste de **mandataires de confiance** configurable, `X-Forwarded-For` n'étant lu que s'il vient
   de l'un d'eux. Sans ce réglage, l'adresse brute de la connexion.

## Ce que la fiche ne demande pas

- Journaliser quoi que ce soit côté cadre : palliad écrit son journal lui-même.
- Exposer le jeton porteur ou un en-tête complet dans l'événement.

## Réponse de la plateforme — 2026-11-10

**Traitée, dans les trois points.** Sur la branche `perf/token-verification`, à paraître dans `3.0.0-ALPHA34`.

1. **L'origine sur la requête.** `IProtocol.getOrigin(...)` (adresse du client et agent utilisateur) est appelée par
   `EXTRACT.gs` et posée en `IOperationRequest.ORIGIN`. L'agent est coupé à 512 caractères.
   - La **session** ne vient pas du transport : `VERIFY_AUTHORIZATION.gs` la pose, et **seulement après** une
     vérification réussie (`SESSION_ID`).
   - C'est l'uuid de l'autorisation vérifiée, jamais le jeton. Un jeton refusé ne nomme aucune session.
2. **Dans l'événement.** `IEvent.getOrigin()` rend un `RequestOrigin(address, userAgent, sessionId)`, et le codec du
   connecteur `api` l'encode sous `origin`.
3. **Derrière un mandataire.** `server.trustedProxies` dans le starter Javalin (ou `JavalinProtocol.trustedProxies`)
   liste des adresses ou des plages, IPv4 et IPv6.
   - `X-Forwarded-For` n'est lu que si la connexion vient de l'un d'eux. Il est alors parcouru **depuis la droite**,
     jusqu'au premier saut qui n'est pas un mandataire de confiance : ce que le client a écrit à gauche ne prouve
     rien.
   - Sans réglage, c'est l'adresse de la connexion, quels que soient les en-têtes.
   - Les adresses sont lues comme des littéraux, sans résolution DNS : un en-tête forgé ne déclenche aucune requête.

Pour vous : `server.trustedProxies=<adresse de Caddy>,<plage de la répartition de l'hébergeur>`.

Testé :
- `JavalinProtocolTest` : sans mandataire, chaîne de mandataires, connexion non fiable, IPv6, nom d'hôte, agent
  trop long, entrée refusée ;
- `OperationTraceTest` : l'origine et la session dans l'événement ;
- `SelfVerifyingTokenCostTest` : la session est l'uuid du jeton vérifié, et un jeton refusé n'en nomme aucune.
