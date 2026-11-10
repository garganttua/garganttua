# Authentification à deux facteurs : une étape entre l'authentification et l'émission du jeton

**À l'attention de :** garganttua-api (DSL de l'authentificateur, scripts `AUTHENTICATE` / `CREATE_AUTHORIZATION`,
binding Javalin)
**Émis par :** palliad (v3, AOT pur, natif ; jeton porteur ES256 stocké, `checkStoredOnVerify(true)` ; deux
authentificateurs, l'un pour des personnes, l'autre pour des machines, sur le même domaine d'autorisations)
**Date :** 2026-11-06
**Version constatée :** garganttua-api `3.0.0-ALPHA33` (sources lues au tag, HEAD `26c6116`)
**Gravité :** c'est une évolution, pas un bogue, mais elle est bloquante pour le consommateur. Un hébergement de données
de santé exige un second facteur à la connexion, au plus tard en janvier 2027 pour palliad. Aujourd'hui, un
consommateur ne peut l'ajouter qu'en contournant le cadre, et les contournements possibles ouvrent un **contournement
du second facteur** (§ Constat, point 3). Tout est **lu** dans les sources, rien n'a été mesuré.

## Le besoin

Un authentificateur doit pouvoir dire, après avoir vérifié le premier facteur (le mot de passe) : « identité établie,
mais un second facteur est attendu ». Le jeton ne doit être émis qu'après la preuve de ce second facteur.

**Ce que le consommateur fait lui-même** :

- la nature du facteur (TOTP, WebAuthn, code de secours…) ;
- le stockage de ses secrets ;
- la politique qui décide qui y est soumis ;
- l'enrôlement.

**Ce que le cadre doit fournir** :

- le **séquencement** : l'issue intermédiaire, un état intermédiaire sûr, une opération d'étape 2 ;
- la garantie qu'**aucun jeton complet ne sort sans les deux facteurs**, réutilisation comprise.

## Constat (lu)

1. **L'authentification et l'émission sont une seule opération sans point d'arrêt.**
   - `POST /<domaine>/authenticate` joue `scripts/business/AUTHENTICATE.gs`, puis `CREATE_AUTHORIZATION.gs`.
   - Le second émet le jeton dès qu'un `IAuthentication` authentifié existe.
   - L'émetteur sur mesure (`.authentication(…).authorization(issuer, "method")`, `SecurityExpressionsSupport.
     runCustomIssuer`) ne fait que **remplacer la fabrication** du jeton, après coup : il ne peut pas suspendre.
2. **Une stratégie ne sait dire que oui ou non.**
   - `SecurityAuthenticationExpressions.tryAuthenticate` (l. 253) garde la première `IAuthentication` authentifiée,
     sinon lève `"All authentication methods failed"`.
   - Le client reçoit alors un 401 `{"error": "All authentication methods failed"}` (`JavalinResponses`, l. 116).
   - Une stratégie n'a aucun moyen de rendre une troisième issue, ni des données au client (méthodes possibles, défi).
3. **La réutilisation ignore le niveau d'authentification.**
   - Avant d'émettre, `findReusableAuthorization` (l. 218) appelle `lookupValidAuthorization` (l. 78). Le filtre de
     `buildReuseFilter` (l. 101) porte sur le propriétaire, le tenant, `revoked = false` et `expiration > now`.
   - Si un consommateur faisait émettre un jeton « partiel » à l'étape 1 (même domaine, `type` différent, sans
     autorités), une connexion au seul mot de passe recevrait le **jeton complet** d'une session déjà ouverte par la
     même personne.
   - Le second facteur serait contourné précisément quand un mot de passe a été volé pendant que la victime travaille.
4. **Un jeton sans autorités reste un appelant authentifié.**
   - Il passe `VERIFY_AUTHORIZATION` et ouvre tout ce qui est déclaré en `Access.authenticated`, sans autorité.
   - Un « jeton partiel » porteur n'est donc pas contenu par le cadre. Le consommateur devrait poser une liste blanche
     de chemins devant le pipeline.
5. **Le seul crochet de la vérification est trop large.**
   - `.authorization().reconcile(supplier, "method")` existe.
   - Mais il **remplace entièrement** la réconciliation R1-R3 et le recalcul serveur du statut super
     (`SecurityExpressions.reconcileCaller`, l. 330-354).
   - S'en servir pour refuser un jeton partiel obligerait le consommateur à réécrire la partie la plus sensible du
     cadre.
6. **Une étape 2 portée par un cas d'usage du consommateur ne peut pas émettre de jeton.**
   - Signature, stockage, encodage : tout vit dans des expressions de script (`issueAuthorization`,
     `persistIfStorable`, `encodeIfPossible`) qui dépendent du contexte d'exécution du workflow.
   - Ce n'est pas une API publique.
7. **La requête d'authentification n'a que deux champs.**
   - `AuthenticationRequest(String login, Object credentials)`.
   - `AuthenticateCredentialsSupplier` ne rend que `byte[]` ou `String` : un objet `{password, code}` donne
     `Optional.empty()`.
8. **Le module `security-authentication-challenge`** (v2, `ChallengeAuthentication`) est hors du build
   (`api/security/pom.xml`, l. 36-40) et ne fait pas de second facteur. `PRESENTATION.md` le présente encore « pour la
   MFA », ce qui induit en erreur.

## Ce qui est demandé

Une étape de second facteur, **déclarée et facultative par authentificateur**, dont la proposition suit. Les noms sont
à votre jugement.

### 1. La déclaration dans le DSL

```java
users.security().authenticator()
    .login("id")
    .authentication(passwordStrategy)
        .secondFactor()
            // Décide, APRÈS un premier facteur réussi, ce qui est exigé. Méthode du consommateur :
            //   SecondFactorRequirement requirementOf(Object principal, IAuthentication first, IOperationRequest req)
            .requirement(new FixedSupplierBuilder<>(policy, …), "requirementOf")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new FirstFactorAuthenticationSupplierBuilder())   // nouveau
                .withParam(2, new OperationRequestSupplierBuilder())            // en-têtes accessibles (appareil de confiance)
            .up()
            // Vérifie une preuve. Méthode du consommateur :
            //   SecondFactorVerdict verify(Object principal, SecondFactorProof proof, IPartialAuthentication partial)
            .verification(new FixedSupplierBuilder<>(verifier, …), "verify")
                .withParam(…)
            .up()
            .partialStore(partialDomain)          // domaine déclaré par le consommateur (voir § 3)
            .lifeTime(5, TimeUnit.MINUTES)        // durée de l'état intermédiaire
            .maxAttempts(5)                       // preuves fausses tolérées, puis l'état est consommé
        .up()
        .authorization(authorizations).lifeTime(24, TimeUnit.HOURS).key(…);
