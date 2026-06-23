package com.garganttua.api.core.expression;
import com.garganttua.core.reflection.annotations.Reflected;


import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.definition.IDomainKeyDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.Pluralizer;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

import jakarta.annotation.Nullable;

import static com.garganttua.api.core.expression.ExpressionUtils.*;

/**
 * Security expressions for the signing / verification / encode-decode / key-resolution side of the
 * authorization pipeline. Split out of {@link SecurityExpressions} to keep that registry under the
 * file-size gate; the {@code @Expression} methods stay in the same package so they remain discovered
 * by the {@code com.garganttua.api.core.expression} package scan, and behaviour is identical.
 */
@Reflected(queryAllPublicMethods = true)
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals", "PMD.NullAssignment"})
public class SecuritySigningExpressions {

	private SecuritySigningExpressions() {
	}

	@Expression(name = "resolveKeyRealm", description = "Resolves an IKeyRealm for sign/verify. Two modes: (1) supplier — uses the user-provided ISupplierBuilder<IKeyRealm> declared via .key(supplier); (2) persisted — looks up or auto-creates a key entity on the domain declared via .key(domain), scoped by AuthenticatorKeyUsage (oneForAll / oneForTenant / oneForEach). The optional operationRequest argument carries the caller used to scope the realmName in persisted mode.")
	public static IKeyRealm resolveKeyRealm(@Nullable Object domainContext, @Nullable Object operationRequest) {
		return resolveKeyRealmAndSigner(domainContext, operationRequest).realm();
	}

	@Expression(name = "resolveSigningKey", description = "Resolves the KEY OBJECT that SIGNED a given authorization, by reading its signedBy field. Returns Object, not IKeyRealm: the framework does NOT impose its IKeyRealm shape — the key is whatever the user declared. Persisted-key mode (signedBy = ${keyDomain}:${uuid}): returns that EXACT @Key entity (robust to key rotation: the token verifies against the key that actually signed it), and REFUSES it when that key is revoked or expired. Supplier mode (signedBy = realm name, or no signedBy stamped): returns the object the configured .key(supplier) provides. Powers DomainKeySupplier; the user's verify method casts it to their own key type and extracts the verification material however they defined it.")
	public static Object resolveSigningKey(@Nullable Object authzEntity, @Nullable Object domainContext,
			@Nullable Object operationRequest) {
		Object token = unwrapOptional(authzEntity);
		IDomain<?> authzDomain = toDomain(domainContext);
		if (token == null || authzDomain == null) {
			throw new ApiException("resolveSigningKey: authorization entity and domain context are required");
		}
		IOperationRequest req = (unwrapOptional(operationRequest) instanceof IOperationRequest r) ? r : null;
		// Single source of truth: DomainKeySupplier (extends KeySupplier) resolves
		// the EXACT key from signedBy, falling back to KeySupplier's current-key rules.
		return new com.garganttua.api.core.security.key.DomainKeySupplier()
				.resolveKeyForToken(authzDomain, token, req);
	}

	/**
	 * A resolved key realm together with the qualified id of its signer, used to
	 * stamp an authorization's {@code signedBy} field. In persisted-key mode the
	 * signer id is {@code ${keyDomainName}:${keyUuid}} (the @Key entity backing
	 * the realm); in supplier mode there is no key entity, so the realm name is
	 * used instead.
	 */
	private record ResolvedKeyRealm(IKeyRealm realm, String signerId) {
	}

	// The key business rules (scope / autocreate / rotation) live in KeySupplier
	// — single source of truth. The sign path asks it for a materialized signing
	// realm + the qualified signer id to stamp on the token.
	private static ResolvedKeyRealm resolveKeyRealmAndSigner(@Nullable Object domainContext, @Nullable Object operationRequest) {
		return resolveKeyRealmAndSigner(domainContext, operationRequest, null);
	}

