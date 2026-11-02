# Le DAO PostgreSQL réécrit toutes les listes d'une entité à chaque écriture, même quand elles n'ont pas changé

**À l'attention de :** garganttua-api (`dao-postgresql`)
**Émis par :** palliad (v3, AOT pur, natif GraalVM, distributions MongoDB et PostgreSQL)
**Date :** 2026-11-02
**Version constatée :** garganttua-api `3.0.0-ALPHA29`
**Gravité :** performance, pas correction. Une écriture qui ne touche qu'un champ simple paie la réécriture complète de
toutes les listes de l'entité, à tous les niveaux. Sur PostgreSQL, c'est l'essentiel de l'écart avec MongoDB sur les
écritures. Cela pèse aussi sur les synchronisations qui réécrivent des milliers d'entités dont les listes ne bougent
presque jamais, et sur le volume de WAL (réplication, sauvegardes).

## Symptôme

**Mesuré** (binaires natifs palliad, même commit, mêmes données, mêmes ressources ; banc complet dans
`palliad/docs/benchmark-mongodb-postgresql.md`) : un `PATCH` qui ne change QUE le nom d'un établissement de santé.
L'entité porte des listes (téléphones, services FHIR, et pour chaque service sept listes de codes).

| | MongoDB | PostgreSQL |
|---|---:|---:|
| `PATCH /healthcare-organizations/{uuid}` `{"name": …}`, médiane sur 20 | 16,4 ms | 29,9 ms (+83 %) |
| Création d'une entité SANS liste (terme de glossaire) | 16,0 ms | 22,7 ms (+42 %) |

L'écart sur l'entité à listes (+13,5 ms) est le double de celui de l'entité sans liste (+6,7 ms).

## Cause

**Lu** dans les sources d'ALPHA29.

1. `PgWriter.upsert` (`api/dao/dao-postgresql/src/main/java/com/garganttua/dao/postgresql/PgWriter.java`, autour de
   la l. 72) écrit la ligne principale, puis appelle `children.write(...)` pour CHAQUE table enfant de l'entité, sans
   condition.
2. `PgWriteChildren.write` (`PgWriteChildren.java`, l. 81-97) supprime toutes les lignes du propriétaire dans la table
   de premier niveau. Les niveaux inférieurs partent avec elle par `ON DELETE CASCADE`. Il réinsère ensuite tous les
   éléments, niveau par niveau.
3. Le commentaire de la classe (l. 25-32) assume ce choix : « Replacing rather than diffing is deliberate […] working
   out which elements moved, changed or disappeared would buy nothing but code ». C'est vrai pour la **justesse**,
   puisque la transaction rend le remplacement atomique. Ça ne l'est pas pour le **coût** : quand rien n'a bougé, la
   réécriture fait des suppressions en cascade, des insertions par lots, la mise à jour des index et des clés
   étrangères, et du WAL, pour un résultat identique.

Sur un établissement de palliad, un `PATCH` du nom supprime et réinsère les téléphones, les services et les sept
listes de chaque service. Une passe de synchronisation de l'annuaire national réécrit ainsi environ 33 000
établissements avec toutes leurs listes, qui sont presque toujours inchangées.

## Attendu

Ne réécrire une table enfant que si la liste qu'elle porte a changé. Deux façons, par ordre de préférence.

1. **Une empreinte par liste, sur la ligne principale.** Une colonne technique par champ collection (par exemple
   `__digest_<champ>`), qui porte l'empreinte du contenu sérialisé de la liste (éléments, ordre et sous-listes compris).
   - À l'upsert, le DAO calcule l'empreinte de la liste reçue et la compare à l'empreinte stockée. Il peut la relire
     dans la même instruction, par une CTE avant l'`INSERT … ON CONFLICT`, ou par un `SELECT … FOR UPDATE` dans la
     transaction de l'écriture.
   - Le DAO ne réécrit que les tables dont l'empreinte diffère.
   - Le contrat de `IDao` ne change pas, et rien ne dépend de l'appelant.
   - Une ligne écrite avant (empreinte absente) est réécrite comme aujourd'hui, et reçoit son empreinte.
2. **Les champs touchés, transmis par le pipeline.** Un `PATCH` sait quels champs il porte : une liste absente du corps
   est celle qui est déjà stockée. Ce serait le plus économe, sans calcul d'empreinte. Mais il faudrait faire passer au
   DAO l'information « champs touchés » (une surcharge de `save`), ce qui change le contrat commun ; et une écriture
   interne qui fusionne les non-nuls (`mergeAllNonNullFields`) devrait la fournir aussi.

Dans les deux cas, une **épreuve** suffirait :
- le `PATCH` d'un champ simple sur une entité à listes n'émet ni `DELETE` ni `INSERT` sur ses tables enfants ;
- une modification réelle d'une liste, y compris d'un élément d'une sous-liste ou de l'ordre des éléments, la réécrit
  toujours.

## Ce que la fiche ne demande pas

