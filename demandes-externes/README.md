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
| [toute-exception-de-crochet-rend-500](toute-exception-de-crochet-rend-500.md) | palliad | 2026-08-27 | élevée | Une règle métier refusée par un crochet rend 500 : un refus de saisie est indiscernable d'une panne. |
| [crochets-de-suppression-ne-tirent-jamais](crochets-de-suppression-ne-tirent-jamais.md) | palliad | 2026-08-25 | élevée | `beforeDelete`/`afterDelete` libres ne sont jamais appelés — la purge écrite par le consommateur ne tourne pas. |
| [mandatory-teste-la-nullite-et-seulement-a-la-creation](mandatory-teste-la-nullite-et-seulement-a-la-creation.md) | palliad | 2026-08-27 | moyenne | `mandatory` laisse passer la chaîne vide, et ne tient pas à la mise à jour. |
| [operation-request-caller-reconstruit](operation-request-caller-reconstruit.md) | palliad | 2026-08-24 | moyenne | `OperationRequest.caller()` rebâtit l'appelant depuis `X-Tenant-Id` : un contrôle qui a l'air de protéger ne protège rien. |
| [attempt-authentication-avale-les-exceptions](attempt-authentication-avale-les-exceptions.md) | palliad | 2026-08-24 | moyenne | Une stratégie qui lève rend le même 401 qu'un mot de passe faux, sans une ligne de journal. |
| [champs-ecartes-silencieusement](champs-ecartes-silencieusement.md) | palliad | 2026-08-26 | observabilité | Un champ refusé à l'écriture rend 200 sans le dire : l'écran affiche un succès qui n'a rien écrit. |
| [authenticator-authorities-decoratif](authenticator-authorities-decoratif.md) | palliad | 2026-08-24 | malentendu | `.authorities(champ)` sur un authenticator n'est relu nulle part — le DSL décrit un comportement qui n'existe pas. |
| [prefixe-http-non-configurable](prefixe-http-non-configurable.md) | autonom | 2026-09-01 | évolution | Les routes générées se montent à la racine, sans préfixe possible : une application qui partage son serveur tient deux espaces de noms HTTP. |

Rangées par gravité, pas par date : c'est l'ordre dans lequel elles se lisent utilement.