	private static ResolvedKeyRealm resolveKeyRealmAndSigner(@Nullable Object domainContext, @Nullable Object operationRequest,
			@Nullable ICaller signingCaller) {
		IDomain<?> authzDomain = toDomain(domainContext);
		IOperationRequest req = (unwrapOptional(operationRequest) instanceof IOperationRequest r) ? r : null;
		com.garganttua.api.core.security.key.KeySupplier.Signing s =
				new com.garganttua.api.core.security.key.KeySupplier().resolveSigning(authzDomain, req, signingCaller);
		return new ResolvedKeyRealm(s.realm(), s.signerId());
	}

	/**
	 * The identity a freshly-minted token's signing key is scoped to: the authenticated
	 * PRINCIPAL, read off the token itself (its {@code owned} + tenantId fields, set by
	 * {@link #createAuthorizationEntity}). This is what {@code oneForEach} keys on, so the
	 * realm is {@code …:caller:<tenant>:<principal-ownerId>} instead of the anonymous
	 * login request's {@code …:caller:anonymous:anonymous}. Returns null when neither
	 * field is populated (then the request caller is used, preserving legacy behaviour).
	 */
	private static @Nullable ICaller signingPrincipalCaller(@Nullable Object token, @Nullable IDomain<?> domainContext) {
		Object entity = unwrapOptional(token);
		if (entity == null || domainContext == null) {
			return null;
		}
		IDomain<?> tokenDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(domainContext);
		if (tokenDomain == null) {
			tokenDomain = domainContext;
		}
		ObjectAddress ownedAddr = tokenDomain.getDomainDefinition() != null
				? tokenDomain.getDomainDefinition().owned() : null;
		ObjectAddress tenantAddr = tokenDomain.getTenantIdFieldAddress();
		String ownerId = ownedAddr != null ? SecurityExpressions.readField(entity, ownedAddr) : null;
		String tenantId = tenantAddr != null ? SecurityExpressions.readField(entity, tenantAddr) : null;
		if (ownerId == null && tenantId == null) {
			return null;
		}
		// Preserve ownerId even when tenantId is null (non-tenant mode): the principal
		// is identified by its (qualified) ownerId.
		return new Caller(tenantId, tenantId, null, ownerId, false, false, null);
	}

	/**
	 * Builds the qualified signer id ({@code ${keyDomainName}:${keyUuid}}) for a
	 * persisted key entity, falling back to the bare key domain name when the
	 * entity carries no uuid.
	 */
	public static String keySignerId(IDomain<?> keyDomain, Object keyEntity, IReflection reflection) {
		ObjectAddress uuidAddr = keyDomain.getEntityDefinition() != null
				? keyDomain.getEntityDefinition().uuid() : null;
		if (uuidAddr != null) {
			Object v = reflection.getFieldValue(keyEntity, uuidAddr.toString());
			if (v != null) {
				return OwnerIds.qualify(keyDomain.getDomainName(), v.toString());
			}
		}
		return keyDomain.getDomainName();
	}

	// (resolvePersistedKeyRealm / buildRealmName / pickUsable moved to KeySupplier — single source of key business rules)

