# Demande — `mandatory` devrait tenir aussi à la MISE À JOUR, et refuser le VIDE

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-27
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** moyenne — la contrainte fait moins que son nom ne promet, et l'écart ne se voit pas :
elle refuse bien la création la plus évidente, ce qui donne toute confiance dans les cas où elle
laisse passer.

> **Précision d'emblée, mesurée : l'UNICITÉ, elle, est déjà tenue à la mise à jour.** Un
> `PATCH` qui pose sur une entité la valeur unique d'une autre rend **409
> `Unicity constraint violated for field 'email'`**. Rien à corriger de ce côté — la demande qui
> suit ne concerne que `mandatory`.

## Ce qui est mesuré

Sonde `locations.entity().mandatory("description")` sur un champ sans aucune autre règle, instance
JVM, base jetable :

| Requête | Statut | État du champ après | Verdict |
|---|---|---|---|
| `POST` sans le champ | **400** `Mandatory field 'description' is null` | — | conforme |
| `POST` avec `"description":""` | **201** | `""` | **échappe** |
| `POST` avec `"description":"   "` | **201** | `"   "` | **échappe** |
| `PATCH` partiel, champ absent | 200 | conservé | conforme |
| `PATCH {"description":null}` | 200 | **conservé** | conforme (`ignoreNull`) |
| `PATCH {"description":""}` | **200** | **vidé** | **trou** |
| `PUT` complet, champ absent | 200 | conservé | conforme |

## Les deux conséquences

**1. Un champ obligatoire peut être vidé après coup.** `PATCH {"champ":""}` réussit. La contrainte
protège donc l'instant de la création et rien d'autre : il suffit d'une seconde requête pour obtenir
l'état que la première interdisait. C'est le point central de cette demande.

**2. `mandatory` couvre MOINS qu'un formulaire.** Les gardes d'écran s'écrivent `if (!x)`, ce qui
refuse la chaîne vide ; `mandatory` teste `== null`. Un consommateur qui remplace ses gardes de
formulaire par `mandatory`, en croyant renforcer, **affaiblit** — et rien ne le lui dit.

C'est ce qui a décidé palliad à ne PAS s'en remettre à `mandatory` : les quinze domaines gardés le
sont par des crochets `beforeCreate` + `beforeUpdate` écrits à la main, qui refusent nul ET vide à
la création et refusent, à la mise à jour, un champ *présent et vide*. Ce code n'existerait pas si
la contrainte du cadre couvrait ces cas.

## Ce qui est demandé

Que `mandatory(champ)` signifie « ce champ porte une valeur », à tout moment :

1. **À la création** — refuser `null`, la chaîne vide et la chaîne de blancs.
2. **À la mise à jour** — appliquer la même règle à toute valeur **FOURNIE**, et seulement à
   celle-là.

Le second point est le plus délicat, et c'est pourquoi il est détaillé ici : depuis que `PATCH` est
le verbe de mise à jour, **le corps est partiel**. Rejouer la règle de création telle quelle
refuserait tout corps ne portant pas l'ensemble des champs obligatoires — c'est-à-dire à peu près
toutes les requêtes qu'un écran émet. La distinction qui fonctionne, éprouvée sur palliad :

| Ce que le corps contient | Décision attendue |
|---|---|
| champ absent | laisser passer — « on n'y touche pas » |
| champ à `null` | laisser passer — c'est déjà la sémantique `ignoreNull` |
| champ à `""` ou `"   "` | **refuser** — c'est un effacement explicite |

Autrement dit : la règle de mise à jour ne regarde que ce que le client a réellement envoyé. C'est
exactement le partage `Require.present` / `Require.nonVideSiFourni` que palliad a dû écrire.

## Point de vigilance pour la mise en œuvre

Un champ rendu obligatoire ne peut PAS être alimenté par un crochet : `validateMandatories` tourne
avant les crochets libres (`CREATE_ONE.gs`). Étendre la contrainte à la mise à jour hérite du même
ordre, ce qui est cohérent — mais mérite d'être dit, car un consommateur qui pose un défaut en
`beforeUpdate` sur un champ obligatoire verra sa requête refusée avant que le défaut ne s'applique.

## Effet de bord souhaitable

