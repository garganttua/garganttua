# Le rafraîchissement du jeton ignore la révocation, l'état du compte et les droits du moment

**À l'attention de :** garganttua-api (script `REFRESH_AUTHORIZATION.gs`, `SecurityAuthenticationExpressions`,
`SecurityAuthorizationExpressions`)
**Émis par :** palliad (v3, AOT pur, natif ; jeton porteur ES256 stocké, `checkStoredOnVerify(true)` ; émetteur
propre déclaré par `.authorization(issuer, "issue")`)
**Date :** 2026-11-12
**Version constatée :** garganttua-api `3.0.0-ALPHA34` (sources du dépôt Maven local, reconstruites le 2026-11-11 à 22:55)
**Gravité :** bloquante pour un jeton court. palliad veut des jetons d'accès de 15 minutes, rafraîchis par le front.
Avec le rafraîchissement actuel, une session fermée ou un compte suspendu se rouvrent par un simple rafraîchissement.
Tout est **lu** dans les sources, rien n'a été mesuré ni rejoué.

## Le besoin

Un jeton d'accès de courte durée (15 minutes), que le front échange avant son expiration contre un neuf, tant que la
personne travaille, et jusqu'à une durée maximale de session. C'est le schéma ordinaire d'OAuth 2 : jeton court, et
**contrôle de l'état au rafraîchissement**.

## Constat (lu dans `REFRESH_AUTHORIZATION.gs`)

Le script reçoit l'autorisation présentée par le client (sa forme de transport, le JWT), la décode, vérifie sa
signature, `refreshNotRevoked`, `refreshNotExpired`, retrouve le principal (`findPrincipalByOwnerUuid`), fabrique une
authentification synthétique (`synthAuthFromPrincipal`), puis émet, signe, encode et stocke une autorisation neuve.

1. **La révocation stockée n'est pas lue.** `refreshNotRevoked` lit le champ sur l'entité **décodée du jeton présenté**,
   pas sur l'enregistrement stocké. Un jeton révoqué en base (session fermée par un administrateur, déconnexion) porte
   toujours `revoked = false` dans sa forme signée. Il se rafraîchit donc, et l'autorisation neuve, stockée non révoquée,
   passe ensuite `checkStoredOnVerify`. **La révocation devient inopérante dès qu'un rafraîchissement est possible.**
2. **L'état du compte n'est pas contrôlé.** `findPrincipalByOwnerUuid` rend le principal sans regarder son champ
   `enabled` (déclaré par `.enabled(...)` sur l'authentificateur). Un compte suspendu se rafraîchit.
3. **Les droits sont recopiés de l'ancien jeton.** `synthAuthFromPrincipal` reprend les autorités et le type de
   l'autorisation présentée (`readTokenAuthorities`). Une modification des profils, un retrait de droits ou une licence
   qui se resserre n'entrent pas dans le jeton rafraîchi. Le résolveur d'autorités de l'authentification
   (`PalliadLoginPasswordAuthentication` chez palliad) n'est pas rappelé.
4. **Pas de rotation.** L'autorisation présentée n'est ni révoquée ni marquée consommée : elle reste rafraîchissable
   jusqu'à sa date de fin de rafraîchissement. Un jeton volé peut être rafraîchi en parallèle du jeton légitime, sans
   que rien ne le détecte. La réutilisation d'un jeton déjà échangé, signe classique d'un vol, n'est pas repérable.
5. **L'émetteur déclaré n'est pas utilisé.** Le script appelle `createAuthorizationEntity2` directement, et non
   l'émetteur déclaré par `.authorization(issuer, method)`. Chez palliad, ce dernier copie le nom et le prénom dans le
   jeton signé : un jeton rafraîchi en est privé, et le journal des accès perd le nom du lecteur.
6. **Le jeton d'accès est le jeton de rafraîchissement.** Il n'y a pas de jeton de rafraîchissement distinct : le JWT
   présenté à chaque requête est aussi celui qu'on échange. Un jeton d'accès de 15 minutes intercepté vaut donc une
   session jusqu'à la fin de rafraîchissement. C'est acceptable seulement si les points 1 et 4 sont corrigés.

7. **La durée maximale de session n'existe pas.** `populateRefreshFields` pose la fin de rafraîchissement à
   `maintenant + refreshLifeTime` à **chaque** émission, rafraîchissement compris. Une session rafraîchie régulièrement
   ne finit donc jamais. palliad veut une session de 10 heures au plus après la connexion, quoi qu'il arrive.

8. **La route n'est pas montée** (mesuré le 2026-11-12 sur le back de dev, ALPHA34 reconstruite). Avec
   `.refreshable()` déclaré, `DomainWorkflowSupport` enregistre bien le workflow `refreshAuthorization` et
   `DomainDefinition` déclare l'opération, mais `JavalinInterface` ne monte que `POST /users/authenticate` (ligne 282) :
   `POST /users/refresh` répond 404 (« Endpoint POST /users/refresh not found »), comme `/authorizations/refresh`. Le
   journal de démarrage liste les routes du domaine `users` : aucune ne rafraîchit.

## Ce qui est demandé

- **Lire l'enregistrement stocké au rafraîchissement** (quand le domaine est `storable`) et refuser s'il est révoqué ou
  absent : la même règle que `checkStoredOnVerify`.
- **Contrôler l'état du principal** (`enabled`, et les autres gardes que l'authentification applique) avant d'émettre.
- **Recalculer les autorités** par le chemin de l'authentification (le résolveur déclaré), au lieu de les recopier.
- **Rotation** : révoquer (ou marquer consommée) l'autorisation présentée au moment d'en émettre une neuve. Une
  présentation d'une autorisation déjà consommée révoque toute la lignée (détection de réutilisation).