	public static IDomain<?> resolveKeyDomain(IDomain<?> authenticatorDomain,
			com.garganttua.api.commons.definition.IDomainAuthenticatorAuthorizationKeyDefinition keyConfig) {
		if (!(authenticatorDomain instanceof Domain<?> domCtx)) return null;
		IApi apiContext = domCtx.getApiContext();
		if (apiContext == null) return null;
		try {
			IClass<?> entityClass = keyConfig.keyDomain().getEntityClass();
			String domainName = Pluralizer.toPlural(entityClass.getSimpleName().toLowerCase(java.util.Locale.ROOT));
			return apiContext.getDomain(domainName).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static ICaller extractCaller(@Nullable Object operationRequest) {
		Object unwrapped = unwrapOptional(operationRequest);
		if (unwrapped instanceof IOperationRequest req) {
			ICaller caller = req.caller();
			if (caller != null) return caller;
		}
		return Caller.createAnonymousCaller();
	}

	static boolean isExpired(Object value) {
		long now = System.currentTimeMillis();
		if (value instanceof java.util.Date d) return d.getTime() <= now;
		if (value instanceof java.time.Instant i) return i.toEpochMilli() <= now;
		if (value instanceof Long l) return l <= now;
		return false;
	}

	public static void stampIdentityAndTenancy(Object entity, IDomain<?> keyDomain,
			com.garganttua.api.commons.security.annotations.AuthenticatorKeyUsage usage,
			ICaller caller, IReflection reflection) {
		ObjectAddress uuidAddr = keyDomain.getEntityDefinition() != null
				? keyDomain.getEntityDefinition().uuid() : null;
		if (uuidAddr != null) {
			reflection.setFieldValue(entity, uuidAddr.toString(),
					com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().toString());
		}

		ObjectAddress tenantAddr = keyDomain.getTenantIdFieldAddress();
		if (tenantAddr != null) {
			String stampedTenant = usage == com.garganttua.api.commons.security.annotations.AuthenticatorKeyUsage.oneForAll
					? null : caller.tenantId();
			if (stampedTenant != null) {
				reflection.setFieldValue(entity, tenantAddr, stampedTenant);
			}
		}

		ObjectAddress ownedAddr = keyDomain.getDomainDefinition() != null
				? keyDomain.getDomainDefinition().owned() : null;
		if (ownedAddr != null && usage == com.garganttua.api.commons.security.annotations.AuthenticatorKeyUsage.oneForEach
				&& caller.ownerId() != null) {
			reflection.setFieldValue(entity, ownedAddr, caller.ownerId());
		}
	}

	@Expression(name = "signAuthorization", description = "Signs an authorization entity by invoking its getDataToSign method, signing with keyRealm.getKeyForSigning(), and writing the signature back into the configured signature field.")
	public static boolean signAuthorization(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable Object keyRealmObj) {
		if (authzEntity == null || domainContext == null || keyRealmObj == null) {
			throw new ApiException("signAuthorization: entity, domainContext and keyRealm are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.signable()) {
			throw new ApiException("signAuthorization: authorization is not signable on the resolved domain");
		}
		ObjectAddress dataMethod = authzDef.getDataToSignMethod();
		ObjectAddress sigField = authzDef.signatureField();
		if (dataMethod == null) {
			throw new ApiException("signAuthorization: signable authorization has no getDataToSign method configured");
		}
		if (sigField == null) {
			throw new ApiException("signAuthorization: signable authorization has no signature field configured");
		}
		IKeyRealm realm = (IKeyRealm) unwrapOptional(keyRealmObj);
		if (realm == null) {
			throw new ApiException("signAuthorization: keyRealm is null");
		}
		return SecurityExpressionsSupport.doSign(authzEntity, dataMethod, sigField, realm);
	}

	@Expression(name = "signIfSignable", description = "If the resolved authorization is signable, resolves the key realm (supplier or persisted) and signs the entity. The operationRequest argument is forwarded to resolveKeyRealm to scope the persisted-mode realmName by caller. No-op when not signable. Throws when signable but no key is configured.")
	public static boolean signIfSignable(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable Object operationRequest) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("signIfSignable: entity and domainContext are required");
		}
		if (!SecurityAuthorizationExpressions.isAuthorizationSignable(domainContext)) {
			return true;
		}
		// Scope the signing key on the authenticated PRINCIPAL (read off the token),
		// not the anonymous login request — so oneForEach mints one key per principal.
		ICaller principalCaller = signingPrincipalCaller(authzEntity, toDomain(domainContext));
		ResolvedKeyRealm resolved = resolveKeyRealmAndSigner(domainContext, operationRequest, principalCaller);
		boolean signed = signAuthorization(authzEntity, domainContext, resolved.realm());
		stampSignedBy(authzEntity, domainContext, resolved.signerId());
		return signed;
	}

	/**
	 * Records who signed an authorization in its configured {@code signedBy}
	 * field (no-op when no such field is configured). The signer id follows the
	 * {@code ${domainName}:${id}} rule — see {@link #keySignerId}.
	 */
	private static void stampSignedBy(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable String signerId) {
		if (authzEntity == null || signerId == null) {
			return;
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || authzDef.signedBy() == null) {
			return;
		}
		DefaultMapper.reflection().setFieldValue(authzEntity, authzDef.signedBy(), signerId);
	}

	@Expression(name = "verifyIfSignable", description = "If the resolved authorization is signable, resolves the key realm (supplier or persisted) and verifies the entity's signature. The operationRequest argument is forwarded to resolveKeyRealm to scope the persisted-mode realmName by caller. Returns true when not signable or signature valid; false on signature mismatch. Throws when signable but no key is configured.")
	public static boolean verifyIfSignable(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable Object operationRequest) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("verifyIfSignable: entity and domainContext are required");
		}
		if (!SecurityAuthorizationExpressions.isAuthorizationSignable(domainContext)) {
			return true;
		}
		IKeyRealm realm = resolveKeyRealm(domainContext, operationRequest);
		return verifyAuthorizationSignature(authzEntity, domainContext, realm);
	}

	@Expression(name = "verifyTokenSignature", description = "Framework-owned signature verification for a SELF-VERIFYING signable authorization (a token whose own domain is an authenticator). Resolves the EXACT key the token was signed with — by its qualified signedBy, via DomainKeySupplier (rotation-robust; refuses a revoked/expired signing key) — and verifies the entity's signature. Unlike verifyIfSignable (which reads the key config on the given domain), this works on the TOKEN domain where no key config is declared. Returns true when not signable; false on signature mismatch; throws (→ 401) when the token cannot be verified (no qualified signedBy, missing/revoked/expired key).")
	public static boolean verifyTokenSignature(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable Object operationRequest) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("verifyTokenSignature: entity and domainContext are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.signable()) {
			return true; // not signable → nothing to verify
		}
		IDomain<?> authzDomain = toDomain(domainContext);

		// Resolve the EXACT @Key the token was signed with — by its qualified signedBy
		// (the supplier's job; refuses a revoked/expired signing key). Fail-closed when
		// there is no qualified signedBy.
		var signer = new com.garganttua.api.core.security.key.DomainKeySupplier()
				.resolveSignerKey(authzDomain, authzEntity);
		if (signer == null) {
			throw new ApiException("verifyTokenSignature: cannot verify the token signature — it carries no qualified "
					+ "signedBy (${keyDomain}:${uuid}). A self-verifying signable authorization must be signed by a "
					+ "persisted @Key whose reference is stamped on the token.");
		}

		// Read the verification IKey DIRECTLY off the resolved @Key entity — the entity
		// already exposes it, so there is no realm to materialise. Verification is
		// read-only: the private signing material is never touched.
		IDomainKeyDefinition keyDef = signer.keyDomain().getDomainDefinition() != null
				? signer.keyDomain().getDomainDefinition().keyDefinition() : null;
		if (keyDef == null || keyDef.keyForSignatureVerification() == null) {
			throw new ApiException("verifyTokenSignature: key domain '" + signer.keyDomain().getDomainName()
					+ "' declares no @KeyForSignatureVerification field to verify the token against");
		}
		Object keyVal = DefaultMapper.reflection().getFieldValue(
				signer.entity(), keyDef.keyForSignatureVerification().toString());
		if (!(keyVal instanceof IKey verificationKey)) {
			throw new ApiException("verifyTokenSignature: the resolved @Key's verification field is not an IKey — got "
					+ (keyVal == null ? "null" : keyVal.getClass().getName()));
		}
		return verifyEntitySignatureWithKey(authzEntity, authzDef, verificationKey);
	}

	@Expression(name = "verifyAuthorizationSignature", description = "Verifies the signature on an authorization entity by invoking getDataToSign, reading the signature field, and calling keyRealm.getKeyForSignatureVerification().verifySignature. Returns true on valid signature, false on mismatch; throws on misconfiguration.")
	public static boolean verifyAuthorizationSignature(@Nullable Object authzEntity, @Nullable Object domainContext, @Nullable Object keyRealmObj) {
		if (authzEntity == null || domainContext == null || keyRealmObj == null) {
			throw new ApiException("verifyAuthorizationSignature: entity, domainContext and keyRealm are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.signable()) {
			throw new ApiException("verifyAuthorizationSignature: authorization is not signable on the resolved domain");
		}
		IKeyRealm realm = (IKeyRealm) unwrapOptional(keyRealmObj);
		if (realm == null) {
			throw new ApiException("verifyAuthorizationSignature: keyRealm is null");
		}
		IKey key;
		try {
			key = realm.getKeyForSignatureVerification();
		} catch (Exception e) {
			throw new ApiException("verifyAuthorizationSignature failed: " + e.getMessage(), e);
		}
		return verifyEntitySignatureWithKey(authzEntity, authzDef, key);
	}

	/**
	 * Verifies an authorization entity's signature against a given verification key: invokes
	 * getDataToSign, reads the signature field, and calls {@code key.verifySignature}. Returns
	 * false on a signature mismatch or any crypto error (a tampered token is a 401, not a 500);
	 * throws an ApiException only on misconfiguration (no getDataToSign / no signature field /
	 * signature field empty or not a byte[]).
	 */
	private static boolean verifyEntitySignatureWithKey(Object authzEntity, IDomainAuthorizationDefinition authzDef, IKey verificationKey) {
		ObjectAddress dataMethod = authzDef.getDataToSignMethod();
		ObjectAddress sigField = authzDef.signatureField();
		if (dataMethod == null) {
			throw new ApiException("verifyAuthorizationSignature: signable authorization has no getDataToSign method configured");
		}
		if (sigField == null) {
			throw new ApiException("verifyAuthorizationSignature: signable authorization has no signature field configured");
		}
		IReflection reflection = DefaultMapper.reflection();
		byte[] data;
		byte[] signature;
		try {
			data = reflection.invokeMethod(authzEntity, dataMethod.toString(), IClass.getClass(byte[].class));
			if (data == null) {
				throw new ApiException("verifyAuthorizationSignature: getDataToSign returned null");
			}
			Object sigVal = reflection.getFieldValue(authzEntity, sigField.toString());
			if (!(sigVal instanceof byte[] sig)) {
				throw new ApiException("verifyAuthorizationSignature: signature field on authorization entity is empty or not a byte[]");
			}
			signature = sig;
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("verifyAuthorizationSignature failed: " + e.getMessage(), e);
		}
		// Crypto errors during verification (malformed signature bytes, decoding failure,
		// key/algorithm mismatch) map to "signature invalid" (false), not a 500.
		try {
			return verificationKey.verifySignature(signature, data);
		} catch (Exception e) {
			return false;
		}
	}

	// ----- Encode authorization to transport-friendly form (Phase 3) -----

	@Expression(name = "hasEncodeMethod", description = "Returns true when the resolved authorization definition declares an encode method (set via .refreshable().encode(method) on the DSL).")
	public static boolean hasEncodeMethod(@Nullable Object domainContext) {
		Object def = SecurityExpressions.authorizationDefinition(domainContext);
		return def instanceof IDomainAuthorizationDefinition d && d.encodeMethod() != null;
	}

	@Expression(name = "encodeAuthorization", description = "Invokes the user-declared encode method on an authorization entity and returns its result (typically a String for HTTP transport, or a byte[] for binary protocols). Return type is whatever the entity's method returns.")
	public static Object encodeAuthorization(@Nullable Object authzEntity, @Nullable Object domainContext) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("encodeAuthorization: entity and domainContext are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || authzDef.encodeMethod() == null) {
			throw new ApiException("encodeAuthorization: no encode method configured on authorization definition");
		}
		String methodName = authzDef.encodeMethod().toString();
		try {
			IReflection reflection = DefaultMapper.reflection();
			IClass<?> entityClass = IClass.getClass(authzEntity.getClass());
			com.garganttua.core.reflection.IMethod method = reflection.resolveMethod(entityClass, methodName)
					.orElseThrow(() -> new ApiException("encodeAuthorization: method '" + methodName
							+ "' not found on " + authzEntity.getClass().getName()));
			return method.invoke(authzEntity);
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("encodeAuthorization failed: " + e.getMessage(), e);
		}
	}

	@Expression(name = "encodeIfPossible", description = "If the resolved authorization declares an encode method, invokes it and returns the encoded form. Returns null when no encode method is configured (no-op for setups that ship the entity directly).")
	public static @Nullable Object encodeIfPossible(@Nullable Object authzEntity, @Nullable Object domainContext) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("encodeIfPossible: entity and domainContext are required");
		}
		if (!hasEncodeMethod(domainContext)) {
			return null;
		}
		return encodeAuthorization(authzEntity, domainContext);
	}

	@Expression(name = "hasDecodeMethod", description = "Returns true when the resolved authorization definition declares a decode method (.authorization().decode(method) or @AuthorizationDecode).")
	public static boolean hasDecodeMethod(@Nullable Object domainContext) {
		Object def = SecurityExpressions.authorizationDefinition(domainContext);
		return def instanceof IDomainAuthorizationDefinition d && d.decodeMethod() != null;
	}

	@Expression(name = "decodeAuthorizationEntity", description = "Reconstructs an authorization entity from its transport form (e.g. a JWT String / byte[]) using the configured decode method. Returns the value unchanged when it is already a decoded entity (not a String/byte[]) or when no decode method is configured. The decode method is invoked on a fresh entity instance with the raw bytes; it may populate the instance (void/returns this) or be a factory returning a new entity. Used by refresh/verify to accept an encoded token where the entity is expected.")
	public static @Nullable Object decodeAuthorizationEntity(@Nullable Object raw, @Nullable Object domainContext) {
		if (raw == null || domainContext == null) {
			return raw;
		}
		// Already a decoded entity (not a wire form) → nothing to do (Mode B).
		if (!(raw instanceof String) && !(raw instanceof byte[])) {
			return raw;
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || authzDef.decodeMethod() == null) {
			return raw; // no decode configured — leave the raw form for the caller to handle
		}
		IDomain<?> dc = toDomain(domainContext);
		IDomain<?> authzDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(dc);
		IClass<?> entityClass = authzDomain != null ? authzDomain.getEntityClass()
				: (dc != null ? dc.getEntityClass() : null);
		if (entityClass == null) {
			throw new ApiException("decodeAuthorizationEntity: cannot resolve the authorization entity class");
		}
		byte[] rawBytes = raw instanceof String s
				? s.getBytes(java.nio.charset.StandardCharsets.UTF_8) : (byte[]) raw;
		return SecurityExpressionsSupport.invokeDecode(entityClass, authzDef.decodeMethod().toString(), rawBytes);
	}

	@Expression(name = "predecodeRawAuthorization", description = "VERIFY_AUTHORIZATION pre-step (Mode A). When a raw Authorization header is present, no authorization is pre-decoded yet, and the domain declares a decode method, reconstructs the authorization entity from the header value via that decode method and sets it as the decoded authorization (so the rest of verify runs Mode B — signature + validation). No-op when already pre-decoded, no raw header, or no decode method (the scheme/protocol path then handles it).")
	public static boolean predecodeRawAuthorization(@Nullable Object operationRequest, @Nullable Object domainContext) {
		IOperationRequest req = (IOperationRequest) unwrapOptional(operationRequest);
		if (req == null) {
			return false;
		}
		if (req.arg(IOperationRequest.AUTHORIZATION).orElse(null) != null) {
			return false; // Mode B — caller already decoded
		}
		Object rawArg = req.arg(IOperationRequest.RAW_AUTHORIZATION).orElse(null);
		if (rawArg == null) {
			return false; // no raw header
		}
		if (!hasDecodeMethod(domainContext)) {
			return false; // no decode method — leave for the scheme/protocol path
		}
		String raw = AuthorizationProtocolExpressions.rawAuthorizationAsString(rawArg);
		String value = stripAuthorizationScheme(raw);
		Object entity = decodeAuthorizationEntity(value, domainContext);
		req.arg(IOperationRequest.AUTHORIZATION, entity);
		return true;
	}

	/** Strips a leading {@code <scheme> } (e.g. {@code Bearer }) from a raw header, returning the value. */
	private static @Nullable String stripAuthorizationScheme(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.strip();
		int sp = s.indexOf(' ');
		return sp > 0 ? s.substring(sp + 1).strip() : s;
	}
}
