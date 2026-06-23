package com.garganttua.core.runtime;

import com.garganttua.core.SuppressFBWarnings;
import com.garganttua.core.reflection.IClass;

/**
 * Declares that a fallback handles a specific exception type originating from a
 * given step of a given runtime.
 *
 * @param exception   the exception type to handle
 * @param runtimeName the runtime the exception must originate from
 * @param fromStep    the step the exception must originate from
 */
// NM_CLASS_NOT_EXCEPTION: this is a value record describing an exception-handling rule, not an exception type itself.
@SuppressFBWarnings(value = "NM_CLASS_NOT_EXCEPTION",
        justification = "Value record describing an on-exception handler, intentionally not a Throwable subtype.")
public record RuntimeStepOnException(IClass<? extends Throwable> exception, String runtimeName, String fromStep) implements IRuntimeStepOnException {
}
