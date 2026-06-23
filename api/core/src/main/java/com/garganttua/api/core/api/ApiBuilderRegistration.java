package com.garganttua.api.core.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.security.IApiSecurityBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticationBuilder;
import com.garganttua.api.commons.security.context.IAuthenticationContext;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.BeanStrategy;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.Predefined;
import com.garganttua.core.mapper.IMapper;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;

/**
 * Bean-registration helpers for {@link ApiBuilder}: publishes the built {@link IApi}, each
 * {@link IDomain} (plus the well-known {@code tenantDomain}), the authentication contexts and the
 * default {@link IMapper} into the runtime injection context. Extracted from {@code ApiBuilder} to
 * keep that wide builder under the file-size gate; behaviour is identical.
 */
final class ApiBuilderRegistration {

    private static final Logger log = Logger.getLogger(ApiBuilderRegistration.class);

    private ApiBuilderRegistration() {
    }

    /** Registers the built API, its domains and the authentication contexts as named beans. */
    @SuppressWarnings("unchecked")
    static void registerBuiltObjectInContext(ApiBuilder builder, IInjectionContext context,
            IApi apiContext) {
        log.debug("Registering IApi as bean in InjectionContext");
        String providerName = Predefined.BeanProviders.garganttua.toString();

        BeanReference<IApi> beanRef = new BeanReference<>(
                IClass.getClass(IApi.class),
                Optional.of(BeanStrategy.singleton),
                Optional.of("Api"),
                Set.of());
        context.addBean(providerName, beanRef, apiContext);
        log.debug("IApi successfully registered as bean with 'Api' name");

        registerDomains(context, providerName, (Api) apiContext);
        registerAuthenticationContexts(builder, context, providerName);
    }

    @SuppressWarnings("unchecked")
    private static void registerDomains(IInjectionContext context, String providerName, Api apiContext) {
        for (Map.Entry<String, IDomain<?>> entry : apiContext.getDomains().entrySet()) {
            String domainName = entry.getKey();
            IDomain<?> domainContext = entry.getValue();

            BeanReference<IDomain<?>> domainBeanRef = new BeanReference<>(
                    (IClass<IDomain<?>>) (IClass<?>) IClass.getClass(IDomain.class),
                    Optional.of(BeanStrategy.singleton),
                    Optional.of("domain." + domainName),
                    Set.of());
            context.addBean(providerName, domainBeanRef, domainContext);
            log.debug("IDomain successfully registered as bean with 'domain.{}' name", domainName);

            // Register the tenant domain context with a well-known bean name
            if (domainContext.isTenantEntity()) {
                BeanReference<IDomain<?>> tenantBeanRef = new BeanReference<>(
                        (IClass<IDomain<?>>) (IClass<?>) IClass.getClass(IDomain.class),
                        Optional.of(BeanStrategy.singleton),
                        Optional.of("tenantDomain"),
                        Set.of());
                context.addBean(providerName, tenantBeanRef, domainContext);
                log.info("Tenant domain context registered as bean 'tenantDomain' (domain: {})", domainName);
            }
        }
    }

    private static void registerAuthenticationContexts(ApiBuilder builder, IInjectionContext context,
            String providerName) {
        if (builder.securityBuilder == null) {
            return;
        }
        for (Map.Entry<IClass<?>, IAuthenticationBuilder<IApiSecurityBuilder>> entry
                : builder.securityBuilder.getAuthenticationBuilders().entrySet()) {
            IClass<?> authClass = entry.getKey();
            IAuthenticationContext authContext = entry.getValue().build();
            String beanName = "authentication." + authClass.getSimpleName();

            @SuppressWarnings("unchecked")
            BeanReference<IAuthenticationContext> authBeanRef = new BeanReference<>(
                    (IClass<IAuthenticationContext>) (IClass<?>) IClass.getClass(IAuthenticationContext.class),
                    Optional.of(BeanStrategy.singleton),
                    Optional.of(beanName),
                    Set.of());
            context.addBean(providerName, authBeanRef, authContext);
            log.debug("IAuthenticationContext registered as bean '{}'", beanName);
        }
    }

    /** Registers the default {@link IMapper} as the {@code mapper} bean. */
    static void registerMapperBean(IInjectionContext injectionContext) {
        log.debug("Registering IMapper as bean in InjectionContext");
        String providerName = Predefined.BeanProviders.garganttua.toString();

        BeanReference<IMapper> beanRef = new BeanReference<>(
                IClass.getClass(IMapper.class),
                Optional.of(BeanStrategy.singleton),
                Optional.of("mapper"),
                Set.of());
        injectionContext.addBean(providerName, beanRef, DefaultMapper.mapper());
        log.debug("IMapper successfully registered as bean with 'mapper' name");
    }
}
