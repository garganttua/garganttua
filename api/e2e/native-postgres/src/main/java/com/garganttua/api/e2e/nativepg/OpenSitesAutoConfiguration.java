package com.garganttua.api.e2e.nativepg;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.core.reflection.IClass;

/**
 * Opens the {@code sites} domain to anonymous create and read: the proof is about storage, not about
 * tokens, and a domain is closed by default. Discovered through {@code META-INF/services}, like every
 * auto-configuration — which the native image must also honour.
 */
public final class OpenSitesAutoConfiguration implements IApiAutoConfiguration {

	@Override
	public int order() {
		return 50;
	}

	@Override
	public void apply(AutoConfigurationContext context) throws ApiException {
		context.apiBuilder()
				.domain(IClass.getClass(Site.class))
					.security()
						.creationAccess(Access.anonymous)
						.readAllAccess(Access.anonymous)
						.readOneAccess(Access.anonymous)
					.up()
				.up();
	}
}
