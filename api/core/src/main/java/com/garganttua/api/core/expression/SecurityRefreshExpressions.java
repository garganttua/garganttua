package com.garganttua.api.core.expression;

import static com.garganttua.api.core.expression.ExpressionUtils.toDomain;
import static com.garganttua.api.core.expression.ExpressionUtils.unwrapOptional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.SynchronizationPolicy;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.core.security.authentication.KnownPrincipal;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.reflection.binders.IMethodBinder;

import jakarta.annotation.Nullable;

/**
 * What a refresh checks beyond the token it is handed, and the rotation it performs (REFRESH_AUTHORIZATION).
 *
 * <p>
 * The token a client presents is signed at login and never changes: a session closed since, or a refresh
 * already exchanged, still reads as valid in it. So a storable authorization is judged by its STORED record —
 * absent or revoked, the refresh is refused, as {@code checkStoredOnVerify} refuses a request. And the record
 * is marked exchanged (its {@code .refreshable().revokable(field)}) before a new one is issued: the token it
 * stood for keeps working until it expires, but can no longer be refreshed. A client presenting it again is
 * presenting a token someone else already exchanged — the classic sign of a theft — and every live
 * authorization of that owner is revoked.
 * </p>
 *
 * <p>
 * The claim (read, check, mark) runs under the authorization domain's synchronization key when that domain
 * declares one, so that two concurrent refreshes of one token cannot both succeed; without it, the window is
 * the time between a read and a write.
 * </p>
 */
@Reflected
public final class SecurityRefreshExpressions {

	private static final Logger log = Logger.getLogger(SecurityRefreshExpressions.class);

	/** Same words as the verification: a caller is not told which of the two it was. */
	static final String NOT_FOUND_OR_REVOKED = "Authorization not found or revoked";

	static final String REUSED = "This authorization has already been refreshed";

	static final String REFUSED = "The authentication can no longer be refreshed";

	/** The configuration warnings already given, one per domain and kind: a refresh is not a log line. */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private SecurityRefreshExpressions() {
		// Expression function holder
	}

	/**
	 * The authorization a refresh is handed: the request's {@code entity} when the caller put it there
	 * (in-process), else the token of the {@code Authorization} header, scheme stripped — over HTTP, a refresh
	 * presents its token as every request does. Decoded afterwards by {@code decodeAuthorizationEntity}.
	 *
	 * @throws ApiException when the request carries neither
	 */
	@Expression(name = "presentedForRefresh", description = "Refresh: the authorization presented — the request's "
			+ "entity (in-process), else the Authorization header's token without its scheme (HTTP).")
	public static Object presentedForRefresh(@Nullable Object request) {
		if (!(unwrapOptional(request) instanceof IOperationRequest operationRequest)) {
			throw new ApiException("presentedForRefresh: operation request is required");
		}
		Object entity = unwrapOptional(operationRequest.arg("entity").orElse(null));
		if (entity != null) {
			return entity;
		}
		Object raw = operationRequest.arg(IOperationRequest.RAW_AUTHORIZATION).orElse(null);
		String token = SecuritySigningExpressions.stripAuthorizationScheme(
				AuthorizationProtocolExpressions.rawAuthorizationAsString(raw));
		if (token == null || token.isBlank()) {
			throw new ApiException("No authorization to refresh: present it in the Authorization header");
		}
		return token;
	}