- Pas de mise à jour élément par élément (diff fin des listes) : réécrire une liste qui a changé reste le bon choix.
- Rien sur MongoDB, qui remplace le document entier en une seule écriture.
- Rien sur la lecture : relire les tables enfants est un autre sujet (palliad le contourne déjà par `fields=` sur les
  écrans qui n'affichent pas les listes).

## Réponse de la plateforme — 2026-11-02

**Traitée par votre option 1, avec un ajout que nous estimons indispensable : rien ne peut laisser une empreinte
mentir.** Sur `main`, à paraître dans `3.0.0-ALPHA30`. L'option 2 n'est pas retenue : elle change le contrat
commun, et chaque écriture interne devrait la fournir.

### Ce qui est fait

- **Une empreinte par collection de premier niveau**, sur la ligne principale (`_digest__<champ>`, `TEXT`). Elle
  couvre tout le sous-arbre : éléments, ordre, sous-listes à toute profondeur, valeurs de map.
- **Calculée sur les LIGNES, pas sur la liste** (`PgWriteChildren#digest`) : même parcours que l'insertion, mêmes
  positions et clés, mêmes valeurs JDBC (une entité composée compte pour son uuid, comme sa ligne), le parent d'une
  ligne imbriquée désigné par son rang là où l'insertion utilise l'identifiant généré. Deux collections qui
  seraient stockées en mêmes lignes ont donc la même empreinte, par construction. SHA-256 sur un encodage typé
  et préfixé par longueurs : `"ab","c"` n'est pas `"a","bc"`, la chaîne `"1"` n'est pas le nombre 1.
- **À l'écriture** : un `SELECT … FOR UPDATE` lit les empreintes stockées et verrouille la ligne (une écriture
  concurrente de la même entité attend, puis compare avec ce que la première a écrit) ; l'upsert pose les
  nouvelles ; seules les collections dont l'empreinte diffère sont supprimées et réinsérées. Une ligne écrite
  avant (empreinte absente) est réécrite et reçoit la sienne, comme vous le proposiez.

### L'ajout : l'empreinte ne peut pas mentir

Une empreinte stockée n'est vraie que si personne d'autre n'écrit les tables enfants. Or quelqu'un d'autre peut le
faire : un script SQL, une migration, et surtout **une instance restée en ALPHA29 pendant une montée progressive**,
qui réécrit les listes sans rien savoir des empreintes. Une instance ALPHA30 trouverait ensuite une empreinte encore
égale à l'ancienne liste, et ne réécrirait pas : la donnée serait fausse, sans un signal.

Chaque table enfant, à toute profondeur, porte donc un **déclencheur** qui efface l'empreinte du propriétaire à toute
insertion, mise à jour ou suppression (et toutes les empreintes de la table sur un `TRUNCATE`). Les écritures du DAO
lèvent un drapeau de transaction (`set_config('garganttua.dao_writes_lists', 'on', true)`) que le déclencheur
respecte, pour ne pas effacer l'empreinte qu'elles viennent de poser.

Colonnes, fonction et déclencheurs sont créés en `SchemaMode.CREATE`, sous verrou consultatif. **En `VALIDATE`, ils ne
sont pas exigés** : sans eux, chaque écriture réécrit tout, comme avant, et un WARN donne le DDL à appliquer — un
déploiement validé n'est jamais refusé pour leur absence. Un échec à les créer (un rôle sans droit sur les fonctions)
fait de même.

### Épreuve — **MESURÉE** (PostgreSQL 17)

`PgListDigestTest` : le témoin est `xmin`, la transaction qui a écrit chaque ligne enfant.

| Cas | Résultat |
|---|---|
| sauvegarde qui ne change que le nom, entité à listes, sous-listes et map | aucune ligne enfant réécrite, à aucune profondeur |
| un élément d'une liste change | cette liste réécrite, les autres non |
| l'ordre des éléments ; un élément d'une sous-liste ; une valeur de map ; une liste vidée | réécrites, relues justes |
| une ligne insérée par SQL ; une ligne supprimée dans une sous-liste ; un `TRUNCATE` | empreinte effacée, la sauvegarde suivante rétablit la liste |
| une instance « ALPHA29 » qui réécrit une liste sans drapeau ni empreinte | empreinte effacée, la liste d'origine rétablie |
| `VALIDATE` sans le DDL des empreintes | chaque écriture aboutit, tout est réécrit |

**Le gain, mesuré au niveau du DAO** (JVM, PostgreSQL local, entité de 3 téléphones et 8 services à 7 listes,
médiane sur 300 sauvegardes) :

| Sauvegarde | Avant (tout réécrit) | Après |
|---|---:|---:|
| seul le nom change | 12,9 ms | **4,2 ms** |
| toutes les listes changent | 11,5 ms | 12,8 ms (+1,3 ms : empreinte et lecture verrouillée) |

Votre banc HTTP en natif n'a pas été rejoué ici : à mesurer chez vous, sur `PATCH /healthcare-organizations/{uuid}`.

### Côté palliad

Monter en ALPHA30 ; en `SchemaMode.CREATE`, rien à faire. Si vos bases PostgreSQL sont en `VALIDATE`, appliquer le DDL
que le WARN donne au démarrage. Toute écriture directe dans les tables enfants reste possible : elle efface simplement
l'empreinte, et la prochaine écriture par le DAO réécrit la liste.