Une contrainte déclarative rendant un **400** (c'est déjà le cas de `mandatory` à la création)
règlerait au passage, pour tous les cas de présence, le problème décrit dans
[`toute-exception-de-crochet-rend-500.md`](toute-exception-de-crochet-rend-500.md) : aujourd'hui, une règle équivalente écrite
par le consommateur rend un 500.

---

## Réponse de la plateforme — 2026-09-04

**Traitée, en option déclarative.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA17`.

### Une rectification sur le mécanisme

Le titre de la fiche dit « et seulement à la création ». La lecture des sources dit autre chose :
**`validateMandatories` tourne déjà à la mise à jour**, `UPDATE_ONE.gs:54`, commentaire compris —
*« Validate mandatory fields on the merged entity »*. Elle ne « ne tient pas » : elle tourne, sur
l'entité **fusionnée**, où le champ n'est jamais `null` — il y est soit conservé, soit posé à `""`.
Le test de nullité passe donc toujours.

Le trou est donc unique, et c'est celui que votre tableau mesure : **la valeur vide**, sur les deux
routes. Ce n'est pas une nuance de vocabulaire — cela décide de la forme du correctif. Rejouer la
règle de création à la mise à jour n'aurait rien ajouté ; ce qu'il fallait, c'est un contrôle qui
regarde **ce que le client a envoyé**, pas le résultat de la fusion. Après fusion, un effacement est
indiscernable d'une valeur légitime.

Votre précision d'emblée est confirmée au passage : **l'unicité est bien tenue à la mise à jour**,
rien n'a été touché de ce côté.

### Ce qui est ajouté

Une politique déclarative, `MandatoryPolicy`, avec le défaut inchangé :

```java
.mandatory("description")                              // inchangé : refuse null, rien d'autre
.mandatory("description", MandatoryPolicy.nonBlank)    // nouveau

@EntityMandatory                                        // inchangé
@EntityMandatory(MandatoryPolicy.nonBlank)              // nouveau
```

Sous `nonBlank`, exactement le partage de votre tableau — c'est celui que nous avons implémenté,
ligne pour ligne :

| Ce que le corps contient | Décision |
|---|---|
| champ absent | laisser passer |
| champ à `null` | laisser passer (sémantique `ignoreNull`) |
| champ à `""` ou `"   "` | **refuser**, 400 |

À la création, `null`, `""` et `"   "` sont refusés ensemble.

### Pourquoi une option plutôt qu'un durcissement

La fiche ne demandait pas d'opt-in — elle demandait que `mandatory` signifie « porte une valeur ».
Nous avons choisi l'option quand même, pour une raison que la fiche ne pouvait pas peser : un
consommateur qui stocke aujourd'hui une chaîne vide dans un champ `mandatory` recevrait un 400 à la
montée de version, sans l'avoir demandé ni pouvoir l'anticiper. Aucune déclaration existante ne
change donc de sens ; c'est la déclaration qui décide.

Le geste concret chez vous : remplacer `Require.present` / `Require.nonVideSiFourni` par la
déclaration, domaine par domaine, en montant l'un après l'autre plutôt que d'un coup.

### Votre point de vigilance

Confirmé et conservé : `validateMandatories` — et maintenant `validateProvidedMandatories` — tournent
**avant** les crochets libres. Un consommateur qui pose un défaut en `beforeUpdate` sur un champ
obligatoire verra sa requête refusée avant que le défaut ne s'applique. C'est cohérent avec la
création, et cela reste vrai.

### Votre effet de bord souhaitable

Il est là, et par deux chemins : la contrainte déclarative rend un 400, et depuis le traitement de
[`toute-exception-de-crochet-rend-500`](toute-exception-de-crochet-rend-500.md) une règle équivalente
que vous écrivez à la main peut rendre 400 aussi, via `ApiException.badRequest(...)`.

### Rupture d'API

`IEntityDefinition.mandatories()` rend désormais `List<Pair<ObjectAddress, MandatoryPolicy>>` et non
`List<ObjectAddress>` — la politique doit voyager avec le champ, sans quoi le contrôle en aval
devrait la deviner. Idem pour `IDomain.getMandatoryFields()`, qui délègue. **Si votre code lit l'une
des deux, il faudra le recompiler.** Nous n'avons pas trouvé d'appelant hors du cadre ; c'est écrit
ici pour que vous le vérifiiez plutôt que de le découvrir.

**Couvert par :** `MandatoryPolicyIntegrationTest` (6 tests) — les trois formes vides à la création,
l'effacement refusé à la mise à jour, le champ absent laissé tranquille, et le défaut inchangé.