	/**
	 * Claims the authorization presented for a refresh.
	 *
	 * @param authzEntity   the presented authorization, decoded and signature-checked
	 * @param domainContext the authenticator domain
	 * @return the presented authorization, its stored record now marked exchanged when storable
	 * @throws ApiException when the record is absent, revoked, past its refresh window, or already exchanged
	 */
	@Expression(name = "claimRefresh", description = "Refresh: judges a storable authorization by its stored "
			+ "record (absent or revoked → refused), refuses one already exchanged and revokes every live "
			+ "authorization of its owner (reuse = theft), else marks it exchanged (rotation) and returns it. "
			+ "Returns the presented authorization unchanged when the domain is not storable.")
	public static Object claimRefresh(@Nullable Object authzEntity, @Nullable Object domainContext) {
		Object presented = unwrapOptional(authzEntity);
		IDomain<?> authenticator = toDomain(domainContext);
		if (presented == null || authenticator == null) {
			throw new ApiException("claimRefresh: authorization and domain are required");
		}
		if (!(SecurityExpressions.authorizationDefinition(authenticator) instanceof IDomainAuthorizationDefinition def)) {
			throw new ApiException("claimRefresh: authorization definition not resolved");
		}
		if (!def.storable()) {
			warnOnce(authenticator, "stateless", "The authorization of '{}' is refreshable but not storable: a "
					+ "refresh cannot see a revocation nor rotate the token — a closed session reopens by "
					+ "refreshing. Declare .storable() for both.");
			return presented;
		}
		IDomain<?> authzDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(authenticator);
		if (authzDomain == null) {
			throw new ApiException("claimRefresh: storable authorization but no authorization domain linked");
		}
		return underLock(authzDomain, presented, () -> claim(authenticator, authzDomain, def, presented));
	}

	/**
	 * The stored record decides only what changes after the login — revoked, exchanged. What the token was
	 * minted with (its owner, its refresh window, its authorities) is signed in the presented form, and a DTO
	 * that does not store every field must not make a refresh fail.
	 */
	private static Object claim(IDomain<?> authenticator, IDomain<?> authzDomain, IDomainAuthorizationDefinition def,
			Object presented) {
		Object stored = SecurityVerificationSupport.serverAuthoritativeAuthorizationStrict(authzDomain, presented)
				.orElseThrow(() -> new ApiException(NOT_FOUND_OR_REVOKED));
		if (isTrue(stored, def.refreshRevoked())) {
			int revoked = revokeLineage(authzDomain, def, presented, stored);
			log.warn("Authorization {} of '{}' was presented for a refresh after it had been exchanged — possible "
					+ "token theft: the {} live authorization(s) of its owner are revoked",
					uuidOf(authzDomain, stored), authzDomain.getDomainName(), revoked);
			throw new ApiException(REUSED);
		}
		if (isTrue(stored, def.revoked())) {
			throw new ApiException(NOT_FOUND_OR_REVOKED);
		}
		if (def.refreshRevoked() == null) {
			warnOnce(authenticator, "rotation", "The authorization of '{}' declares no "
					+ ".refreshable().revokable(field): a refreshed token stays refreshable, and a stolen one "
					+ "can be refreshed alongside the legitimate one without anything noticing.");
			return presented;
		}
		if (DefaultMapper.reflection().getFieldValue(stored, def.refreshRevoked().toString()) == null) {
			// Minted false at login: a stored null means the DTO does not keep the field, and a mark written
			// to it would be dropped on the way to the store.
			warnOnce(authenticator, "rotation-unstored", "The stored authorizations of '{}' do not keep the "
					+ "refresh-revoked field (null where the login wrote false): map it on the DTO, or rotation "
					+ "and reuse detection do nothing.");
		}
		DefaultMapper.reflection().setFieldValue(stored, def.refreshRevoked(), Boolean.TRUE);
		authzDomain.getRepository().save(stored);
		return presented;
	}

	/**
	 * Revokes every live authorization of the owner of the presented token — access and refresh — so that
	 * neither the thief nor the victim keeps a session: the victim logs in again, the thief cannot.
	 *
	 * @return how many were revoked
	 */
	private static int revokeLineage(IDomain<?> authzDomain, IDomainAuthorizationDefinition def, Object presented,
			Object stored) {
		ObjectAddress owned = authzDomain.getDomainDefinition().owned();
		Object owner = owned == null ? null : DefaultMapper.reflection().getFieldValue(presented, owned.toString());
		List<Object> lineage = new ArrayList<>();
		if (owner != null) {
			List<IFilter> filters = new ArrayList<>();
			filters.add(Filter.eq(owned.toString(), owner));
			if (def.revoked() != null) {
				filters.add(Filter.eq(def.revoked().toString(), false));
			}
			IFilter filter = filters.size() == 1 ? filters.get(0) : Filter.and(filters.toArray(new Filter[0]));
			lineage.addAll(authzDomain.getRepository()
					.getEntities(Optional.empty(), Optional.of(filter), Optional.empty()));
		}
		String storedUuid = uuidOf(authzDomain, stored);
		if (lineage.stream().noneMatch(a -> Objects.equals(uuidOf(authzDomain, a), storedUuid))) {
			lineage.add(stored);
		}
		for (Object authorization : lineage) {
			if (def.revoked() != null) {
				DefaultMapper.reflection().setFieldValue(authorization, def.revoked(), Boolean.TRUE);
			}
			DefaultMapper.reflection().setFieldValue(authorization, def.refreshRevoked(), Boolean.TRUE);
			authzDomain.getRepository().save(authorization);
		}
		return lineage.size();
	}

