package com.garganttua.api.core.security.authenticator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.commons.context.dsl.security.IAuthenticationBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticatorBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticatorConnectionBuilder;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorConnection;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.core.dsl.AbstractAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.reflection.fields.FieldResolver;

/**
 * Builds one named connection of an authenticator ({@code .connection(name)}): its login field, scope and strategies.
 * {@code up()} returns to the authenticator, which builds its connections with itself.
 */
@Reflected
@SuppressFBWarnings(value = { "IS2_INCONSISTENT_SYNC" }, justification = "Single-threaded DSL builder used once "
        + "during configuration; doBuild() is synchronized to publish a consistent snapshot.")
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class AuthenticatorConnectionBuilder<E> extends
        AbstractAutomaticLinkedBuilder<IAuthenticatorConnectionBuilder<E>, IAuthenticatorBuilder<E>, IAuthenticatorConnection>
        implements IAuthenticatorConnectionBuilder<E> {

    /** A route segment: lower-case letters, digits and dashes. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * Refuses a connection name that is not a route segment.
     *
     * @throws ApiException when the name is null or not lower-case letters, digits and dashes
     */
    static void requireValidName(String name) throws ApiException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ApiException("Connection name '" + name
                    + "' is not a route segment: use lower-case letters, digits and dashes");
        }
    }

    private final String name;
    private final IClass<?> entityClass;
    private ObjectAddress login;
    private AuthenticatorScope scope;
    private final List<IAuthenticationBuilder<?>> authentications = new ArrayList<>();

    /** @param name a valid route segment — see {@link #requireValidName(String)} */
    AuthenticatorConnectionBuilder(IAuthenticatorBuilder<E> authenticator, String name, IClass<?> entityClass) {
        super(authenticator);
        this.name = name;
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class cannot be null");
    }

    @Override
    public IAuthenticatorConnectionBuilder<E> login(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.login = FieldResolver.fieldByFieldName(this.entityClass, IClass.getReflection(), fieldName,
                IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IAuthenticatorConnectionBuilder<E> login(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        return login(field.getName());
    }

    @Override
    public IAuthenticatorConnectionBuilder<E> login(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address cannot be null");
        this.login = FieldResolver.fieldByAddress(this.entityClass, IClass.getReflection(), fieldAddress,
                IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IAuthenticatorConnectionBuilder<E> scope(AuthenticatorScope scope) {
        this.scope = Objects.requireNonNull(scope, "Scope cannot be null");
        return this;
    }

    @Override
    public IAuthenticatorConnectionBuilder<E> authentication(IAuthenticationBuilder<?> authentication)
            throws ApiException {
        this.authentications.add(Objects.requireNonNull(authentication, "Authentication cannot be null"));
        return this;
    }

    @Override
    protected synchronized IAuthenticatorConnection doBuild() throws ApiException {
        if (this.login == null) {
            throw new ApiException("Connection '" + this.name + "' declares no login field: .login(field)");
        }
        if (this.authentications.isEmpty()) {
            throw new ApiException("Connection '" + this.name + "' declares no authentication strategy: "
                    + ".authentication(...)");
        }
        List<IAuthenticationDefinition> definitions = new ArrayList<>();
        for (IAuthenticationBuilder<?> authentication : this.authentications) {
            definitions.add(authentication.build().getAuthenticationDefinition());
        }
        return new AuthenticatorConnection(this.name, this.login, this.scope, definitions);
    }

    @Override
    protected void doAutoDetection() throws ApiException {
        // No-op: a connection is declared, not detected.
    }
}