```

**Les types fournis par `commons`** (records `@Reflected`, pour le natif) :

- **`SecondFactorRequirement`** : `none()`, `verify(List<String> methods)` ou `enrol(List<String> methods)`.
  - Avec `none()`, le jeton est émis comme aujourd'hui.
  - Avec `enrol`, la personne n'a pas encore de facteur et doit en enrôler un avant d'obtenir un jeton.
- **`SecondFactorProof(String method, Object value)`** : lu dans le corps de l'étape 2.
- **`SecondFactorVerdict`** : `accepted(String method)`, `rejected(String reason)` ou `enrolled(String method)`.
- **`IPartialAuthentication`** : identifiant, propriétaire qualifié, tenant, méthodes exigées, échéance, essais restants.
  En lecture seule pour le consommateur.

### 2. L'étape 1 : `authenticate` rend une issue intermédiaire, sans jeton

Quand `requirementOf` rend `verify` ou `enrol` :

- **aucune autorisation n'est émise, réutilisée ni stockée** ;
- **pas d'en-tête `X-Authorization`** ;
- le cadre crée un état intermédiaire et répond, par exemple :

```json
{ "secondFactor": { "challenge": "<opaque, 256 bits>", "mode": "verify", "methods": ["totp", "recovery"],
                    "expiresAt": "2026-11-06T10:05:00Z" } }
