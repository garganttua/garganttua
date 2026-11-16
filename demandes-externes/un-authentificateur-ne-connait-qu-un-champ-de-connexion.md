# Un authentificateur ne connaît qu'un champ de connexion : impossible de se connecter par un second identifiant

**À l'attention de :** garganttua-api (DSL de l'authentificateur, `PrincipalSupplier`, script `AUTHENTICATE`)
**Émis par :** palliad (v3, AOT pur, natif ; un authentificateur `users`, connexion par adresse, jeton ES256 stocké,
rafraîchissement ; connexion Pro Santé Connect en cours sur la branche `feat/pro-sante-connect`)
**Date :** 2026-11-16
**Version constatée :** garganttua-api `3.0.0-ALPHA35` (sources lues au tag, HEAD `b83244d`)
**Gravité :** évolution, pas un bogue. Elle bloque une exigence du consommateur : la connexion des professionnels de
santé par Pro Santé Connect (e-CPS, carte CPS), attendue par la PGSSI-S pour les services qui traitent des données
de santé, doit identifier le compte par son **numéro RPPS**, et non par l'adresse électronique. Aujourd'hui, la seule
voie consiste à détourner la stratégie du mot de passe : elle reconnaît une preuve PSC dans `credentials`, avec
toujours l'adresse dans `login`. Tout est **lu** dans les sources, rien n'a été mesuré.

## Le besoin

Une même entité authentifiable (le compte d'une personne) doit pouvoir être trouvée par **plus d'un identifiant de
connexion**, chacun avec **sa stratégie** :

| Connexion | `login` | `credentials` | Stratégie |
|---|---|---|---|
| Par mot de passe | l'adresse (champ `id`) | le mot de passe (et le second facteur) | `authenticate` du mot de passe |
| Par Pro Santé Connect | le **numéro RPPS** (champ `rpps`) | une preuve de l'authentification PSC, émise par le consommateur après avoir vérifié l'identité côté serveur | `authenticate` PSC |

Particularité de la seconde : le RPPS n'est **unique que dans un tenant**. Une même personne a un compte par équipe,
chacun avec sa propre adresse, puisque l'adresse est unique sur toute la plateforme. La recherche par RPPS doit donc
se faire **dans le tenant de la requête** (`X-Tenant-Id`). Le cadre le fait déjà pour un authentificateur de portée
`tenant` (voir Constat, point 3).

Le jeton émis doit rester **celui du domaine** : même propriétaire (`users:<uuid>`), même domaine d'autorisations,
même clé, même émetteur sur mesure, même rafraîchissement, même révocation. Seule la méthode (`type`) diffère.

## Constat (lu)

1. **Un domaine n'a qu'un authentificateur.**
   `IDomainSecurityBuilder.authenticator()` rend l'unique `IAuthenticatorBuilder` du domaine.
2. **Un authentificateur n'a qu'un champ de connexion.**
   `IAuthenticatorBuilder.login(String | IField | ObjectAddress)` pose un champ, et
   `IAuthenticatorDefinition.login()` n'en rend qu'un. Les stratégies, elles, peuvent être plusieurs
   (`authenticationDefinitions()` est une liste, essayée en cascade par
   `SecurityAuthenticationExpressions.tryAuthenticate`, l. 254). Mais elles reçoivent toutes **le même principal**,
   trouvé avant elles par ce champ unique.
3. **Le principal est cherché par ce seul champ.** `PrincipalSupplier.findPrincipal` (l. 120-152) filtre sur
   `Filter.eq(<champ de connexion>, login)`. Pour un authentificateur de portée `tenant`, il ajoute le tenant de
   l'appelant (l. 136-140). Avec un RPPS dans `login`, la recherche se fait sur l'adresse, ne trouve rien, et la
   requête finit en 401 avant toute stratégie (`"User not found for login"`).
4. **Un second domaine authentificateur ne convient pas.** C'est la voie qu'emploie palliad pour ses comptes de
   machine. Mais le propriétaire du jeton porte alors le nom de ce second domaine (`<autre-domaine>:<uuid>`), si bien
   que tout ce qui reconnaît une personne par `users:<uuid>` (révocation, journal, droits) ne la reconnaît plus. Et
   le second domaine devrait recopier la collection des comptes.

## Attendu

Une des deux formes, au choix de la plateforme :

- **A. Plusieurs champs de connexion sur un authentificateur, chacun lié à ses stratégies.** Par exemple, une
  « connexion » nommée qui porte son champ, sa portée et ses stratégies :
  `.authenticator().login("id").authentication(motDePasse)` puis
  `.alternateLogin("psc", "rpps").scope(tenant).authentication(psc)`. La requête dit laquelle elle emploie : un
  attribut du corps, ou une route `POST /<domaine>/authenticate/<connexion>`.
- **B. Plusieurs authentificateurs sur un domaine**, qui partagent l'autorisation (domaine d'autorisations, clé,
  émetteur, durée de vie, rafraîchissement), avec chacun son champ de connexion, sa portée et ses stratégies. Le
  propriétaire du jeton reste `<domaine>:<uuid>`.

Dans les deux cas :

- **la recherche par le second champ respecte la portée** déclarée pour lui (`tenant` : le tenant de la requête), et
  refuse quand elle trouve plus d'une entité au lieu de prendre la première ;
- **la stratégie reçoit le principal trouvé par SON champ**, et l'`IAuthentication` qu'elle rend porte sa méthode
  (`type`), comme aujourd'hui ;
- **le rafraîchissement d'un jeton** ainsi émis rappelle la stratégie de la connexion qui l'a émis, ou la méthode
  `refresh` commune ;
