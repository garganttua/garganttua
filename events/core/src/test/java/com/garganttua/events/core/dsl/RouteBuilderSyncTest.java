package com.garganttua.events.core.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexStrategy;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.dsl.IContextBuilder;

/**
 * Verifies the {@link RouteBuilder} synchronization DSL: the name-based form and the three
 * mutex-supplying forms (fixed instance, supplier builder, bean reference), what each stores on the
 * built {@link RouteDef}'s {@code RouteSyncDef}, and the {@link EventsBuilder}-side bean registration
 * they trigger.
 */
@DisplayName("RouteBuilder synchronization DSL")
class RouteBuilderSyncTest {

	@BeforeEach
	void setUp() {
		IClass.setReflection(ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.withScanner(new ReflectionsAnnotationScanner())
				.build());
	}

	@AfterEach
	void tearDown() {
		IClass.setReflection(null);
	}

	private static RouteBuilder route(EventsBuilder events, String uuid) {
		IContextBuilder ctx = events.context("tenant", "cluster");
		return (RouteBuilder) ctx.route(uuid, "in").to("out");
	}

	@Nested
	@DisplayName("name-based synchronization (unchanged, back-compatible)")
	class NameBased {

		@Test
		@DisplayName("stores lock + lockObject and no bean; registers nothing")
		void storesNameAndLockObject() throws DslException {
			EventsBuilder events = (EventsBuilder) EventsBuilder.builder();
			RouteDef def = route(events, "route-name").synchronization("orders-lock", "tenant-1").build();

			assertEquals("orders-lock", def.synchronization().lock());
			assertEquals("tenant-1", def.synchronization().lockObject());
			assertNull(def.synchronization().lockBean(),
					"a name-based lock carries no bean reference");
			assertEquals(0, events.mutexInstanceCount());
			assertEquals(0, events.mutexSupplierCount());
		}
	}

	@Nested
	@DisplayName("mutex-supplying synchronization forms")
	class MutexSupplying {

		@Test
		@DisplayName("synchronization(IMutex): registers a mutex instance and stores its generated bean")
		void fixedInstanceRegistersBean() throws DslException {
			EventsBuilder events = (EventsBuilder) EventsBuilder.builder();
			RouteDef def = route(events, "route-obj")
					.synchronization(new CountingMutex(), "ignored").build();

			assertNull(def.synchronization().lock(), "the object form uses no name-based lock");
			assertNotNull(def.synchronization().lockBean(), "the generated bean name is stored");
			assertTrue(def.synchronization().lockBean().startsWith("mutex:route:route-obj"),
					"the generated bean name is route-scoped: " + def.synchronization().lockBean());
			assertEquals(1, events.mutexInstanceCount());
			assertEquals(0, events.mutexSupplierCount());
		}

		@Test
		@DisplayName("synchronization(ISupplierBuilder): registers a supplier and stores its generated bean")
		void supplierBuilderRegistersBean() throws DslException {
			EventsBuilder events = (EventsBuilder) EventsBuilder.builder();
			ISupplierBuilder<IMutex, ISupplier<IMutex>> supplier =
					new FixedSupplierBuilder<>(new CountingMutex(), IClass.getClass(IMutex.class));
			RouteDef def = route(events, "route-sup").synchronization(supplier, null).build();

			assertNull(def.synchronization().lock());
			assertNotNull(def.synchronization().lockBean());
			assertEquals(0, events.mutexInstanceCount());
			assertEquals(1, events.mutexSupplierCount());
		}

		@Test
		@DisplayName("synchronizationBean(ref): stores the reference verbatim, registers nothing")
		void beanReferenceStoresReference() throws DslException {
			EventsBuilder events = (EventsBuilder) EventsBuilder.builder();
			RouteDef def = route(events, "route-bean")
					.synchronizationBean("#sharedMutex", "ignored").build();

			assertNull(def.synchronization().lock());
			assertEquals("#sharedMutex", def.synchronization().lockBean(),
					"the caller's bean reference is stored as-is (bean registered elsewhere)");
			assertEquals(0, events.mutexInstanceCount());
			assertEquals(0, events.mutexSupplierCount());
		}

		@Test
		@DisplayName("each object form gets a distinct generated bean name")
		void distinctGeneratedNames() throws DslException {
			EventsBuilder events = (EventsBuilder) EventsBuilder.builder();
			String a = route(events, "r1").synchronization(new CountingMutex(), null).build()
					.synchronization().lockBean();
			String b = route(events, "r2").synchronization(new CountingMutex(), null).build()
					.synchronization().lockBean();

			assertEquals(2, events.mutexInstanceCount());
			org.junit.jupiter.api.Assertions.assertNotEquals(a, b, "generated bean names must be unique");
		}

		@Test
		@DisplayName("a mutex form on a route built outside the EventsBuilder chain fails fast")
		void requiresEventsBuilderChain() {
			RouteBuilder standalone = new RouteBuilder("orphan", "in");
			assertThrows(DslException.class,
					() -> standalone.synchronization(new CountingMutex(), null));
		}
	}

	/** Minimal {@link IMutex} test double that just runs the protected function. */
	static final class CountingMutex implements IMutex {
		@Override
		public <R> R acquire(ThrowingFunction<R> function) throws MutexException {
			return function.execute();
		}

		@Override
		public <R> R acquire(ThrowingFunction<R> function, MutexStrategy strategy) throws MutexException {
			return function.execute();
		}
	}
}
