# Demandes externes

**Ce dossier accueille les fiches déposées par les CONSOMMATEURS de la plateforme** — les projets
qui construisent sur `core`, `api` ou `events` sans en écrire une ligne. Bogues constatés, écarts
entre ce que le DSL donne à lire et ce qu'il fait, manques qui obligent à un contournement.

C'est le seul endroit prévu pour cela. Une fiche vivant dans le dépôt du consommateur n'est lue
que par le consommateur : elle décrit un défaut de la plateforme à des gens qui ne peuvent pas le
corriger, pendant que ceux qui le peuvent ne savent pas qu'elle existe. Les sept premières fiches
ci-dessous ont passé leurs premières semaines dans `palliad/docs/bugs/`, exactement dans cette
situation.

## Ce qu'une fiche doit porter

L'en-tête, tel que les fiches existantes l'écrivent — il permet de trier sans ouvrir :

```
**À l'attention de :** garganttua-api
**Émis par :** <projet> (contexte utile : v3, AOT pur, natif…)
**Date :** AAAA-MM-JJ
**Version constatée :** garganttua-api `3.0.0-ALPHAxx`
**Gravité :** <et surtout POURQUOI — l'effet, pas l'étiquette>
```

Puis, dans l'ordre : le **symptôme** tel qu'on le rencontre, la **cause** avec le fichier et les
lignes quand elle a été lue dans les sources, l'**attendu**, et ce que la fiche **ne demande pas**
— cette dernière section évite qu'un correctif dépasse la demande et casse d'autres consommateurs.

Une distinction que les fiches tiennent et qu'il faut garder : **mesuré** (reproduit en HTTP,
avant/après) n'est pas **lu** (établi par lecture des sources, sans exécution). Les deux sont
recevables, ils n'engagent pas autant.

> **Les chemins de sources sont ceux de ce dépôt.** Une fiche qui cite
> `api/core/src/main/java/…` se relit ici telle quelle — ce qui n'était pas le cas chez le
> consommateur, où le chemin ne désignait rien.

## Ce que ce dossier n'est pas

Une file de tickets, ni un engagement de traitement. Rien ici n'est priorisé par le fait d'y être :
une fiche décrit ce qui a été constaté et ce qui aiderait, la décision reste à la plateforme.
Quand une demande est traitée, le geste utile est de le dire **dans la fiche** — version qui
corrige, ou refus motivé — plutôt que de l'effacer : le consommateur qui l'a écrite viendra
vérifier, et un dossier vide ne lui apprend rien.

## Demandes de la plateforme vers un consommateur

Ce dossier est prévu pour l'autre sens, et il le reste. Mais quand la plateforme a besoin d'une
mesure que seul un déploiement peut produire, la demande se pose là où vit la conversation — sinon
elle n'est pas lue, ce qui est exactement l'argument que ce README fait pour les fiches des
consommateurs. La direction est alors annoncée dans l'en-tête.

| Fiche | Adressée à | En attente de |
|---|---|---|
| [mesure-demandee-compteur-de-resolutions](mesure-demandee-compteur-de-resolutions.md) | autonom | le compteur `reflection.resolveAddresses` d'une requête réelle sous ALPHA17 — le seul facteur qui manque pour expliquer, ou écarter, le plancher de ~40 ms |

## Fiches ouvertes

Aucune.

Les onze fiches déposées par palliad et autonom ont été traitées — voir ci-dessous.

Ce n'est pas une invitation à se taire : le dossier existe pour ce que les consommateurs
constatent, et il est fait pour se remplir de nouveau. Deux points explicitement laissés ouverts
attendent d'ailleurs un retour, et sont nommés dans leur fiche : le plancher de ~40 ms, à
re-mesurer chez autonom après ALPHA17, et le champ non déclaré à la mise à jour, non rapporté dans
les en-têtes de champs écartés.

## Fiches répondues

