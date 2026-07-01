package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.BeanStrategy;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.Predefined;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexManager;
import com.garganttua.core.mutex.MutexStrategy;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteSyncDef;

/**
 * Verifies {@link RouteMessageProcessor#resolveMutex(RouteDef)} across the synchronization forms: a
 * {@code lockBean} reference resolves the {@link IMutex} bean from the injection context (the object /
 * supplier / bean DSL forms), a {@code lock} name resolves through the core {@link IMutexManager}, and
 * every unresolvable case degrades to {@code null} so the route runs unsynchronized.
 */
@DisplayName("RouteMessageProcessor mutex resolution")
class RouteMessageProcessorMutexTest {

	private static final String PROVIDER = Predefined.BeanProviders.garganttua.toString();

	private IInjectionContext context;

	@BeforeEach
	void setUp() throws Exception {
		IReflectionBuilder reflectionBuilder = ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.withScanner(new ReflectionsAnnotationScanner());
		IClass.setReflection(reflectionBuilder.build());
		IInjectionContextBuilder builder = InjectionContext.builder()
				.provide(reflectionBuilder)
				.autoDetect(false);
		this.context = builder.build();
		this.context.onInit();
		this.context.onStart();
	}

	@AfterEach
	void tearDown() {
		IClass.setReflection(null);
	}

	private void registerMutexBean(String name, IMutex mutex) throws Exception {
		BeanReference<IMutex> reference = new BeanReference<>(
				IClass.getClass(IMutex.class), Optional.of(BeanStrategy.singleton), Optional.of(name), Set.of());
		this.context.addBean(PROVIDER, reference, mutex);
	}

	private RouteMessageProcessor processor(IMutexManager mutexManager, IInjectionContext ctx) {
		return new RouteMessageProcessor(new RouteObserver(new ObservableRegistry()),
				new ArrayList<>(), mutexManager, ctx);
	}

	private static RouteDef routeWith(RouteSyncDef sync) {
		return new RouteDef("route-1", "in", List.of(), List.of(), null, sync);
	}

	@Test
	@DisplayName("a lockBean reference resolves the exact IMutex bean from the injection context")
	void resolvesBeanMutexInstance() throws Exception {
		CountingMutex mutex = new CountingMutex();
		registerMutexBean("mutex:route:route-1:1", mutex);
		RouteMessageProcessor processor = processor(null, context);

		IMutex resolved = processor.resolveMutex(routeWith(new RouteSyncDef(null, null, "mutex:route:route-1:1")));

		assertSame(mutex, resolved, "the resolved mutex is the very instance registered as the bean");
	}

	@Test
	@DisplayName("a #name bean reference resolves by its name segment")
	void resolvesBeanByNameSegment() throws Exception {
		CountingMutex mutex = new CountingMutex();
		registerMutexBean("sharedMutex", mutex);
		RouteMessageProcessor processor = processor(null, context);

		IMutex resolved = processor.resolveMutex(routeWith(new RouteSyncDef(null, null, "#sharedMutex")));

		assertSame(mutex, resolved);
	}

	@Test
	@DisplayName("a lock name resolves through the core mutex manager")
	void resolvesNameThroughMutexManager() {
		RouteMessageProcessor processor = processor(new MutexManager(), context);

		IMutex resolved = processor.resolveMutex(routeWith(new RouteSyncDef("orders-lock", null)));

		assertNotNull(resolved, "a plain lock name resolves to a default in-JVM mutex");
	}

	@Test
	@DisplayName("no synchronization declared → null (route runs unsynchronized)")
	void nullWhenNoSync() {
		assertNull(processor(new MutexManager(), context).resolveMutex(routeWith(null)));
	}

	@Test
	@DisplayName("bean reference but no injection context → null (graceful degradation)")
	void nullWhenNoContext() {
		RouteMessageProcessor processor = processor(new MutexManager(), null);
		assertNull(processor.resolveMutex(routeWith(new RouteSyncDef(null, null, "sharedMutex"))));
	}

	@Test
	@DisplayName("bean reference to an absent bean → null (graceful degradation)")
	void nullWhenBeanAbsent() {
		RouteMessageProcessor processor = processor(null, context);
		assertNull(processor.resolveMutex(routeWith(new RouteSyncDef(null, null, "does-not-exist"))));
	}

	/** Minimal {@link IMutex} test double that records acquisitions and runs the protected function. */
	static final class CountingMutex implements IMutex {
		final AtomicInteger acquisitions = new AtomicInteger();

		@Override
		public <R> R acquire(ThrowingFunction<R> function) throws MutexException {
			this.acquisitions.incrementAndGet();
			return function.execute();
		}

		@Override
		public <R> R acquire(ThrowingFunction<R> function, MutexStrategy strategy) throws MutexException {
			return acquire(function);
		}
	}
}