- **la réutilisation d'un jeton vivant** (`findReusableAuthorization`) distingue la connexion d'origine. C'est le même
  défaut que dans la fiche `authentification-a-deux-facteurs` : mesuré chez palliad le 2026-11-16, une connexion PSC
  d'un compte déjà ouvert par mot de passe reçoit le jeton `type: pwd` de cette session.

## Ce que la fiche ne demande pas

- **Pro Santé Connect dans le cadre.** Le protocole OpenID Connect, l'échange du code, la vérification de l'`id_token`
  et la preuve placée dans `credentials` restent l'affaire du consommateur, dans sa stratégie.
- **Un changement du comportement actuel.** Un authentificateur à un seul champ de connexion doit fonctionner
  exactement comme aujourd'hui.
- **Une recherche « par n'importe quel champ ».** Seuls les champs déclarés comme champs de connexion doivent pouvoir
  servir à trouver un principal.

## Réponse — 2026-11-16, sur `main`, à paraître dans la prochaine ALPHA

**Forme A retenue : plusieurs connexions sur un même authentificateur.** La forme B aurait touché tous les endroits
qui supposent un seul authentificateur par domaine (vérification, rafraîchissement, état du compte, émission), pour
partager presque tout entre deux authentificateurs. Avec A, seul ce qui diffère est propre à la connexion : le
champ, la portée et les stratégies. Le reste appartient à l'authentificateur. Le jeton reste celui du domaine :
propriétaire `users:<uuid>`, même clé, même émetteur, même rafraîchissement, même révocation.

### Ce que vous déclarez

```java
.authenticator()
    .login("id").authentication(motDePasse)               // la connexion par défaut, inchangée
    .connection("psc")
        .login("rpps").scope(AuthenticatorScope.tenant)   // le RPPS, unique dans un tenant
        .authentication(proSanteConnect)
    .up()
```

Par annotation, un second champ porte `@AuthenticatorLogin(connection = "psc", scope = AuthenticatorScope.tenant,
authentications = ProSanteConnect.class)`. Le champ sans nom reste la connexion par défaut.

### Ce que fait le cadre

1. **Le choix de la connexion.**
   - En HTTP, par la route : `POST /users/authenticate/psc`, à côté de `/users/authenticate`. Une connexion non
     déclarée n'a pas de route : 404.
   - En appel interne, par l'argument `IOperationRequest.CONNECTION`. Une connexion inconnue y est refusée : 400.
   - Sans nom, c'est la connexion par défaut, exactement comme avant.
2. **La recherche du principal** se fait par le champ de la connexion, dans sa portée. Pour vous, c'est le tenant de
   `X-Tenant-Id`, comme le fait déjà un authentificateur de portée `tenant`.
3. **Plusieurs comptes trouvés : refus (401)** au lieu de prendre le premier. La règle vaut pour toutes les
   connexions, **y compris celle par défaut** : prendre « le premier » de deux comptes au même identifiant
   authentifiait celui que le stockage rendait en premier. C'est un changement de comportement, mais il ne touche
   qu'une base déjà incohérente.
4. **Les stratégies essayées** sont celles de la connexion, et elles seules : un mot de passe présenté par `psc`
   est refusé.
   - La stratégie reçoit le principal trouvé par **son** champ.
   - `AuthenticatorDefinitionSupplierBuilder` lui passe l'authentificateur tel qu'atteint : `login()` y désigne
     `rpps`.
5. **La réutilisation d'un jeton vivant distingue la méthode** : la valeur que rend la stratégie
   (`IAuthentication.authorization()`), posée dans le `type` du jeton.
   - Une connexion PSC d'un compte déjà ouvert par mot de passe reçoit un jeton neuf de type `psc`. Une seconde
     connexion par mot de passe retrouve toujours le jeton `pwd`.
   - Un stockage qui ne garde pas le `type` réutilise comme avant.
   - C'est aussi le défaut que signale la fiche `authentification-a-deux-facteurs` ; il est corrigé pour les deux.
6. **Le rafraîchissement** essaie toutes les méthodes `refresh` déclarées : celle de la connexion par défaut, puis
   celles des connexions.
   - Chacune reçoit le jeton remplacé (`RefreshedAuthorizationSupplierBuilder` ou `@RefreshedAuthorization`).
   - Une stratégie PSC décline un jeton qui n'est pas de type `psc` en ne s'authentifiant pas, et la suivante est
     essayée.
   - Le nom de la connexion n'a pas besoin d'être stocké dans le jeton.

### Ce qui le prouve

- `AuthenticatorConnectionIntegrationTest` :
  - recherche par RPPS dans le tenant de la requête (le même RPPS dans deux tenants donne deux comptes) ;
  - connexion par défaut inchangée ;
  - stratégies propres à chaque connexion ;
  - connexion inconnue ;
  - doublons refusés par les deux connexions ;
  - réutilisation par méthode ;
  - rafraîchissement par la stratégie de la méthode du jeton.
- `AnnotatedConnectionIntegrationTest` : la même chose déclarée par annotations.
- `GarganttuaApplicationE2ETest.ConnectionOverHttp` : un vrai serveur Javalin, `POST /accounts/authenticate/code`,
  la stratégie qui n'est pas la sienne refusée, une connexion non déclarée en 404.

### Ce qui reste chez vous

Pro Santé Connect lui-même (l'échange OpenID Connect, la vérification de l'`id_token`, la preuve placée dans
`credentials`) reste dans votre stratégie, comme la fiche le prévoit. Pour le rafraîchissement d'un jeton PSC, votre
stratégie PSC déclare une méthode `refresh` qui décline les jetons d'un autre `type`.