Traitées le 2026-09-04, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA17`**. La réponse est
écrite au bas de chaque fiche : ce qui a été fait, et — quand la lecture des sources l'a établi —
en quoi la cause différait de celle que la fiche supposait. Elles restent ici pour que celui qui les
a écrites puisse vérifier.

| Fiche | Émise par | Réponse |
|---|---|---|
| [resolution-par-requete-et-exceptions-sur-le-chemin-chaud](resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md) | autonom | Corrigée, les trois pistes. 8 à 9 fois moins cher sur le chemin de résolution isolé (1 395–1 670 ns → 173–178 ns). Deux sites levaient, pas un : le second est dans le repli AOT, donc sur VOTRE déploiement. La mémo porte la FORME de la classe, pas les adresses — celles-ci dépendent de l'adresse de base. Le chemin est désormais instrumenté et le rapport tombe à l'arrêt de la JVM. Les ~40 ms n'ont pas été reproduites ici : **à re-mesurer chez vous**. |
| [toute-exception-de-crochet-rend-500](toute-exception-de-crochet-rend-500.md) | palliad | Corrigée — mais pas là où la fiche le pensait : le `switch` vide était du code mort, le 500 venait du `-> 500` de l'étape. Un statut choisi par le consommateur (`ApiException.badRequest(...)`) l'emporte désormais sur celui de l'étape. |
| [crochets-de-suppression-ne-tirent-jamais](crochets-de-suppression-ne-tirent-jamais.md) | palliad | Corrigée, les deux défauts. Le second était plus large que mesuré : les crochets liés à l'entité étaient cassés sur TOUTES les opérations, pas seulement la suppression. |
| [afterget-ne-tire-que-sur-readone](afterget-ne-tire-que-sur-readone.md) | autonom | Corrigée dans le sens de la demande 1. Ce n'était pas un choix de contrat : `READ_ALL` exigeait un `?mode=full` qu'une lecture ordinaire n'envoie pas, ce qui sautait aussi l'injection. L'exemption des lectures internes, que la fiche signalait, est maintenant explicite — elle tenait par accident. |
| [mandatory-teste-la-nullite-et-seulement-a-la-creation](mandatory-teste-la-nullite-et-seulement-a-la-creation.md) | palliad | Corrigée en option déclarative (`MandatoryPolicy.nonBlank`), défaut inchangé. Rectification : la contrainte tourne DÉJÀ à la mise à jour — sur l'entité fusionnée, où le champ n'est jamais nul. Le partage absent / null / vide du tableau de la fiche est implémenté ligne pour ligne. Rupture : `IEntityDefinition.mandatories()`. |
| [operation-request-caller-reconstruit](operation-request-caller-reconstruit.md) | palliad | Corrigée telle que demandée, en trois lignes : la vérification publiait déjà l'appelant réconcilié, personne ne le lisait. Plus large que la fiche : cinq suppliers passent par `request.caller()`. |
| [champs-ecartes-silencieusement](champs-ecartes-silencieusement.md) | palliad | Corrigée : les deux en-têtes, avec les noms de DTO, toujours présents, statut inchangé à 200. Exigence 1 tenue à la création, **partielle à la mise à jour** — le champ non déclaré n'est pas rapporté, et la fiche dit pourquoi. |
| [attempt-authentication-avale-les-exceptions](attempt-authentication-avale-les-exceptions.md) | palliad | Corrigée — mais notre première réponse était trop affirmative, et la fiche porte la **rectification** : le `catch` ne voyait que les erreurs de fourniture, pas la stratégie qui lève elle-même, laquelle est pourtant ce que le titre désigne. Vraie depuis ALPHA19. |
| [apiexception-de-cas-dusage-rendue-en-200-corps-0](apiexception-de-cas-dusage-rendue-en-200-corps-0.md) | autonom | Corrigée. Leur supposition était juste : le binder CAPTURE l'exception au lieu de la lever, `single()` rendait `null`, et le `0` était le code de sortie du workflow servi comme corps. Demande de `4xx` par défaut **non retenue** (500 reste le défaut d'une `ApiException` nue, `ApiException.badRequest(...)` donne le 400), avec la raison écrite dans la fiche. A permis de trouver le même défaut à trois autres endroits, dont un qui persistait des entités NON sécurisées. |
| [authenticator-authorities-decoratif](authenticator-authorities-decoratif.md) | palliad | Corrigée par l'option 1 : la déclaration est honorée en retombée (la stratégie garde la main, une liste vide reste autoritative), et un champ illisible se signale au lieu de rendre un jeton vide. |
| [prefixe-http-non-configurable](prefixe-http-non-configurable.md) | autonom | Corrigée, purement additive : `new JavalinInterface(app, "/api")`. Les cinq critères d'acceptation sont tenus, `completePath` tranché dans votre sens. Un angle mort à connaître : `.interfasse(IClass)` instancie sans argument, le préfixe passe par `.interfasse(ISupplierBuilder)`. |

## Ruptures d'API à la montée en `3.0.0-ALPHA17`

Le dossier n'est pas une file de tickets, mais une rupture se prévient. Trois signatures de
`garganttua-api-commons` changent ; seul le cadre les implémente aujourd'hui, et rien n'a été trouvé
qui les appelle hors de lui — c'est écrit ici pour que vous le vérifiiez plutôt que de le découvrir
à la compilation.

| Élément | Avant | Après |
|---|---|---|
| `IEntityDefinition.mandatories()` (et `IDomain.getMandatoryFields()`) | `List<ObjectAddress>` | `List<Pair<ObjectAddress, MandatoryPolicy>>` |
| `IEntityUpdater.update(...)` | rend l'entité | rend un `EntityWriteOutcome` (entité + champs appliqués/écartés) |
| `IEntityCreator.create(...)` | rend l'entité | rend un `EntityWriteOutcome` |

Tout le reste est additif.
