package com.garganttua.dao.postgresql.aot;

import com.garganttua.core.aot.commons.IAOTInfrastructureSeed;
import com.garganttua.core.aot.commons.IAOTSeedContext;
import com.garganttua.dao.postgresql.PgDao;

/**
 * Pre-registers {@code garganttua-api-dao-postgresql}'s framework-public concrete types in the
 * {@code AOTRegistry} on cold-start, so they resolve at runtime in pure-AOT mode (when
 * {@code garganttua-runtime-reflection} is absent and {@code AOTReflectionProvider} is the only
 * provider).
 *
 * <p>
 * Coverage: {@link PgDao} — the {@code IDao} users wire via {@code .db(new PgDao(...))}. It carries
 * {@code @Reflected} so the AOT processor emits its descriptor; this seed makes it resolvable in the
 * registry. The mapping collaborators are instantiated directly by {@code PgDao}, never reflectively,
 * and need no entry.
 * </p>
 *
 * <p>
 * <b>Not covered, and not verifiable here:</b> the DTOs this DAO reads and writes are reached through
 * core reflection, so a native image needs their descriptors like any other domain class — and the
 * PostgreSQL JDBC driver and HikariCP carry their own native-image metadata, which no build of this
 * repository exercises.
 * </p>
 *
 * <p>
 * Discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/com.garganttua.core.aot.commons.IAOTInfrastructureSeed}.
 * </p>
 */
public class PostgresDaoInfrastructureSeed implements IAOTInfrastructureSeed {

    @Override
    public void seed(IAOTSeedContext context) {
        context.registerClass(PgDao.class);
    }
}
