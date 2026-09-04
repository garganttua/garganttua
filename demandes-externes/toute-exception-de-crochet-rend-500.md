# Bogue — toute règle métier posée par un crochet rend un HTTP 500

**À l'attention de :** garganttua-api
**Émis par :** palliad (consommateur v3, AOT pur)
**Date :** 2026-08-27
**Version constatée :** garganttua-api `3.0.0-ALPHA15`
**Gravité :** élevée — un refus de saisie est indiscernable d'une panne serveur. La supervision
s'alarme sur une faute de frappe, et un client HTTP correct a le droit de rejouer un 500.

## Symptôme

Un crochet `beforeCreate` / `beforeUpdate` qui refuse une écriture en levant une `ApiException`
produit une réponse **HTTP 500**, avec le bon message dans le corps :

```
POST /patients {"remarks":"x"}
→ 500  {"error":"Le champ « nom » est obligatoire."}
```

Le message est juste, le statut est faux. C'est une saisie invalide, pas une défaillance.

Le contraste est net avec les validations INTERNES du cadre, qui rendent, elles, le bon code :

| Refus | Origine | Statut rendu |
|---|---|---|
| champ obligatoire absent | `mandatory(...)`, cadre | **400** |
| collision d'unicité | `unicity(...)`, cadre | **409** |
| règle métier d'un consommateur | crochet + `ApiException` | **500** |

Autrement dit : les règles du cadre sont de première classe, celles du consommateur ne le sont pas.

## Cause

`OperationResponseCode.fromExceptionCode(ApiException)` ramène TOUTE exception à `SERVER_ERROR`.
Le `lookupswitch` est vide — désassemblé sur le jar publié :

```
public static OperationResponseCode fromExceptionCode(ApiException);
   0: aload_0
   1: invokevirtual #62   // ApiException.getCode:()I
   4: lookupswitch  { // 0
           default: 16
      }
  16: getstatic     #7    // Field SERVER_ERROR:LOperationResponseCode;
  19: astore_1
  20: aload_1
  21: areturn
```

La méthode lit bien `getCode()`, donc l'intention d'un aiguillage par code existe — il n'y a
simplement aucun cas. Et le consommateur ne peut pas non plus choisir un code lui-même : le
constructeur qui en prend un est **`protected`** :

```java
public class ApiException extends CoreException {
    public    ApiException(String);
    public    ApiException(String, Throwable);
    public    ApiException(Throwable);
    protected ApiException(int, String);          // ← inaccessible depuis un consommateur
    protected ApiException(int, String, Throwable);
}
```

Un consommateur peut sous-classer pour atteindre le constructeur protégé, mais cela ne servirait à
rien tant que le `switch` ne distingue aucun code.

## Pourquoi cela coûte, concrètement

1. **La supervision devient inutilisable.** Un 500 est un incident ; une faute de frappe n'en est
   pas un. Sur palliad, quinze domaines portent désormais des règles métier : toute saisie
   incomplète produira une alerte.
2. **Un client a le droit de rejouer un 500.** C'est la sémantique HTTP : 5xx = « réessayez, le
   problème est chez nous ». Un client bien élevé rejouera donc une écriture délibérément refusée.
3. **Le message est la seule information exploitable**, ce qui oblige chaque client à lire le corps
   plutôt que le statut. Côté palliad, il a fallu normaliser l'erreur dans un intercepteur HTTP
   pour que le motif remonte jusqu'à l'écran — un contournement, pas une solution.

## Reproduction

Sur n'importe quel domaine, un crochet libre qui refuse :

```java
public static void refuser(Object entity) throws ApiException {
    throw new ApiException("Refus métier");
}
// ...
domain.entity()
    .beforeCreate(entityHookMethod(MaClasse.class, "refuser"))
        .withParam(0, new HookEntitySupplierBuilder(entityClass))
    .up()
  .up();
```

`POST /mon-domaine {}` → **500** `{"error":"Refus métier"}`.

