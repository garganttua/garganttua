package com.garganttua.core.application;

import com.garganttua.core.CoreException;

/**
 * Unchecked exception raised when {@link GarganttuaApplication#run} fails to
 * boot the application's {@code Bootstrap}.
 *
 * <p>Wraps the underlying {@code DslException} (or any boot failure) as a
 * {@link CoreException}, so callers see a uniform framework exception type with
 * an error code rather than a raw {@code RuntimeException}.</p>
 *
 * @since 3.0.0-ALPHA04
 */
public class GarganttuaApplicationException extends CoreException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception wrapping a boot failure.
     *
     * @param message the detail message explaining the boot failure
     * @param cause   the underlying cause
     */
    public GarganttuaApplicationException(String message, Throwable cause) {
        super(CoreException.DSL_ERROR, message, cause);
    }
}
