package com.garganttua.core.cache;

import com.garganttua.core.CoreException;

/** A cache that cannot be created or resolved: unknown implementation, missing codec, unreachable store. */
public class CacheException extends CoreException {

    private static final long serialVersionUID = 1L;

    public CacheException(String message) {
        super(CoreException.CACHE_ERROR, message);
    }

    public CacheException(String message, Throwable cause) {
        super(CoreException.CACHE_ERROR, message, cause);
    }
}
