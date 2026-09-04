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

## Fiches ouvertes

Émises par **palliad** et **autonom** (consommateurs v3, AOT purs), constatées sur
`3.0.0-ALPHA15`. La colonne « Émise par » compte : deux consommateurs qui butent sur la même chose
ne disent pas la même chose qu'un seul.

| Fiche | Émise par | Date | Gravité | En une ligne |
|---|---|---|---|---|
| [resolution-par-requete-et-exceptions-sur-le-chemin-chaud](resolution-par-requete-et-exceptions-sur-le-chemin-chaud.md) | autonom | 2026-09-02 | élevée | ~40 ms sur CHAQUE requête authentifiée, passés à lever des exceptions pour dire « ce n'est pas un champ » et à re-résoudre par réflexion ce que l'AOT a déjà calculé. |
| [mandatory-teste-la-nullite-et-seulement-a-la-creation](mandatory-teste-la-nullite-et-seulement-a-la-creation.md) | palliad | 2026-08-27 | moyenne | `mandatory` laisse passer la chaîne vide, et ne tient pas à la mise à jour. |
| [operation-request-caller-reconstruit](operation-request-caller-reconstruit.md) | palliad | 2026-08-24 | moyenne | `OperationRequest.caller()` rebâtit l'appelant depuis `X-Tenant-Id` : un contrôle qui a l'air de protéger ne protège rien. |
| [champs-ecartes-silencieusement](champs-ecartes-silencieusement.md) | palliad | 2026-08-26 | observabilité | Un champ refusé à l'écriture rend 200 sans le dire : l'écran affiche un succès qui n'a rien écrit. |
| [prefixe-http-non-configurable](prefixe-http-non-configurable.md) | autonom | 2026-09-01 | évolution | Les routes générées se montent à la racine, sans préfixe possible : une application qui partage son serveur tient deux espaces de noms HTTP. |

Rangées par gravité, pas par date : c'est l'ordre dans lequel elles se lisent utilement.

## Fiches répondues

Traitées le 2026-09-04, corrigées sur `main`, **à paraître dans `3.0.0-ALPHA17`**. La réponse est
écrite au bas de chaque fiche : ce qui a été fait, et — quand la lecture des sources l'a établi —
en quoi la cause différait de celle que la fiche supposait. Elles restent ici pour que celui qui les
a écrites puisse vérifier.

| Fiche | Émise par | Réponse |
|---|---|---|
| [toute-exception-de-crochet-rend-500](toute-exception-de-crochet-rend-500.md) | palliad | Corrigée — mais pas là où la fiche le pensait : le `switch` vide était du code mort, le 500 venait du `-> 500` de l'étape. Un statut choisi par le consommateur (`ApiException.badRequest(...)`) l'emporte désormais sur celui de l'étape. |
| [crochets-de-suppression-ne-tirent-jamais](crochets-de-suppression-ne-tirent-jamais.md) | palliad | Corrigée, les deux défauts. Le second était plus large que mesuré : les crochets liés à l'entité étaient cassés sur TOUTES les opérations, pas seulement la suppression. |
| [afterget-ne-tire-que-sur-readone](afterget-ne-tire-que-sur-readone.md) | autonom | Corrigée dans le sens de la demande 1. Ce n'était pas un choix de contrat : `READ_ALL` exigeait un `?mode=full` qu'une lecture ordinaire n'envoie pas, ce qui sautait aussi l'injection. L'exemption des lectures internes, que la fiche signalait, est maintenant explicite — elle tenait par accident. |
| [attempt-authentication-avale-les-exceptions](attempt-authentication-avale-les-exceptions.md) | palliad | Corrigée telle que demandée : `warn` nommant la stratégie, la cascade inchangée. |
| [authenticator-authorities-decoratif](authenticator-authorities-decoratif.md) | palliad | Corrigée par l'option 1 : la déclaration est honorée en retombée (la stratégie garde la main, une liste vide reste autoritative), et un champ illisible se signale au lieu de rendre un jeton vide. |
