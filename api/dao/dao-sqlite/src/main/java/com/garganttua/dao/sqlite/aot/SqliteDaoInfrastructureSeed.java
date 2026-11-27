package com.garganttua.dao.sqlite.aot;

import com.garganttua.core.aot.commons.IAOTInfrastructureSeed;
import com.garganttua.core.aot.commons.IAOTSeedContext;
import com.garganttua.dao.sqlite.SqliteDao;

/**
 * Pre-registers {@code garganttua-api-dao-sqlite}'s framework-public concrete types in the {@code AOTRegistry} on
 * cold-start, so they resolve at runtime in pure-AOT mode (when {@code garganttua-runtime-reflection} is absent and
 * {@code AOTReflectionProvider} is the only provider).
 *
 * <p>
 * Coverage: {@link SqliteDao} — the {@code IDao} users wire via {@code .db(new SqliteDao(...))}. It carries
 * {@code @Reflected} so the AOT processor emits its descriptor; this seed makes it resolvable in the registry. Its
 * collaborators are instantiated directly, never reflectively. The SQLite driver ships its own GraalVM feature
 * ({@code org.sqlite.nativeimage.SqliteJdbcFeature}), which registers the embedded native library and the JNI
 * callbacks of the Java functions the DAO registers.
 * </p>
 *
 * <p>
 * Discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/com.garganttua.core.aot.commons.IAOTInfrastructureSeed}.
 * </p>
 */
public class SqliteDaoInfrastructureSeed implements IAOTInfrastructureSeed {

    @Override
    public void seed(IAOTSeedContext context) {
        context.registerClass(SqliteDao.class);
    }
}
