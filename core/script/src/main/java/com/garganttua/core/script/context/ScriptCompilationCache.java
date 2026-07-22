package com.garganttua.core.script.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.runtime.IRuntime;

/**
 * Parse-and-compile cache shared by every {@link ScriptContext} of one lineage: the compiled
 * {@link IRuntime} of a given script source is built once and reused, instead of being rebuilt on
 * every {@code include()}.
 *
 * <p><b>Why this exists.</b> {@code include("classpath:.../READ_ONE.gs")} used to run the full
 * ANTLR parse + runtime construction on every call, and {@link CompiledScript} deliberately binds a
 * <em>fresh</em> {@link ScriptContext} frame per execution (re-entrancy safety), so the per-frame
 * {@code includedScripts} registry could never amortise that cost. On the api CRUD read path that
 * meant re-compiling every branch script — create, update, delete included — on every single read
 * request.
 *
 * <p><b>What is shared, and why it is safe.</b> Only the compiled {@link IRuntime} is cached. A
 * runtime is immutable and stateless per execution (it opens its own child injection context per
 * call), which is exactly the property {@code WorkflowBuilder.precompile(true)} already relies on to
 * share one {@link com.garganttua.core.script.ICompiledScript} across concurrent requests. The
 * mutable per-run state — {@code lastVariables}, {@code lastOutput}, the included-script registry —
 * stays on the per-call {@link ScriptContext} frame and is never cached.
 *
 * <p><b>Keyed by source, not by path.</b> A modified file yields a different key, so a stale entry
 * can never be served; there is no invalidation to get wrong. The cost is re-reading (not
 * re-parsing) the resource, which is orders of magnitude cheaper than the parse it replaces.
 *
 * <p>Thread-safe. Bounded in practice by the number of distinct script sources an application
 * includes.
 *
 * @since 3.0.0-ALPHA11
 */
public final class ScriptCompilationCache {

    private static final Logger log = Logger.getLogger(ScriptCompilationCache.class);

    private final ConcurrentMap<String, IRuntime<Object[], Object>> runtimes = new ConcurrentHashMap<>();
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();

    /** {@return the runtime already compiled for {@code source}, or {@code null} on a miss} */
    IRuntime<Object[], Object> get(String source) {
        IRuntime<Object[], Object> runtime = this.runtimes.get(source);
        if (runtime == null) {
            this.missCount.increment();
        } else {
            this.hitCount.increment();
        }
        return runtime;
    }

    /** Stores the runtime compiled from {@code source}, keeping the first entry on a race. */
    void put(String source, IRuntime<Object[], Object> runtime) {
        if (runtime == null) {
            return;
        }
        this.runtimes.putIfAbsent(source, runtime);
        log.debug("Script compilation cached, {} distinct source(s) held", this.runtimes.size());
    }

    /** {@return the number of distinct compiled sources held} */
    public int size() {
        return this.runtimes.size();
    }

    /** {@return how many compilations were served from the cache} */
    public long hits() {
        return this.hitCount.sum();
    }

    /** {@return how many compilations had to be performed} */
    public long misses() {
        return this.missCount.sum();
    }

    /** Drops every cached compilation — the next {@code include()} recompiles from source. */
    public void clear() {
        this.runtimes.clear();
        log.debug("Script compilation cache cleared");
    }
}