	/**
	 * The authentication a refreshed token is issued from: the one the authenticator's {@code refresh} strategy
	 * re-derives for the principal — the authorities of the moment — or, when no strategy declares one, a copy
	 * of the replaced token's.
	 *
	 * @param principal     the principal, re-read from the authenticator domain and found active
	 * @param authzEntity   the claimed authorization (the stored record when storable)
	 * @param domainContext the authenticator domain
	 * @param request       the operation request, handed to the strategy's suppliers
	 * @return a successful authentication
	 * @throws ApiException when every declared refresh strategy refuses
	 */
	@Expression(name = "refreshAuthentication", description = "Refresh: re-derives the authentication of the "
			+ "principal through the authenticator's .authentication(...).refresh(method) strategies (current "
			+ "authorities; a refusal refuses the refresh), or copies the replaced token's when none declares one.")
	public static IAuthentication refreshAuthentication(@Nullable Object principal, @Nullable Object authzEntity,
			@Nullable Object domainContext, @Nullable Object request) {
		Object known = unwrapOptional(principal);
		IDomain<?> authenticator = toDomain(domainContext);
		IAuthenticatorDefinition authDef = SecurityExpressions.authenticatorContext(authenticator);
		if (known == null || authDef == null) {
			throw new ApiException("refreshAuthentication: principal and authenticator are required");
		}
		List<IMethodBinder<?>> strategies = refreshStrategies(authDef);
		if (strategies.isEmpty()) {
			warnOnce(authenticator, "authorities", "No authentication strategy of '{}' declares "
					+ ".refresh(method): a refreshed token copies the authorities of the one it replaces, so a "
					+ "right withdrawn since the login stays until the next login.");
			return SecurityAuthenticationExpressions.synthAuthFromPrincipal(known, unwrapOptional(authzEntity),
					domainContext);
		}
		if (unwrapOptional(request) instanceof IOperationRequest operationRequest) {
			operationRequest.arg(KnownPrincipal.ARG, new KnownPrincipal(() -> Optional.of(known)));
		}
		SecurityAuthenticationExpressions.prepareAuthContext(unwrapOptional(request), authenticator);
		for (IMethodBinder<?> strategy : strategies) {
			try {
				IAuthentication authentication = SecurityAuthenticationExpressions.runStrategy(strategy);
				if (authentication != null) {
					return authentication;
				}
			} catch (RuntimeException e) {
				log.warn("Refresh strategy {} threw {} — treated as a refusal: {}", strategy.getExecutableReference(),
						e.getClass().getSimpleName(), e.getMessage(), e);
			}
		}
		throw new ApiException(REFUSED);
	}

	/** The request arg carrying the end of the refresh window a refreshed authorization may not exceed. */
	public static final String REFRESH_NOT_AFTER = "refreshNotAfter";

	/**
	 * The end of the presented authorization's refresh window, which its successor inherits: the window is
	 * fixed at login, so that {@code refreshLifeTime} is the longest a session lasts — a refresh does not push
	 * it back.
	 *
	 * @return the window's end, or {@code null} when the authorization declares none
	 */
	@Expression(name = "refreshWindowEnd", description = "Refresh: the end of the presented authorization's "
			+ "refresh window (its .refreshable().expirable(field)), or null — the successor inherits it, so a "
			+ "session lasts at most refreshLifeTime from the login.")
	public static Object refreshWindowEnd(@Nullable Object authzEntity, @Nullable Object domainContext) {
		Object presented = unwrapOptional(authzEntity);
		if (presented == null
				|| !(SecurityExpressions.authorizationDefinition(toDomain(domainContext))
						instanceof IDomainAuthorizationDefinition def)
				|| def.refreshExpiration() == null) {
			return null;
		}
		return DefaultMapper.reflection().getFieldValue(presented, def.refreshExpiration().toString());
	}