## Correctif proposé

Deux gestes, indépendants ; le premier suffit.

1. **Donner des cas au `switch`** de `fromExceptionCode` — au minimum un code « erreur du client »
   qui rende `CLIENT_ERROR`.
2. **Exposer une fabrique publique** pour que le consommateur puisse le poser sans sous-classer :

```java
public static ApiException clientError(String message) {
    return new ApiException(CLIENT_ERROR_CODE, message);
}
```

Un défaut à `SERVER_ERROR` reste le bon choix pour une `ApiException` nue : ce qui manque est
seulement la possibilité d'en sortir.

## Note

Ce défaut est proche, mais distinct, de « une exception levée depuis un cas d'usage est avalée et
rend 200 » (relevé palliad du 2026-07). Là, l'exception disparaît ; ici, elle arrive — avec le
mauvais statut.

---

## Réponse de la plateforme — 2026-09-04

**Traitée.** Corrigée sur `main`, à paraître dans `3.0.0-ALPHA17`.

### Une rectification, qui compte pour la suite

Le `lookupswitch` vide que la fiche désassemble est réel, et il est maintenant rempli. Mais **ce
n'est pas lui qui rendait le 500** : `fromExceptionCode` n'était appelée de nulle part. Le grep est
sans appel — la méthode était publique, morte, et son intention (« un aiguillage par code existe »)
n'avait jamais été branchée.

Le 500 venait d'ailleurs, et la fiche l'aurait manqué : le statut d'un échec est écrit **dans le
script d'étape**, pas déduit de l'exception.

```
// CREATE_ONE.gs
entity <- runBeforeCreate(@entity, @0)
! => recordCaughtException(@0, @exception) -> 500     // ← le 500, il est là
```

`Domain.mapWorkflowResult` prenait ce code d'étape tel quel. Remplir le `switch` seul n'aurait donc
**rien changé au comportement observé** — la fiche aurait été close sur un correctif inopérant. Le
mérite du relevé est d'avoir nommé le bon symptôme ; la cause était une étage plus bas.

### Ce qui a été fait

1. **Le `switch` est rempli** (`OperationResponseCode.fromExceptionCode`) — c'était demandé, et la
   méthode est désormais du code vivant.
2. **La fabrique publique demandée existe**, et pas seulement pour le 400 :
   `ApiException.badRequest / unauthorized / forbidden / notFound / notAcceptable / conflict`, plus
   `ApiException.of(code, message)` pour le reste.
3. **Le statut choisi l'emporte sur celui de l'étape.** `Domain` consulte
   `ApiException.hasExplicitStatus()` : quand le refus vient du code d'un consommateur, c'est son
   choix qui est rendu, à la place du `-> 500` de l'étape. C'est ce geste-là, et non le `switch`,
   qui change ce que voit l'appelant.

```java
public static void refuser(Patient p) {
    if (p.getNom() == null || p.getNom().isBlank()) {
        throw ApiException.badRequest("Le champ « nom » est obligatoire.");
    }
}
// POST /patients {} → 400, message inchangé
```

### Le défaut reste `SERVER_ERROR`

Comme la fiche le propose : une `ApiException` nue continue de rendre 500, et un code hérité d'un
échec de socle (réflexion, injection) n'est **pas** un statut — c'est un diagnostic, et il ne
supplante pas l'étage. `hasExplicitStatus()` ne reconnaît que les constantes de statut déclarées.
Autrement dit, aucun consommateur existant ne change de comportement sans l'avoir écrit.

### Sur la remarque finale de la fiche

Le défaut voisin cité — « une exception levée depuis un cas d'usage est avalée et rend 200 » — n'est
pas traité ici et reste ouvert. Si vous le reproduisez sur ALPHA16, une fiche à part serait utile :
celui-ci est un problème de propagation, pas de statut.

**Couvert par :** `OperationResponseCodeTest` (7 tests, `api/commons`).
