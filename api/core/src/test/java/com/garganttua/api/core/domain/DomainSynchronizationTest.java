package com.garganttua.api.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.DomainSyncDef;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.service.OperationResponse;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.InterruptibleLeaseMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;

/**
 * Unit tests for {@link DomainSynchronization}: write-operation detection, mutex resolution from the
 * core manager, {@code Type::name} / {@code lockObject} name mapping, and the acquire-wrap behaviour
 * (writes run inside the mutex, reads and unsynchronized domains run the pipeline directly).
 */
class DomainSynchronizationTest {

    private static IOperationRequest requestWith(BusinessOperation op) {
        IOperationRequest request = mock(IOperationRequest.class);
        if (op == null) {
            when(request.arg(IOperationRequest.OPERATION)).thenReturn(Optional.empty());
        } else {
            OperationDefinition def = mock(OperationDefinition.class);
            when(def.getBusinessOperation()).thenReturn(op);
            when(request.arg(IOperationRequest.OPERATION)).thenReturn(Optional.of(def));
        }
        return request;
    }

    @Nested
    @DisplayName("isWriteOperation")
    class IsWriteOperation {

        @Test
        @DisplayName("create/update/deleteOne/deleteAll are writes; reads/unknown are not")
        void classifiesOperations() {
            assertTrue(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.create)));
            assertTrue(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.update)));
            assertTrue(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.deleteOne)));
            assertTrue(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.deleteAll)));
            assertFalse(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.readOne)));
            assertFalse(DomainSynchronization.isWriteOperation(requestWith(BusinessOperation.readAll)));
            assertFalse(DomainSynchronization.isWriteOperation(requestWith(null)));
        }
    }

    @Nested
    @DisplayName("toMutexName")
    class ToMutexName {

        @Test
        @DisplayName("plain lock -> default InterruptibleLeaseMutex, name verbatim")
        void plainLock() {
            MutexName name = DomainSynchronization.toMutexName(new DomainSyncDef("orders", null));
            assertEquals(InterruptibleLeaseMutex.class.getName(), name.type().getName());
            assertEquals("orders", name.name());
        }

        @Test
        @DisplayName("qualified Type::name selects the factory type and bare name")
        void qualifiedLock() {
            String lock = InterruptibleLeaseMutex.class.getCanonicalName() + MutexName.SEPARATOR + "orders";
            MutexName name = DomainSynchronization.toMutexName(new DomainSyncDef(lock, null));
            assertEquals(InterruptibleLeaseMutex.class.getName(), name.type().getName());
            assertEquals("orders", name.name());
        }

        @Test
        @DisplayName("non-blank lockObject narrows the name with a ':' suffix")
        void lockObjectSuffix() {
            MutexName name = DomainSynchronization.toMutexName(new DomainSyncDef("orders", "tenant-1"));
            assertEquals("orders:tenant-1", name.name());
        }
    }

    @Nested
    @DisplayName("resolveWriteMutex")
    class ResolveWriteMutex {

        @Test
        @DisplayName("returns null when no sync, no manager, or a read operation — never touches the manager")
        void nullCases() throws MutexException {
            IMutexManager manager = mock(IMutexManager.class);
            IOperationRequest write = requestWith(BusinessOperation.create);

            assertNull(DomainSynchronization.resolveWriteMutex(manager, null, write, "d"));
            assertNull(DomainSynchronization.resolveWriteMutex(manager,
                    new DomainSyncDef("  ", null), write, "d"));
            assertNull(DomainSynchronization.resolveWriteMutex(null,
                    new DomainSyncDef("lock", null), write, "d"));
            assertNull(DomainSynchronization.resolveWriteMutex(manager,
                    new DomainSyncDef("lock", null), requestWith(BusinessOperation.readOne), "d"));

            verify(manager, never()).mutex(any());
        }

        @Test
        @DisplayName("write op resolves the mutex from the manager with the mapped MutexName")
        void writeResolvesFromManager() throws MutexException {
            IMutexManager manager = mock(IMutexManager.class);
            IMutex mutex = mock(IMutex.class);
            when(manager.mutex(any())).thenReturn(mutex);

            IMutex resolved = DomainSynchronization.resolveWriteMutex(manager,
                    new DomainSyncDef("orders", "t1"), requestWith(BusinessOperation.update), "d");

            assertSame(mutex, resolved);
            verify(manager).mutex(new MutexName(
                    com.garganttua.core.reflection.IClass.getClass(InterruptibleLeaseMutex.class), "orders:t1"));
        }

        @Test
        @DisplayName("an unresolvable lock is swallowed and runs unsynchronized (null)")
        void unresolvableLockDegrades() throws MutexException {
            IMutexManager manager = mock(IMutexManager.class);
            when(manager.mutex(any())).thenThrow(new MutexException("boom"));

            assertNull(DomainSynchronization.resolveWriteMutex(manager,
                    new DomainSyncDef("orders", null), requestWith(BusinessOperation.create), "d"));
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("read operation runs the pipeline directly — mutex is never acquired")
        void readRunsDirectly() throws Exception {
            IMutexManager manager = mock(IMutexManager.class);
            AtomicInteger runs = new AtomicInteger();
            OperationResponse expected = OperationResponse.ok("read");

            OperationResponse actual = DomainSynchronization.execute(manager,
                    new DomainSyncDef("orders", null), requestWith(BusinessOperation.readOne), "d",
                    () -> { runs.incrementAndGet(); return expected; });

            assertSame(expected, actual);
            assertEquals(1, runs.get());
            verify(manager, never()).mutex(any());
        }

        @Test
        @DisplayName("write operation runs the pipeline inside mutex.acquire and returns its result")
        void writeRunsInsideMutex() throws Exception {
            IMutexManager manager = mock(IMutexManager.class);
            IMutex mutex = mock(IMutex.class);
            when(manager.mutex(any())).thenReturn(mutex);
            // Make the mutex actually invoke the protected function, like a real acquire().
            when(mutex.acquire(any())).thenAnswer(inv ->
                    ((IMutex.ThrowingFunction<?>) inv.getArgument(0)).execute());
            AtomicInteger runs = new AtomicInteger();
            OperationResponse expected = OperationResponse.created("saved");

            OperationResponse actual = DomainSynchronization.execute(manager,
                    new DomainSyncDef("orders", null), requestWith(BusinessOperation.create), "d",
                    () -> { runs.incrementAndGet(); return expected; });

            assertSame(expected, actual);
            assertEquals(1, runs.get());
            verify(mutex).acquire(any());
        }

        @Test
        @DisplayName("a failure inside the mutex surfaces as a MutexException wrapping the cause")
        void failureInsideMutexIsWrapped() throws Exception {
            IMutexManager manager = mock(IMutexManager.class);
            IMutex mutex = mock(IMutex.class);
            when(manager.mutex(any())).thenReturn(mutex);
            when(mutex.acquire(any())).thenAnswer(inv ->
                    ((IMutex.ThrowingFunction<?>) inv.getArgument(0)).execute());
            ApiException boom = new ApiException("db down");

            MutexException thrown = assertThrows(MutexException.class, () ->
                    DomainSynchronization.execute(manager, new DomainSyncDef("orders", null),
                            requestWith(BusinessOperation.deleteOne), "d",
                            () -> { throw boom; }));

            assertSame(boom, thrown.getCause());
        }
    }
}
