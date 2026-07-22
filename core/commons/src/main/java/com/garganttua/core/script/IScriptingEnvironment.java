package com.garganttua.core.script;

/**
 * Factory exposed by {@code ScriptsBuilder} for creating fresh, fully-wired
 * {@link IScript} instances on demand.
 *
 * <p>Use this for transient script execution where the script's lifecycle is
 * short (load → compile → execute → discard) — typically inside an orchestrator
 * like {@code Workflow}, a REPL, or a CLI. The environment encapsulates the
 * dependencies that every script needs (expression context, runtimes builder
 * factory, class-loader manager) so callers no longer need to wire them by
 * hand via {@code new ScriptContext(...)}.
 *
 * <p>Each {@link #newScript()} call returns an independent {@link IScript} — no
 * state is shared between scripts produced by the same environment.
 *
 * @since 2.0.0-ALPHA02
 */
public interface IScriptingEnvironment {

    /**
     * @return a brand new {@link IScript} instance wired with this
     *         environment's expression context, runtimes builder factory, and
     *         class-loader manager.
     */
    IScript newScript();

    /**
     * Parse + compile {@code source} once and return a thread-safe
     * {@link ICompiledScript} that can be executed concurrently many times
     * without re-parsing or re-building the underlying runtime.
     *
     * <p>Use this for hot-path scripts (per-request handlers, batch loops,
     * pre-compiled workflows). For one-shot scripts or where you need to
     * mutate variables between calls, use {@link #newScript()} instead.
     *
     * @param source         the .gs source code
     * @param presetVariables variables baked into every execution (immutable
     *                        once the compile completes); pass {@code null}
     *                        or an empty map if none
     * @return immutable handle ready to {@code execute(args)} repeatedly
     * @throws ScriptException if parsing or runtime construction fails
     */
    ICompiledScript precompile(String source, java.util.Map<String, Object> presetVariables)
            throws ScriptException;

    /**
     * Resolves and compiles the script at {@code path} ahead of time, so the first
     * {@code include("<path>")} at runtime is served from the environment's compilation cache
     * instead of parsing the script on a request thread.
     *
     * <p>Callers that know their script set statically — a workflow knows every file-backed script
     * of its stages at build time — should warm it up rather than let the first request pay for the
     * parse. This matters most under GraalVM native, where no JIT ever amortises that parse.
     *
     * <p>Best-effort by contract: a path that cannot be resolved is <em>not</em> an error here. It
     * fails at {@code include()} time exactly as it did before, so a warm-up can never turn a
     * runtime problem into a build failure.
     *
     * @param path the {@code include()} path, filesystem or {@code classpath:} prefixed
     * @return {@code true} when the script was compiled into the cache
     */
    default boolean warmUpInclude(String path) {
        return false;
    }
}
