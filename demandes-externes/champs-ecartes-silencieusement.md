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
