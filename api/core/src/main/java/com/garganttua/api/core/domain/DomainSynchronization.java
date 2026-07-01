package com.garganttua.api.core.domain;

import java.util.Set;

import com.garganttua.api.commons.context.DomainSyncDef;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.service.OperationResponse;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.InterruptibleLeaseMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;

/**
 * Write-synchronization support for {@link Domain}: resolves the per-domain mutex from the core
 * {@link IMutexManager} for write operations (create / update / delete) and runs the domain pipeline
 * inside {@code mutex.acquire(...)}. Reads and unsynchronized domains run the pipeline directly.
 * Extracted from {@code Domain} to keep that wide context under the file-size gate; mirrors the
 * events {@code RouteMessageProcessor} resolve/wrap logic.
 */
final class DomainSynchronization {

    private static final Logger log = Logger.getLogger(DomainSynchronization.class);

    /** Operation labels (from {@code DomainInvocationDiagnostics.resolveOperationLabel}) that mutate. */
    private static final Set<String> WRITE_OPS = Set.of("create", "update", "deleteOne", "deleteAll");

    /** The domain pipeline execution wrapped by the mutex; may throw before the response is mapped. */
    @FunctionalInterface
    interface PipelineExecution {
        OperationResponse run() throws Exception;
    }

    private DomainSynchronization() {
    }

    /**
     * Runs {@code pipeline} inside the domain's synchronization mutex when the operation is a write and
     * a mutex is resolvable; otherwise runs it directly. A failure inside the mutex is rewrapped as a
     * {@link MutexException} so the caller's existing catch maps it (unwrapping the cause).
     */
    static OperationResponse execute(IMutexManager manager, DomainSyncDef sync,
            IOperationRequest request, String domainName, PipelineExecution pipeline) throws Exception {
        IMutex mutex = resolveWriteMutex(manager, sync, request, domainName);
        if (mutex == null) {
            return pipeline.run();
        }
        return mutex.acquire(() -> {
            try {
                return pipeline.run();
            } catch (MutexException e) {
                throw e;
            } catch (Exception e) {
                throw new MutexException(
                        "Synchronized operation on domain '" + domainName + "' failed: " + e.getMessage(), e);
            }
        });
    }

    /** True when the request's business operation mutates state (create / update / delete). */
    static boolean isWriteOperation(IOperationRequest request) {
        return WRITE_OPS.contains(DomainInvocationDiagnostics.resolveOperationLabel(request));
    }

    /**
     * Resolves the write mutex, or {@code null} when the domain declares no synchronization, no mutex
     * manager is wired, the operation is a read, or the lock is unresolvable (logged, runs unsynchronized).
     */
    static IMutex resolveWriteMutex(IMutexManager manager, DomainSyncDef sync,
            IOperationRequest request, String domainName) {
        if (sync == null || sync.lock() == null || sync.lock().isBlank() || manager == null) {
            return null;
        }
        if (!isWriteOperation(request)) {
            return null;
        }
        try {
            return manager.mutex(toMutexName(sync));
        } catch (RuntimeException e) {
            log.warn("Domain {}: cannot resolve synchronization mutex '{}': {}; running without "
                    + "synchronization", domainName, sync.lock(), e.getMessage());
            return null;
        }
    }

    /**
     * Maps {@code synchronization(lock, lockObject)} to a {@link MutexName}. A qualified {@code lock}
     * ({@code Type::name}) selects a registered mutex factory type; a plain {@code lock} uses the
     * default {@link InterruptibleLeaseMutex}. A non-blank {@code lockObject} narrows the name.
     */
    static MutexName toMutexName(DomainSyncDef sync) {
        IClass<? extends IMutex> type;
        String name;
        if (sync.lock().contains(MutexName.SEPARATOR)) {
            MutexName qualified = MutexName.fromString(sync.lock());
            type = qualified.type();
            name = qualified.name();
        } else {
            type = IClass.getClass(InterruptibleLeaseMutex.class);
            name = sync.lock();
        }
        if (sync.lockObject() != null && !sync.lockObject().isBlank()) {
            name = name + ":" + sync.lockObject();
        }
        return new MutexName(type, name);
    }
}
