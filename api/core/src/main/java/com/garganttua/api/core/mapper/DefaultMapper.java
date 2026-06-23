package com.garganttua.api.core.mapper;

import com.garganttua.core.mapper.IMapper;
import com.garganttua.core.mapper.Mapper;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;

/**
 * Internal default {@link IMapper} for the framework's own entity↔DTO needs.
 *
 * The reflection is whatever the user installed via
 * {@link IClass#setReflection(IReflection)} — the framework does not pick an
 * implementation. The mapper is built lazily on first access, capturing the
 * reflection then current; subsequent reflection changes do not affect the
 * captured mapper.
 */
public class DefaultMapper {

    private static volatile IMapper mapperInstance;

    private DefaultMapper() {
    }

    public static IMapper mapper() {
        IMapper m = mapperInstance;
        if (m == null) {
            synchronized (DefaultMapper.class) {
                m = mapperInstance;
                if (m == null) {
                    m = new Mapper(IClass.getReflection());
                    mapperInstance = m;
                }
            }
        }
        return m;
    }

    public static IReflection reflection() {
        return IClass.getReflection();
    }
}