- **Passer par l'émetteur déclaré** (`.authorization(issuer, method)`), comme à la connexion, et lui donner
  l'autorisation présentée, pour qu'il puisse en reprendre des données (chez palliad : les noms, la méthode de connexion).
- **Ancrer la fin de rafraîchissement sur la connexion d'origine** : l'autorisation neuve reprend la fin de
  rafraîchissement de celle qu'elle remplace (ou `auth_time + refreshLifeTime`), au lieu de la repousser.
- **Monter la route** `POST /<authentificateur>/refresh` dans la liaison Javalin, comme `/authenticate`, avec le
  nouveau jeton dans `X-Authorization`.
- En option : un jeton de rafraîchissement **distinct** du jeton d'accès (opaque, stocké sous forme d'empreinte, jamais
  envoyé à chaque requête).

## Ce que palliad ne fait pas en attendant

palliad ne branche pas `.refreshable()` tant que les points 1 à 4 ne sont pas corrigés : les jetons restent à 24 heures,
avec la révocation à l'état, qui marche. Aucun correctif n'est appliqué au cadre.

## Réponse — 2026-11-12, sur `main`, à paraître dans `3.0.0-ALPHA35`

**Les huit constats sont confirmés.** Tous sont corrigés, sauf le point 6 (jeton de rafraîchissement distinct),
voir plus bas. Nous avions trouvé le point 7 en chemin, de notre côté, et le point 8 en préparant l'ALPHA35.

### Ce qui est fait

Le rafraîchissement juge désormais l'état du moment, pas seulement le jeton présenté
(`REFRESH_AUTHORIZATION.gs`, `SecurityRefreshExpressions`).

1. **La révocation stockée est lue** (`claimRefresh`).
   - Pour une autorisation stockable (`.revokable(...)` la rend telle), l'enregistrement stocké est relu.
   - Absent ou révoqué, le rafraîchissement est refusé : 401 `Authorization not found or revoked`, la même règle et
     les mêmes mots que `checkStoredOnVerify`.
   - Seul l'état qui change depuis la connexion (révoqué, échangé) est lu dans le stockage. Le propriétaire, la
     fenêtre et les autorités, signés dans le jeton, y sont lus : un DTO qui ne stocke pas tout ne fait pas échouer
     un rafraîchissement.
2. **L'état du compte est contrôlé.** `checkAccountStatus` est appelé sur le principal relu, comme à la connexion :
   `enabled`, compte non verrouillé, non expiré, identifiants non expirés.
3. **Les autorités sont recalculées**, par une méthode que la stratégie d'authentification déclare à côté
   d'`authenticate` : la connexion sans le mot de passe.

   ```java
   auth.refresh("refresh")                                // IAuthentication refresh(Object principal, IAuthenticatorDefinition def)
           .withParam(0, new PrincipalSupplierBuilder())   // le principal relu
           .withParam(1, new AuthenticatorDefinitionSupplierBuilder());
   ```

   - Chez vous, `PalliadLoginPasswordAuthentication.refresh` peut rendre l'`Authentication` que construit
     `authenticate`, avec `authoritiesFor(user)` : le résolveur de profils est rappelé.
   - Une `IAuthentication` non authentifiée refuse le rafraîchissement (401) : la stratégie a donc aussi un droit
     de veto, pour une licence échue par exemple.
   - Sans méthode `refresh`, les autorités du jeton remplacé sont recopiées, comme avant, et un avertissement le
     dit une fois au premier rafraîchissement.