	/**
	 * Holds a freshly built authorization to the refresh window its predecessor had, before it is signed.
	 * A no-op outside a refresh (the request carries no {@link #REFRESH_NOT_AFTER}).
	 */
	static void capRefreshWindow(Object authorization, @Nullable Object domainContext, @Nullable Object request) {
		if (!(unwrapOptional(request) instanceof IOperationRequest operationRequest)
				|| !(operationRequest.arg(REFRESH_NOT_AFTER).orElse(null) instanceof java.time.Instant notAfter)
				|| !(SecurityExpressions.authorizationDefinition(toDomain(domainContext))
						instanceof IDomainAuthorizationDefinition def)
				|| def.refreshExpiration() == null) {
			return;
		}
		Object end = DefaultMapper.reflection().getFieldValue(authorization, def.refreshExpiration().toString());
		if (!(end instanceof java.time.Instant issued) || issued.isAfter(notAfter)) {
			DefaultMapper.reflection().setFieldValue(authorization, def.refreshExpiration(), notAfter);
		}
	}

	private static List<IMethodBinder<?>> refreshStrategies(IAuthenticatorDefinition authDef) {
		List<IAuthenticationDefinition> definitions = authDef.authenticationDefinitions();
		if (definitions == null) {
			return List.of();
		}
		return definitions.stream().map(IAuthenticationDefinition::refreshMethodBinder).filter(Objects::nonNull)
				.<IMethodBinder<?>>map(binder -> binder).toList();
	}

	/**
	 * Runs {@code body} under the synchronization key of the authorization record, when its domain declares a
	 * policy. The body touches the repository only — no script — so the mutex may run it on any thread.
	 */
	@SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
			justification = "The claim's own refusal is carried out of the critical section and rethrown as is, "
					+ "so that it keeps its meaning instead of reading as a failure to take the lock.")
	private static Object underLock(IDomain<?> authzDomain, Object presented, Supplier<Object> body) {
		SynchronizationPolicy policy = authzDomain.synchronization().orElse(null);
		if (policy == null) {
			return body.get();
		}
		MutexName key = policy.keyFor(authzDomain.getDomainName(), tenantOf(authzDomain, presented),
				uuidOf(authzDomain, presented));
		IMutex mutex;
		try {
			mutex = policy.manager().mutex(key);
		} catch (RuntimeException e) {
			log.warn("Cannot resolve the synchronization mutex '{}': {}; refreshing WITHOUT it", key, e.getMessage());
			return body.get();
		}
		AtomicReference<RuntimeException> refusal = new AtomicReference<>();
		Object claimed;
		try {
			claimed = mutex.acquire(() -> {
				try {
					return body.get();
				} catch (RuntimeException e) {
					refusal.set(e);
					return null;
				}
			}, policy.strategy());
		} catch (MutexException e) {
			ApiException busy = new ApiException("This authorization is being refreshed by another request");
			busy.initCause(e);
			throw busy;
		}
		if (refusal.get() != null) {
			throw refusal.get();
		}
		return claimed;
	}

	private static boolean isTrue(Object entity, @Nullable ObjectAddress field) {
		return field != null && Boolean.TRUE.equals(DefaultMapper.reflection().getFieldValue(entity, field.toString()));
	}

	private static String uuidOf(IDomain<?> authzDomain, Object entity) {
		return authzDomain.getEntityDefinition() == null ? null
				: SecurityExpressions.readField(entity, authzDomain.getEntityDefinition().uuid());
	}

	private static String tenantOf(IDomain<?> authzDomain, Object entity) {
		return SecurityExpressions.readField(entity, authzDomain.getTenantIdFieldAddress());
	}

	private static void warnOnce(IDomain<?> domain, String kind, String message) {
		if (WARNED.add(domain.getDomainName() + "/" + kind)) {
			log.warn(message, domain.getDomainName());
		}
	}
}
