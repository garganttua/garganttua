package com.garganttua.core.script.functions;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.expression.ExpressionException;
import com.garganttua.core.expression.annotations.Expression;
import jakarta.annotation.Nullable;
import com.garganttua.core.reflection.annotations.Reflected;

/** Retry script expression functions. Split out of ScriptFunctions; registered by FQN in ExpressionContextBuilder.FRAMEWORK_FUNCTION_CLASSES. */
@Reflected(queryAllDeclaredMethods = true)
public final class ScriptResilienceFunctions {
    private static final Logger log = Logger.getLogger(ScriptResilienceFunctions.class);
    /** Minimum number of attempts a retry function accepts. */
    private static final int MIN_ATTEMPTS = 1;
    private ScriptResilienceFunctions() {}

    /** Builds an {@link ExpressionException} preserving the cause's stack trace. */
    private static ExpressionException resilienceFailure(String message, Throwable cause) {
        ExpressionException ex = new ExpressionException(message + cause.getMessage());
        ex.initCause(cause);
        return ex;
    }

    /** Sleeps for {@code delayMs}, restoring the interrupt flag and failing fast on interruption. */
    private static void sleepBeforeRetry(String fn, long delayMs) {
        try {
            log.trace("{}: waiting {}ms before next attempt", fn, delayMs);
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw resilienceFailure(fn + ": interrupted while waiting: ", ie);
        }
    }

    /**
     * Retries the execution of a supplier until it succeeds or max attempts are reached.
     *
     * <p>The expression is passed lazily as an ISupplier and will be re-evaluated
     * on each retry attempt.</p>
     *
     * <p>Usage examples in script:</p>
     * <pre>
     * // Re-evaluates on each attempt:
     * result &lt;- retry(3, seconds(10), riskyOperation())
     *
     * // With stored expression (also re-evaluates):
     * expr = riskyOperation()
     * result &lt;- retry(3, seconds(10), @expr)
     * </pre>
     *
     * @param maxAttempts maximum number of attempts (must be >= 1)
     * @param delayMs delay between attempts in milliseconds
     * @param expression the expression to execute (passed lazily as ISupplier)
     * @return the result of the successful execution
     * @throws ExpressionException if all attempts fail
     */
    @Expression(name = "retry", description = "Retries a supplier expression with delay between attempts")
    public static Object retry(int maxAttempts, long delayMs, @Nullable ISupplier<?> expression) {
        log.debug("retry({}, {}ms, ISupplier)", maxAttempts, delayMs);

        if (maxAttempts < MIN_ATTEMPTS) {
            throw new ExpressionException("retry: maxAttempts must be >= 1, got: " + maxAttempts);
        }
        if (delayMs < 0) {
            throw new ExpressionException("retry: delay cannot be negative, got: " + delayMs);
        }
        if (expression == null) {
            return null;
        }

        Throwable lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.trace("retry: attempt {}/{}", attempt, maxAttempts);
                Object result = expression.supply().orElse(null);
                log.debug("retry: succeeded on attempt {}", attempt);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.debug("retry: attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts && delayMs > 0) {
                    sleepBeforeRetry("retry", delayMs);
                }
            }
        }
        throw allAttemptsFailed("retry", maxAttempts, lastException);
    }

    /** Builds the terminal "all attempts failed" exception, appending the last cause if present. */
    private static ExpressionException allAttemptsFailed(String fn, int maxAttempts, Throwable lastException) {
        String msg = fn + ": all " + maxAttempts + " attempts failed";
        if (lastException != null) {
            msg += ": " + lastException.getMessage();
        }
        return new ExpressionException(msg);
    }

    /**
     * Retries with exponential backoff.
     *
     * <p>The delay doubles after each failed attempt, starting from initialDelayMs.</p>
     *
     * <p>The expression is passed lazily as an ISupplier and will be re-evaluated
     * on each retry attempt.</p>
     *
     * @param maxAttempts maximum number of attempts (must be >= 1)
     * @param initialDelayMs initial delay in milliseconds (doubles after each failure)
     * @param maxDelayMs maximum delay cap in milliseconds
     * @param expression the expression to execute (passed lazily as ISupplier)
     * @return the result of the successful execution
     * @throws ExpressionException if all attempts fail
     */
    @Expression(name = "retryWithBackoff", description = "Retries with exponential backoff (delay doubles after each failure)")
    public static Object retryWithBackoff(int maxAttempts, long initialDelayMs, long maxDelayMs,
            @Nullable ISupplier<?> expression) {
        log.debug("retryWithBackoff({}, {}ms initial, {}ms max, ISupplier)",
                maxAttempts, initialDelayMs, maxDelayMs);

        if (maxAttempts < MIN_ATTEMPTS) {
            throw new ExpressionException("retryWithBackoff: maxAttempts must be >= 1, got: " + maxAttempts);
        }
        if (initialDelayMs < 0) {
            throw new ExpressionException("retryWithBackoff: initialDelay cannot be negative, got: " + initialDelayMs);
        }
        if (maxDelayMs < initialDelayMs) {
            throw new ExpressionException("retryWithBackoff: maxDelay must be >= initialDelay");
        }
        if (expression == null) {
            return null;
        }

        Throwable lastException = null;
        long currentDelay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.trace("retryWithBackoff: attempt {}/{}", attempt, maxAttempts);
                Object result = expression.supply().orElse(null);
                log.debug("retryWithBackoff: succeeded on attempt {}", attempt);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.debug("retryWithBackoff: attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts && currentDelay > 0) {
                    sleepBeforeRetry("retryWithBackoff", currentDelay);
                    currentDelay = Math.min(currentDelay * 2, maxDelayMs);
                }
            }
        }
        throw allAttemptsFailed("retryWithBackoff", maxAttempts, lastException);
    }

}