4. **Rotation et détection de réutilisation.**
   - Avant d'émettre la nouvelle autorisation, l'enregistrement de l'ancienne est marqué échangé (son
     `.refreshable().revokable(field)`).
   - Le jeton d'accès échangé reste valable jusqu'à son expiration, pour ne pas faire échouer les requêtes en vol,
     mais il ne se rafraîchit plus.
   - Présenté de nouveau, c'est-à-dire échangé par quelqu'un d'autre : 401 `This authorization has already been
     refreshed`, et **toutes les autorisations vivantes du propriétaire sont révoquées**, celles du voleur comme
     celles de la victime, qui se reconnecte. Un `WARN` le journalise.
   - Une connexion ne rend plus jamais une autorisation déjà échangée : la réutilisation à la connexion les exclut.
5. **L'émetteur déclaré est utilisé, et reçoit l'autorisation remplacée.**
   - Le rafraîchissement passe par `issueAuthorization`, comme la connexion : votre
     `.authorization(issuer, "issue")` émet le jeton rafraîchi.
   - Pour reprendre ce qu'il avait mis dans l'ancienne (les noms, la méthode de connexion), il déclare un
     paramètre de plus : `.withParam(i, new RefreshedAuthorizationSupplierBuilder(), true)`, ou un paramètre
     `@RefreshedAuthorization @Nullable`.
   - Ce paramètre vaut `null` à la connexion, et l'autorisation décodée et vérifiée au rafraîchissement. C'est
     ainsi que l'émetteur distingue les deux cas.
7. **La fenêtre de rafraîchissement est fixée à la connexion** (votre point 7).
   - Chaque émission la recalculait à « maintenant + `refreshLifeTime` ». Une session rafraîchie toutes les
     15 minutes ne finissait donc jamais, ce qui contredisait la « durée maximale de session » de votre besoin.
   - Une autorisation rafraîchie hérite désormais de la fin de fenêtre de celle qu'elle remplace :
     `refreshLifeTime` est la durée maximale d'une session, et seul le jeton d'accès (`lifeTime`) est renouvelé.
     Pour vos 10 heures : `refreshLifeTime(10, HOURS)`.
   - Le cadre la pose avant la signature.
   - **Votre émetteur, qui produit le jeton lui-même, doit la respecter.** Il la trouve sur la requête
     d'opération, sous `refreshNotAfter` (un `Instant`, absent à la connexion). Nous n'avons pas pu lire votre
     émetteur, absent du dépôt local de palliad.
8. **La route est montée** (votre point 8) : `POST /<authentificateur>/refresh`, chez vous `/users/refresh`.
   - Le jeton à échanger voyage dans `Authorization: Bearer <jwt>`, comme sur toute requête, sans corps. Un
     jeton d'accès déjà expiré est accepté : seule compte sa fenêtre de rafraîchissement.
   - Le nouveau jeton revient dans `X-Authorization`, et le contexte de sécurité épuré dans le corps, comme à la
     connexion.
   - En appel interne, l'autorisation se passe toujours sous `entity`.
   - Le corps n'est plus lu pour un rafraîchissement : il aurait été désérialisé dans la classe de
     l'authentificateur.

L'épreuve HTTP (`GarganttuaApplicationE2ETest.RefreshOverHttp`, un vrai serveur Javalin) enchaîne connexion,
rafraîchissement par `/accounts/refresh`, rejet du rejeu et révocation de la session. `RefreshRotationIntegrationTest`
couvre chaque point : révocation stockée, enregistrement absent, rotation,
réutilisation, compte suspendu, autorités du moment, refus de la stratégie, recopie sans stratégie, émetteur,
fenêtre fixe, connexion après rotation. Lancés sur l'ancien script, ces tests échouent tous, sauf celui de la
recopie sans stratégie, qui garde volontairement le comportement d'avant.

### Ce qu'il vous faut pour des jetons de 15 minutes

- **Le DTO de l'autorisation doit stocker** `revoked` et le champ de `.refreshable().revokable(...)`. Sans ce
  dernier, rien ne tourne ; un avertissement dit lequel manque.
- **Un verrou sur le domaine d'autorisation**, distribué puisque vous avez plusieurs instances :
  `.synchronization(mutexManager, typeDeMutex)`.
  - La lecture, le contrôle et le marquage tiennent alors sous la même clé.
  - Sans lui, deux rafraîchissements simultanés du même jeton réussissent tous deux. Le test le montre : sans
    verrou il échoue à chaque passage, avec verrou un seul des huit concurrents passe.
- **Le front doit sérialiser ses rafraîchissements** : un seul en vol, les autres onglets attendent son résultat.
  Deux onglets qui rafraîchissent le même jeton ressemblent exactement à un vol, et révoquent la session.
- `lifeTime(15, MINUTES)` et `refreshLifeTime(<durée maximale de session>)`.

### Ce qui n'est pas fait : le jeton de rafraîchissement distinct (point 6)

Le jeton d'accès reste celui qu'on échange. Avec les points 1 et 4 corrigés, c'est la condition que la fiche posait
pour l'accepter : un jeton d'accès intercepté donne au plus une session, que la première rotation légitime suivante
révoque entièrement.

Un jeton opaque distinct, stocké sous forme d'empreinte et jamais envoyé à chaque requête, demande un second
artefact dans le DSL, dans le transport et dans le stockage. Nous préférons en faire une fiche à part si vous le
voulez toujours.
