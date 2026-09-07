package com.garganttua.api.core.perfs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;

/**
 * The authentication strategy of the security performance fixture: a fixed credential check, and a
 * CONFIGURABLE number of granted authorities.
 *
 * <p>
 * The authority count is a parameter because a consumer reported carrying 219 of them and measured
 * that reducing them to 3 changed nothing — a hypothesis worth checking on a harness we control
 * rather than taking on trust.
 * </p>
 *
 * <p>
 * The credential check is a string comparison, deliberately: a real one is a bcrypt hash costing
 * tens of milliseconds by design, which would drown every other figure. What is measured here is
 * the PIPELINE around the check, and the hashing cost is a separate, well-understood constant.
 * </p>
 */
public class PerfAuthentication {

    private final List<String> authorities;

    public PerfAuthentication() {
        this(1);
    }

    public PerfAuthentication(int authorityCount) {
        List<String> granted = new ArrayList<>(authorityCount);
        for (int i = 0; i < authorityCount; i++) {
            granted.add("ROLE_" + i);
        }
        this.authorities = List.copyOf(granted);
    }

    public IAuthentication authenticate(Object principal, byte[] credentials,
            IAuthenticatorDefinition definition) {
        String password = new String(credentials, StandardCharsets.UTF_8);
        boolean success = "valid-password".equals(password);
        return new Authentication(
                success,
                success ? principal : null,
                credentials,
                success ? "perf-token" : null,
                success ? this.authorities : null,
                null, null, false, false,
                true, true, true, true);
    }
}
