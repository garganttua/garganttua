# garganttua-api — un champ écarté à l'écriture n'est signalé nulle part

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3)
**Date :** 2026-08-26
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** faible pour la sécurité, ÉLEVÉE pour l'observabilité

## Symptôme

Un `PATCH` qui nomme un champ que l'appelant n'a pas le droit d'écrire répond **200**, et le champ
reste inchangé en base. Mesuré :

```
PATCH /users/<x> {"authorities":["patient-read-all"]}   par un compte sans l'autorité requise
  → HTTP 200, corps = l'utilisateur
  → en base : authorities inchangé
```

Le résultat de SÉCURITÉ est correct — rien n'a été écrit. C'est l'**observable** qui ne l'est pas :
un écran qui teste le code HTTP conclut que l'attribution a réussi, et l'affiche comme telle.

Le même silence existe, sans aucun rapport avec les droits, pour la fusion `ignoreNull` : un champ
absent du corps n'est pas écrit, et rien ne le distingue d'un champ écrit à l'identique.

## Ce que nous ne demandons PAS

Passer de 200 à 403. Ce serait un changement de comportement qui casserait tout client traitant le
2xx comme un succès, et il est discutable : la requête a bien été traitée, partiellement.

## Ce que nous demandons

Deux en-têtes de réponse, sur les opérations d'écriture :

```
X-Garganttua-Fields-Applied: firstName,lastName
X-Garganttua-Fields-Rejected: authorities,superOwner
```

Trois exigences, par ordre d'importance :

1. **Un champ rejeté y figure quelle qu'en soit la raison** — droit manquant, champ non déclaré,
   valeur refusée. Sinon l'appelant doit encore deviner laquelle des trois s'applique.
2. **Les en-têtes sont présents même quand rien n'est rejeté** (liste vide). Sinon leur absence
   devient ambiguë entre « rien rejeté » et « cadre trop ancien », et aucun client ne peut s'y fier.
3. **Les noms sont ceux du DTO**, pas ceux de l'entité : c'est le vocabulaire que le client a envoyé.

## Pourquoi c'est peu coûteux

Le cadre SAIT déjà quels champs il écarte — c'est lui qui les écarte, dans l'étape de liste blanche.
L'information existe au moment où la réponse est construite ; elle n'est simplement dite à personne.

## Contournement en place chez nous

Relire la réponse et comparer champ par champ, jamais se fier au code HTTP. C'est écrit dans notre
registre d'observations comme règle pour tout écran d'administration — mais c'est une discipline, pas
une garantie : rien ne fait échouer l'écran qui l'oublie.

---

## Réponse de la plateforme — 2026-09-04

**Traitée.** Les deux en-têtes existent, avec les noms de DTO. Corrigée sur `main`, à paraître dans
`3.0.0-ALPHA17`.

```
PATCH /users/<x> {"authorities":["patient-read-all"], "firstName":"Alice"}

HTTP 200
X-Garganttua-Fields-Applied:  firstName
X-Garganttua-Fields-Rejected: authorities
```

### Vos trois exigences

**2. Présents même quand rien n'est rejeté.** Tenue, telle quelle. Les deux en-têtes sont émis sur
toute opération d'écriture, valeur vide plutôt qu'en-tête absent — pour la raison exacte que vous
donnez : une absence serait ambiguë entre « rien rejeté » et « cadre trop ancien », et aucun client
ne pourrait s'y fier.

**3. Les noms sont ceux du DTO.** Tenue. La liste blanche travaille sur des adresses d'entité ; les
`@FieldMappingRule` du DTO sont relues à l'envers pour retrouver le nom que le client a envoyé. Le
DTO retenu est **le premier du domaine**, celui-là même dont `SerializationExpressions` rend le
corps — donc les noms rapportés sont les noms du corps que l'appelant reçoit. Un domaine qui en
déclare plusieurs voit ceux du premier.

**1. Un champ rejeté y figure quelle qu'en soit la raison.** Tenue **à la création**, où le
dépouillement voit tous les champs et rapporte chacun de ceux qui portaient une valeur. **Partielle
à la mise à jour** : le rapport couvre les champs refusés faute d'autorité — votre cas mesuré — et
**pas** un champ que le domaine ne déclare pas comme modifiable. Il faudrait pour cela énumérer ce
que le client a envoyé, et un `PUT` portant le DTO complet inonderait alors l'en-tête de tous les
champs d'identité (`uuid`, `id`, `tenantId`) qu'aucune règle ne couvre et qui ne sont jamais
modifiables par un client. Nous avons préféré un en-tête lisible à un en-tête exhaustif et bruyant.
**Si ce manque vous coûte, la fiche est à rouvrir** — le compromis mérite d'être rediscuté avec un
cas réel plutôt que sur cette prudence.

Une limite à connaître, dans les deux sens : un champ envoyé **explicitement à `null`** est
indiscernable d'un champ absent une fois le corps mappé. Il n'apparaît donc dans aucune des deux
listes.

### Ce que vous ne demandiez pas, et qui n'a pas été fait

Le statut reste **200**. Vous écrivez que passer à 403 casserait tout client traitant le 2xx comme
un succès, et que c'est discutable puisque la requête a bien été traitée, partiellement. Nous
sommes d'accord, et c'est écrit dans le code pour que personne ne « corrige » cela plus tard.

Le silence de la fusion `ignoreNull` que vous mentionnez en passant — un champ absent du corps n'est
pas écrit — reste silencieux : il n'est ni appliqué ni rejeté, parce que le client ne l'a pas nommé.

### Rupture d'API

`IEntityUpdater.update(...)` et `IEntityCreator.create(...)` rendent désormais un
`EntityWriteOutcome` (l'entité **plus** les deux listes) au lieu de l'entité seule. `IOperationResponse`
gagne `getWrittenFields()`, en méthode `default` — donc additif de ce côté. Seul le cadre implémente
les deux premières ; si vous en aviez branché une, il faudra la recompiler.

### Sur votre contournement

« Relire la réponse et comparer champ par champ, jamais se fier au code HTTP » devient une
vérification d'en-tête, ce qui reste une discipline — mais une discipline sur laquelle un test peut
mordre, ce que la comparaison champ à champ ne permettait pas vraiment.

**Couvert par :** `WrittenFieldsIntegrationTest` (7 tests) — le champ refusé nommé, le champ
appliqué nommé, le statut inchangé, le rapport vide mais présent, et le rapport vide sur une lecture.
