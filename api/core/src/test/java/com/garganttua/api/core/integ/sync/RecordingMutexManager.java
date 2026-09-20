package com.garganttua.api.core.integ.sync;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.mutex.MutexStrategy;

/**
 * A mutex manager that records what was locked, and can be told to refuse.
 *
 * <p>
 * The point of the tests using it is not that a lock implementation works — core owns that — but
 * that the api asks for the RIGHT key, at the right moments, and nowhere else.
 * </p>
 *
 * <p>
 * <strong>It runs the protected block on ANOTHER thread, deliberately.</strong>
 * {@code InterruptibleLeaseMutex} does so to enforce the lease — and this api makes the lease
 * mandatory — so a fake that ran the block inline would be kinder than every real implementation and
 * would hide anything that does not survive the thread hop. It hid exactly that once: the runtime
 * context is a {@code ScopedValue} and does not cross threads, so every synchronized write failed
 * with "StatementBlock: no runtime context available" while fifteen tests stayed green.
 * </p>
 */
public class RecordingMutexManager implements IMutexManager {

    /** Every key acquired, in order. */
    public final List<String> acquired = new CopyOnWriteArrayList<>();

    /** How deep the lock is held when the wrapped stage runs — 1 means it really ran inside. */
    public final AtomicInteger maxDepth = new AtomicInteger();

    /** The strategy the api handed to acquire(), so a test can check the lease travelled. */
    public volatile MutexStrategy lastStrategy;

    /** When true, acquisition fails the way a contended distributed lock does. */
    public volatile boolean refuseAcquisition;

    /** When true, resolving the name itself fails — a deployment problem, not contention. */
    public volatile boolean unresolvable;

    /** Run inside the critical section, after the key is held — lets a test observe overlap. */
    public volatile Runnable insideSection;

    private final AtomicInteger depth = new AtomicInteger();

    /** Real locks, one per key: the tests about serialization need the key to actually exclude. */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.locks.ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public java.lang.reflect.Type getSuppliedType() {
        return com.garganttua.core.reflection.IClass.getClass(IMutex.class).getType();
    }

    @Override
    public com.garganttua.core.reflection.IClass<IMutex> getSuppliedClass() {
        return com.garganttua.core.reflection.IClass.getClass(IMutex.class);
    }

    @Override
    public com.garganttua.core.reflection.IClass<MutexName> getOwnerContextType() {
        return com.garganttua.core.reflection.IClass.getClass(MutexName.class);
    }

    @Override
    public IMutex mutex(MutexName name) throws MutexException {
        if (unresolvable) {
            throw new MutexException("no factory registered for " + name.type().getCanonicalName());
        }
        return new RecordingMutex(name);
    }

    /** One recorded lock. */
    public class RecordingMutex implements IMutex {

        private final MutexName name;

        RecordingMutex(MutexName name) {
            this.name = name;
        }

        @Override
        public <R> R acquire(ThrowingFunction<R> function) throws MutexException {
            return acquire(function, null);
        }

        @Override
        public <R> R acquire(ThrowingFunction<R> function, MutexStrategy strategy) throws MutexException {
            lastStrategy = strategy;
            if (refuseAcquisition) {
                throw new MutexException("could not take " + name.name() + " in time");
            }
            acquired.add(name.name());
            java.util.concurrent.locks.ReentrantLock lock =
                    locks.computeIfAbsent(name.name(), k -> new java.util.concurrent.locks.ReentrantLock());
            lock.lock();
            int held = depth.incrementAndGet();
            maxDepth.accumulateAndGet(held, Math::max);
            try {
                Runnable observer = insideSection;
                if (observer != null) {
                    observer.run();
                }
                return onAnotherThread(function);
            } finally {
                depth.decrementAndGet();
                lock.unlock();
            }
        }

        /** Runs the block off the calling thread, as a lease-enforcing mutex must. */
        private <R> R onAnotherThread(ThrowingFunction<R> function) throws MutexException {
            java.util.concurrent.FutureTask<R> task = new java.util.concurrent.FutureTask<>(function::execute);
            Thread worker = new Thread(task, "recording-mutex");
            worker.start();
            try {
                return task.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new MutexException(String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MutexException("interrupted");
            } catch (java.util.concurrent.TimeoutException e) {
                throw new MutexException("the protected block did not finish");
            }
        }
    }
}
