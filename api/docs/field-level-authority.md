# Field-Level Authority (create & update)

Guard, per field, who may **valorize** it at creation and who may **mutate** it on update — independent of the operation-level authority. The authority rules are shared (`EntityCreator` / `EntityUpdater`):

- No authority required (`create(field)` / `update(field)`, or an empty authority) → the field is allowed.
- `caller.authorities()` is `null` or empty → a *guarded* field is denied.
- Otherwise → `authorities.contains(required)` decides.

There is **no super bypass**: `superTenant` / `superOwner` status grants cross-tenant / cross-owner reach, not the authority to valorize or mutate a guarded field — a super caller must still carry the named authority.

## Update — guard a mutation (silent-skip)

`entity().update(field[, "auth-name"][, ignoreNull])` declares the updatable fields. Only declared fields are ever merged onto the stored entity; for a guarded one the caller must carry the authority. A denied (or undeclared) field is **silently skipped** — the operation continues and the other fields update normally; 403 stays an operation-level concern via `VERIFY_AUTHORITY`.

```java
entity()
    .update("email")                       // freely updatable
    .update("name", "user-update-name")    // mutable only with the authority
```

### Null handling — `ignoreNull` (PUT vs PATCH)

Each declaration carries its own policy for a **null incoming value**:

| Declaration | A `null` in the body means | Semantics |
|---|---|---|
| `update("email")` (default, `ignoreNull = false`) | erase the stored value | PUT — the body describes the full state of every updatable field |
| `update("email", true)` | not supplied → keep the stored value | PATCH — a partial body never wipes what it omits |

```java
entity()
    .update("email")                            // null in the body => email becomes null
    .update("comment", true)                    // null in the body => stored comment survives
    .update("role", "admin-set-role", true)     // authority gate + PATCH semantics
```

The authority gate runs **first**: an ungranted field is never written, so a `null` cannot erase a field the caller may not mutate. Primitive fields can never be null and are therefore unaffected by the policy.

A framework-internal write (startup seeding / `invokeInternal`) does not go through this whitelist at all — it merges every non-null field wholesale, so a partial server-side write never wipes data it did not mean to touch.

### PATCH vs PUT over HTTP

The per-field policy above is the **declared default**. A transport that knows the client sent a *partial* body overrides it for that one request by setting `IOperationRequest.PARTIAL_UPDATE`: every rule then reads as `ignoreNull`, whatever the declaration says.

The Javalin interface routes both verbs to the same `updateOne` operation and sets the marker on PATCH only:

```
PATCH /users/{uuid}  {"name":"Alice"}   -> name updated, email untouched
PUT   /users/{uuid}  {"name":"Alice"}   -> name updated, email erased
                                           (unless declared .update("email", true))
```

**Prefer PATCH for updates.** It matches what clients expect from REST and spares you declaring `ignoreNull` on every field just to make partial bodies safe. Reach for PUT when the body really is the full new state of the updatable fields.

The marker never widens what a caller may write: the authority gate runs first, so an ungranted field is skipped under both verbs. It is server-set only — the extract stage publishes query parameters under `queryParameters`, never as top-level args, so a client cannot forge it. Programmatically it is reachable through the request builder:

```java
domain.request()
    .updateOne(uuid, body)
    .caller(caller)
    .param(IOperationRequest.PARTIAL_UPDATE, true)
    .execute();
```

## Annotations — the declarative form

`@AuthorizeCreate` / `@AuthorizeUpdate` on the entity fields are the exact counterparts of the DSL calls, picked up by the entity annotation scanner:

```java
@Entity
public class Article {
    @AuthorizeCreate @AuthorizeUpdate
    private String title;                       // free, PUT semantics

    @AuthorizeCreate @AuthorizeUpdate(ignoreNull = true)
    private String summary;                     // free, PATCH semantics

    @AuthorizeCreate(authority = "article-publish")
    @AuthorizeUpdate(authority = "article-publish")
    private Boolean published;                  // guarded

    private String internalNote;                // on neither whitelist — never client-writable
}
```

An empty `authority()` (the default) means "no gate", exactly like `update(field)` / `create(field)`.

## Create — authorize valorization (whitelist, strip-on-deny)

`entity().create(field[, "auth-name"])` declares the fields a caller may valorize at creation. Declaring **any** `create(...)` turns creation into a **whitelist**: only the declared fields the caller is authorized for survive on the inbound entity; every other client-supplied field is **stripped** (set to null) before the framework stamps `uuid`/`tenantId`/`ownerId` and persists. With **no** `create(...)` declared, creation is unrestricted (the client body is kept as-is — backward compatible).

```java
entity()
    .create("name")                        // free to valorize
    .create("role", "admin-create-role")   // valorized only with the authority
```

```
POST {name, role, secret}
  caller WITHOUT 'admin-create-role'  -> {name}          (role guarded → stripped; secret undeclared → stripped)
  caller WITH    'admin-create-role'  -> {name, role}    (secret undeclared → stripped)

// no .create(...) declared at all      -> {name, role, secret}  (unrestricted)
```

The strip runs **before** framework stamping, so `uuid`/`tenantId`/`ownerId` are still set by the `ensure*` stages (a client cannot pin them unless explicitly whitelisted — a useful security default). A *mandatory* field that ends up stripped then fails `validateMandatories` (400). Primitive fields cannot hold null and are never stripped.

## Notes

- Both are persistence-layer concerns enforced inside the business stage (`createEntity` / `updateEntity` expressions in `CREATE_ONE.gs` / `UPDATE_ONE.gs`); the entity keeps its declared shape.
- The asymmetry — create defaults to *all allowed*, update defaults to *nothing updatable* — is intentional: creation must persist the body by default, whereas mutation is restrictive by default.
- The null policy is per **declaration**, not per domain: an erasing field and an ignoring field coexist on the same entity.