```

Le statut HTTP de cette réponse est à votre choix : 200, ou un 401 avec un code stable dans le corps. Deux contraintes :

- un client doit pouvoir la distinguer d'un échec **sans analyser le message** ;
- le consommateur doit pouvoir la distinguer dans ses filtres. palliad compte les 401 comme des échecs de mot de passe
  pour sa limitation des essais.

**Le défi n'est PAS un jeton porteur.** Il ne passe jamais par `VERIFY_AUTHORIZATION` et n'ouvre aucune opération
`Access.authenticated`. C'est ce qui rend les points 4 et 5 du constat sans objet.

### 3. L'état intermédiaire : une « autorisation partielle » à portée et durée limitées

- **Stockage** : stocké côté serveur, donc vrai en cluster. Seule l'**empreinte** du défi est conservée.
- **Contenu** : il lie le défi au principal, au tenant, à l'authentificateur, à l'`IAuthentication` du premier facteur
  (autorités comprises, gardées côté serveur) et aux méthodes exigées.
- **Durée et essais** : il expire (`lifeTime`) et compte les essais (`maxAttempts`).
- **Usage unique** : il est consommé au succès comme à l'épuisement.
- **Où le stocker** : dans un domaine que le consommateur déclare (`partialStore(domain)`), avec un DSL de champs comme
  celui des autorisations, pour qu'il suive la base, le DAO et les sauvegardes de l'application. Une entité fournie par
  le cadre conviendrait aussi. À votre jugement.
- **Portée** : en plus de l'étape 2, l'état ouvre **les cas d'usage du domaine authentificateur que le consommateur
  désigne explicitement**. C'est l'enrôlement quand `mode = enrol`. Par exemple
  `.security().access(Access.secondFactorPending)`, ou une liste nommée dans `.secondFactor().scopes("enrol-totp",
  "confirm-totp")`.
  - Le défi est alors présenté par un en-tête dédié, distinct du porteur.
  - Le cas d'usage reçoit le principal par un fournisseur.
  - **Aucune autre opération ne doit l'accepter.**

### 4. L'étape 2 : une opération qui finit le travail de `CREATE_AUTHORIZATION`

`POST /<domaine>/authenticate/second-factor`, corps `{challenge, method, value}` :

1. Le cadre retrouve l'état par l'empreinte du défi, puis vérifie l'échéance et les essais restants.
2. Il appelle `verify`.
3. **Si la preuve est acceptée**, il consomme l'état et joue **la même fin** que `CREATE_AUTHORIZATION` :
   - réutilisation (point 5 ci-dessous), sinon émission, signature, encodage et stockage ;
   - le jeton dans `X-Authorization`, le contexte épuré dans le corps ;
   - l'`IAuthentication` reçoit les **méthodes d'authentification** utilisées, par exemple `["pwd", "totp"]` (au sens de
     la claim `amr` de la RFC 8176). Elles sont recopiées sur l'autorisation par un champ déclaré
     (`.authorization().authenticationMethods("amr")`), ou à défaut dans `type`.
4. **Si la preuve est refusée** : 401 avec un code stable (`second_factor_invalid`), et le compteur décrémenté.
5. **Si l'état est épuisé ou expiré** : 401 `second_factor_expired` ou `second_factor_exhausted`. Le client doit
   recommencer l'étape 1.

### 5. Une réutilisation qui tient compte du niveau

- Quand l'authentificateur déclare `.secondFactor()` ou un champ de méthodes, `findReusableAuthorization` ne rend
  qu'une autorisation dont les méthodes **couvrent** celles de la connexion en cours.
- Un jeton émis au seul mot de passe (`none()`) n'est donc jamais rendu à une connexion qui a exigé un second facteur,
  et l'inverse non plus.
- Demande annexe, utile indépendamment : `.authorization().reuse(false)`, pour qu'une connexion émette toujours un
  jeton neuf.

### 6. Rafraîchissement, révocation, `checkStoredOnVerify`

- **`REFRESH_AUTHORIZATION`** recopie les méthodes de l'autorisation présentée, comme `synthAuthFromPrincipal` recopie
  déjà `type`. Un rafraîchissement ne doit jamais **élever** le niveau.
- **`checkStoredOnVerify`** et la révocation sont inchangés. L'état intermédiaire n'est jamais un porteur, donc jamais
  vérifié par ce chemin.
- **Souhait, sans priorité** : un moyen public de révoquer toutes les autorisations vivantes d'un principal. Le
  consommateur en a besoin à l'enrôlement et à la réinitialisation d'un second facteur. Il sait le faire lui-même
  aujourd'hui.

### 7. Plus tard, en option : exiger un second facteur récent pour une opération

`.security().requireAuthenticationMethods("totp")` et/ou `.maxAuthenticationAge(10, MINUTES)` sur une opération, lus
dans les méthodes et l'heure d'authentification portées par l'autorisation vérifiée. C'est utile pour les gestes
sensibles (clés, droits, export). La fiche ne le demande pas pour la première livraison, mais l'émission devrait déjà
porter l'heure d'authentification pour ne pas fermer cette porte.

## Compatibilité

- **Facultatif par authentificateur.** Un authentificateur sans `.secondFactor()` se comporte **à l'identique** :
  - mêmes scripts, même réponse ;
  - réutilisation inchangée tant qu'aucun champ de méthodes n'est déclaré.

  palliad déclare un second authentificateur pour des machines, sur le même domaine d'autorisations : il ne doit pas
  être touché.
- **Format du jeton inchangé**, sauf le champ de méthodes s'il est déclaré.
- **Natif** : nouveaux records `@Reflected`, et méthodes du consommateur résolues par nom comme `authenticate`
  aujourd'hui. Il faut un test d'intégration en image native, la leçon des ALPHA11 à ALPHA13.
- **Transports** : la réponse de l'étape 1 et les codes de l'étape 2 doivent être rendus de la même façon par le binding
  Javalin et par les autres.

## Tests attendus

1. Sans `.secondFactor()` : réponse et jeton identiques à l'ALPHA33 (non-régression, réutilisation comprise).
2. `requirementOf` rend `verify` : pas d'en-tête de jeton, pas de ligne dans le domaine d'autorisations, un défi rendu.
3. Le défi présenté comme porteur, sur une opération `Access.authenticated` → 401.
4. Étape 2 avec une preuve juste → jeton émis, méthodes `["pwd","totp"]` sur l'autorisation, état consommé, et le même
   défi rejoué → refus.
5. Preuves fausses : `maxAttempts` atteint → état consommé, une preuve juste ensuite → refus.
6. Défi expiré → refus.
7. **Contournement par réutilisation** : un jeton complet vivant pour le principal, puis une connexion au seul mot de
   passe → aucun jeton rendu, défi rendu. Un jeton émis avec `none()` n'est pas réutilisé par une connexion à deux
   facteurs.
8. `mode = enrol` : seul le cas d'usage désigné accepte le défi, tout autre cas d'usage du domaine le refuse.
9. Deux nœuds sur la même base : étape 1 sur l'un, étape 2 sur l'autre → réussite (état stocké, pas en mémoire).
10. Rafraîchissement d'un jeton à deux facteurs → méthodes conservées ; d'un jeton `pwd` → pas d'élévation.
11. Une exception levée par `requirementOf` ou `verify` → refus journalisé, jamais un jeton.
12. Les mêmes parcours en image native.

## Ce que la fiche ne demande pas

- **Pas d'implémentation de TOTP, WebAuthn ou d'envoi de codes dans le cadre**, ni de stockage des secrets, ni de
  politique d'obligation. Tout cela reste au consommateur. Des aides (TOTP RFC 6238, Base32) pourraient venir plus
  tard dans un module séparé, sans être un prérequis.
- **Ne pas faire de l'état intermédiaire un jeton porteur**, même à autorités vides : c'est précisément ce qui rend le
  contournement possible (constat, points 3 et 4).
- **Ne pas modifier `VERIFY_AUTHORIZATION`**, la sémantique d'`Access.authenticated`, ni la réconciliation R1-R3.
- **Ne pas faire revivre `security-authentication-challenge`** : ce n'est pas le même besoin.
- **Ne pas changer le comportement d'un authentificateur qui ne déclare rien.**

## Questions à l'équipe du cadre

1. Le statut HTTP de l'issue intermédiaire : 200 avec un corps dédié, ou 401 avec un code stable ?
2. L'état intermédiaire : dans un domaine déclaré par le consommateur, ou dans une entité fournie par le cadre ?
3. Le champ des méthodes : un champ nouveau (`authenticationMethods`), ou le `type` existant ?
4. Une échéance de livraison compatible avec un audit en janvier 2027 est-elle envisageable ? Sinon, le consommateur
   posera un palliatif limité aux comptes d'administration : preuve du second facteur dans `credentials`, vérifiée par
   la stratégie, sans état intermédiaire. Il le retirera à la livraison.
