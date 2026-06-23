package com.garganttua.core.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.IObservable;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservabilityEmitter;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;

/**
 * Default {@link IMapper} implementation: maps a source object onto a destination
 * type by resolving annotation-driven and convention-based {@link MappingRule}s,
 * caching the pre-computed {@link IMappingRuleExecutor}s per source/destination pair.
 *
 * <p>Thread-safe: configurations are cached in a {@link ConcurrentHashMap} and
 * listeners/observers use copy-on-write collections. Per-call cycle detection uses
 * an identity set bound through {@link IMappingRecursion} rather than a
 * {@code ThreadLocal}. Mapping activity is exposed via the {@link IObservable}
 * contract under the {@code mapper:<src>-><dst>} source.
 */
// AvoidDuplicateLiterals: the repeated literal is the "unchecked" @SuppressWarnings value on
// generic casts, not a domain string worth hoisting to a constant.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class Mapper implements IMapper, IObservable {
    private static final Logger log = Logger.getLogger(Mapper.class);

	protected final Map<MappingKey, CachedMappingConfiguration> mappingConfigurations = new ConcurrentHashMap<>();

	private final MapperConfiguration configuration = new MapperConfiguration();
	private final IReflection reflection;
	private final MappingRules mappingRules;
	private final MappingListeners listeners = new MappingListeners();
	private final MapperMetrics metrics = new MapperMetrics();
	private final ObservableRegistry observers = new ObservableRegistry();
	private final StrictCoverageValidator strictCoverageValidator;

	/**
	 * Creates a mapper backed by the given reflection facade.
	 *
	 * @param reflection the reflection provider used to resolve classes, fields and methods
	 * @throws NullPointerException if {@code reflection} is {@code null}
	 */
	public Mapper(IReflection reflection) {
		this.reflection = Objects.requireNonNull(reflection, "IReflection implementation cannot be null");
		this.mappingRules = new MappingRules(reflection);
		this.strictCoverageValidator = new StrictCoverageValidator(reflection);
	}

	private static record MappingKey(IClass<?> source, IClass<?> destination) {
		private MappingKey {
			Objects.requireNonNull(source, "Source class cannot be null");
			Objects.requireNonNull(destination, "Destination class cannot be null");
		}
	}

	@Override
	public <destination> destination map(Object source, destination destination) throws MapperException {
		if (destination == null)
			throw new MapperException("destination cannot be null");
		return this.map(source, (IClass<destination>) this.reflection.getClass(destination.getClass()), destination);
	}

	@Override
	public <destination> destination map(Object source, IClass<destination> destinationClass) throws MapperException {
		if (destinationClass == null)
			throw new MapperException("destination class cannot be null");
		return this.map(source, destinationClass, null);
	}

	@Override
	public void addObserver(IObserver<ObservableEvent> observer) {
		this.observers.addObserver(observer);
	}

	@Override
	public void removeObserver(IObserver<ObservableEvent> observer) {
		this.observers.removeObserver(observer);
	}

	/**
	 * Core mapping entry point. Resolves the cached configuration for the
	 * {@code source}/{@code destinationClass} pair, applies its executors and
	 * returns the populated destination, while notifying listeners, recording
	 * metrics and firing observability start/end/error events.
	 *
	 * @param destination an existing destination instance to populate, or {@code null}
	 *                    to let the mapper instantiate one
	 * @return the populated destination instance
	 * @throws MapperException if mapping fails (rethrown after error notification)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <destination> destination map(Object source, IClass<destination> destinationClass, destination destination)
			throws MapperException {
		// Top-level entry: create the per-call cycle-detection set and bind it via
		// IMappingRecursion. Recursive executors receive a recursion callback that
		// closes over this set, replacing the previous ThreadLocal-based design.
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		this.listeners.fireBeforeMapping(source, destinationClass);

		long startNanos = System.nanoTime();
		try (ObservabilityEmitter.Scope scope =
				ObservabilityEmitter.open(this.observers, UUID.randomUUID())) {
			IClass<destination> resolvedDestClass = destinationClass;
			if (resolvedDestClass == null && destination != null) {
				resolvedDestClass = (IClass<destination>) this.reflection.getClass(destination.getClass());
			}
			String mapperSource = buildObservabilitySource(source, resolvedDestClass);
			scope.fireStart(mapperSource);
			return mapWithScope(source, resolvedDestClass, destination, visited, scope, mapperSource, startNanos);
		}
	}

	private static <destination> String buildObservabilitySource(Object source, IClass<destination> resolvedDestClass) {
		String srcName = source != null ? source.getClass().getSimpleName() : "null";
		String dstName = resolvedDestClass != null ? resolvedDestClass.getSimpleName() : "null";
		return "mapper:" + srcName + "->" + dstName;
	}

	private <destination> destination mapWithScope(Object source, IClass<destination> resolvedDestClass,
			destination destination, Set<Object> visited, ObservabilityEmitter.Scope scope,
			String mapperSource, long startNanos) throws MapperException {
		try {
			CachedMappingConfiguration cachedConfig = null;
			if (source != null && resolvedDestClass != null) {
				cachedConfig = this.getCachedMappingConfiguration(
						this.reflection.getClass(source.getClass()), resolvedDestClass);
			}
			destination result = mapInternal(source, resolvedDestClass, destination, visited);
			long durationNanos = System.nanoTime() - startNanos;
			if (cachedConfig != null) {
				int rulesCount = cachedConfig.config().mappingDirection() == MappingDirection.REGULAR
						? cachedConfig.destinationExecutors().size()
						: cachedConfig.sourceExecutors().size();
				this.metrics.recordMapping(durationNanos, rulesCount);
			}
			this.listeners.fireAfterMapping(source, result, durationNanos);
			scope.fireEnd(mapperSource);
			return result;
		} catch (MapperException e) {
			this.metrics.recordFailure();
			this.listeners.fireMappingError(source, resolvedDestClass, e);
			scope.fireError(mapperSource, e);
			throw new MapperException(e.getMessage(), e);
		}
	}

	private <destination> destination mapInternal(Object source, IClass<destination> destinationClass,
			destination destination, Set<Object> visited) throws MapperException {
		IClass<destination> resolvedDestClass = resolveDestinationClass(source, destinationClass, destination);
		if (resolvedDestClass == null) {
			// Truly no information: nothing to map.
			return null;
		}

		if (source != null && !visited.add(source)) {
			if (this.configuration.failOnCycle()) {
				throw new MapperException("Mapping cycle detected for object of type " + source.getClass().getSimpleName());
			} else {
				log.warn("Cycle detected for {}, returning null", source.getClass().getSimpleName());
				return destination;
			}
		}

		// Atomic leaf types — String, primitive wrappers, JDK numerics, time
		// types, etc. Field iteration on these is wrong (they have internal
		// private fields like String.value that the module system blocks
		// from reflection) and the right answer is always "pass the source
		// through". Without this guard, an auto-convention pass over (String
		// -> String) tries to iterate String.value byte[] and the framework
		// crashes with InaccessibleObjectException on java.base.
		if (source != null && LeafTypes.isLeaf(source.getClass())) {
			@SuppressWarnings("unchecked")
			destination passthrough = (destination) source;
			return passthrough;
		}

		if (source == null) {
			// No source to read from: leave the (possibly pre-existing) destination untouched.
			return destination;
		}

		return mapByConfiguration(source, resolvedDestClass, destination, visited);
	}

	private <destination> destination mapByConfiguration(Object source, IClass<destination> resolvedDestClass,
			destination destination, Set<Object> visited) throws MapperException {
		IClass<?> sourceClass = this.reflection.getClass(source.getClass());
		CachedMappingConfiguration cachedConfig = this.getCachedMappingConfiguration(sourceClass, resolvedDestClass);
		MappingConfiguration mappingConfig = cachedConfig.config();

		log.debug("Mapping {} -> {} ({})", sourceClass.getSimpleName(), resolvedDestClass.getSimpleName(), mappingConfig.mappingDirection());

		IMappingRecursion recursion = new IMappingRecursion() {
			@Override
			public <D> D map(Object src, IClass<D> destCls) throws MapperException {
				return mapInternal(src, destCls, null, visited);
			}
		};

		List<IMappingRuleExecutor> executors = mappingConfig.mappingDirection() == MappingDirection.REVERSE
				? cachedConfig.sourceExecutors()
				: cachedConfig.destinationExecutors();
		return this.doMapping(resolvedDestClass, destination, source, executors, recursion);
	}

	/**
	 * Resolves the effective destination {@link IClass} for a recursive mapping step,
	 * falling back to the source's runtime type when the caller could not determine it.
	 *
	 * @return the resolved destination class, or {@code null} when nothing can be mapped
	 */
	@SuppressWarnings("unchecked")
	private <destination> IClass<destination> resolveDestinationClass(Object source,
			IClass<destination> destinationClass, destination destination) {
		if (destinationClass != null) {
			return destinationClass;
		}
		if (destination != null) {
			return (IClass<destination>) this.reflection.getClass(destination.getClass());
		}
		if (source != null) {
			// Recursion path where the caller couldn't resolve the destination type
			// (e.g. collection of a complex generic like List<List<X>>). Fall back to
			// the source's class — for collection-item passthrough the dest and source
			// types are typically the same.
			return (IClass<destination>) this.reflection.getClass(source.getClass());
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private <destination> destination doMapping(
			IClass<destination> destinationIClass, destination destObject, Object source,
			List<IMappingRuleExecutor> executors, IMappingRecursion recursion) throws MapperException {

		// Record mapping: build via canonical constructor
		if (destinationIClass.isRecord()) {
			return RecordMapping.map(this.reflection, destinationIClass, source);
		}

		destination result = destObject;
		for (IMappingRuleExecutor executor : executors) {
			try {
				result = executor.doMapping(destinationIClass, result, source, recursion);
			} catch (MapperException e) {
				if (this.configuration.failOnError()) {
					throw new MapperException("Unable to do mapping, aborting", e);
				} else {
					log.warn("Mapping rule failed, ignoring: {}", e.getMessage());
				}
			}
		}

		return result;
	}

	private MappingDirection determineMapingDirection(List<MappingRule> sourceRules,
			List<MappingRule> destinationRules, boolean conventionGenerated) throws MapperException {
		if (conventionGenerated) {
			return MappingDirection.REGULAR;
		}
		if (sourceRules.isEmpty() && !destinationRules.isEmpty()) {
			return MappingDirection.REGULAR;
		} else if (!sourceRules.isEmpty() && destinationRules.isEmpty()) {
			return MappingDirection.REVERSE;
		} else {
			throw new MapperException(
					"Cannot determine mapping direction as source and destination are annotated with mapping rules, or neither has mapping rule");
		}
	}

	@Override
	public Mapper configure(MapperConfigurationItem element, Object value) {
		this.configuration.configure(element, value);
		return this;
	}

	private CachedMappingConfiguration createCachedMappingConfiguration(IClass<?> source, IClass<?> destination)
			throws MapperException {
		log.debug("Creating mapping config: {} -> {}", source.getSimpleName(), destination.getSimpleName());

		List<MappingRule> destinationRules = this.mappingRules.parse(destination, source);
		List<MappingRule> sourceRules = this.mappingRules.parse(source, destination);

		boolean conventionGenerated = false;

		// Convention mapping: if no annotations found on either side
		if (destinationRules.isEmpty() && sourceRules.isEmpty() && this.configuration.autoConventionMapping()) {
			destinationRules = this.mappingRules.generateConventionRules(source, destination);
			conventionGenerated = true;
		} else if (this.configuration.autoConventionMapping()) {
			// Complement annotated rules with convention for unmapped fields
			List<MappingRule> conventionRules = this.mappingRules.generateConventionRules(source, destination);
			if (!destinationRules.isEmpty()) {
				destinationRules = complementRules(destinationRules, conventionRules);
			} else if (!sourceRules.isEmpty()) {
				// For reverse-annotated source, complement source rules with convention on destination side
				List<MappingRule> conventionSourceRules = this.mappingRules.generateConventionRules(destination, source);
				sourceRules = complementRules(sourceRules, conventionSourceRules);
			}
		}

		MappingDirection mappingDirection = this.determineMapingDirection(sourceRules, destinationRules, conventionGenerated);
		MappingConfiguration mappingConfig = new MappingConfiguration(source, destination, sourceRules,
				destinationRules, mappingDirection);

		runValidation(mappingDirection, source, destination, sourceRules, destinationRules);

		// Strict mode: check all destination fields are covered
		if (this.configuration.strictMode()) {
			this.strictCoverageValidator.validate(destination,
					mappingDirection == MappingDirection.REGULAR ? destinationRules : sourceRules);
		}

		// Pre-compute executors
		List<IMappingRuleExecutor> destExecutors = precomputeExecutors(MappingDirection.REGULAR,
				destinationRules, source, destination);
		List<IMappingRuleExecutor> srcExecutors = precomputeExecutors(MappingDirection.REVERSE,
				sourceRules, source, destination);

		return new CachedMappingConfiguration(mappingConfig, destExecutors, srcExecutors);
	}

	/** Validate the resolved rules against the active side when validation is enabled; honours failOnError. */
	private void runValidation(MappingDirection mappingDirection, IClass<?> source, IClass<?> destination,
			List<MappingRule> sourceRules, List<MappingRule> destinationRules) throws MapperException {
		try {
			if (this.configuration.doValidation()) {
				if (mappingDirection == MappingDirection.REVERSE)
					this.mappingRules.validate(destination, destinationRules);
				if (mappingDirection == MappingDirection.REGULAR)
					this.mappingRules.validate(source, sourceRules);
			}
		} catch (MapperException e) {
			if (this.configuration.failOnError()) {
				throw new MapperException("Unable to validate mapping, aborting", e);
			} else {
				log.warn("Validation failed, ignoring: {}", e.getMessage());
			}
		}
	}

	private List<MappingRule> complementRules(List<MappingRule> annotatedRules, List<MappingRule> conventionRules) {
		List<MappingRule> result = new ArrayList<>(annotatedRules);
		Set<String> coveredDest = ConcurrentHashMap.newKeySet();
		for (MappingRule r : annotatedRules) {
			if (r.destinationFieldAddress() != null) {
				coveredDest.add(r.destinationFieldAddress().toString());
			}
		}
		for (MappingRule conv : conventionRules) {
			if (conv.destinationFieldAddress() != null && !coveredDest.contains(conv.destinationFieldAddress().toString())) {
				result.add(conv);
			}
		}
		return result;
	}

	private List<IMappingRuleExecutor> precomputeExecutors(MappingDirection direction,
			List<MappingRule> rules, IClass<?> source, IClass<?> destination) throws MapperException {
		if (rules.isEmpty()) {
			return List.of();
		}

		List<IMappingRuleExecutor> executors = new ArrayList<>();
		// source/destination here are the original map(source, destination) pair
		// getRuleExecutor expects: sourceClass = actual runtime source, destClass = actual runtime dest
		IClass<?> resolvedSource = source;
		IClass<?> resolvedDest = destination;

		for (MappingRule rule : rules) {
			try {
				IMappingRuleExecutor executor = this.mappingRules.getRuleExecutor(this, direction, rule,
						resolvedSource, resolvedDest);
				if (executor != null) {
					executors.add(executor);
				}
			} catch (MapperException e) {
				if (this.configuration.failOnError()) {
					throw e;
				} else {
					log.warn("Skipping rule during precomputation: {}", e.getMessage());
				}
			}
		}
		return executors;
	}

	/**
	 * Builds, caches and returns the mapping configuration for the given pair,
	 * pre-computing its executors so subsequent {@code map(...)} calls are cheap.
	 *
	 * @return the resolved {@link MappingConfiguration}
	 * @throws MapperException if the configuration cannot be built
	 */
	@Override
	public MappingConfiguration recordMappingConfiguration(IClass<?> source, IClass<?> destination)
			throws MapperException {
		CachedMappingConfiguration cached = createCachedMappingConfiguration(source, destination);
		MappingKey key = new MappingKey(source, destination);
		this.mappingConfigurations.put(key, cached);
		return cached.config();
	}

	@Override
	public MappingConfiguration getMappingConfiguration(IClass<?> source, IClass<?> destination)
			throws MapperException {
		return getCachedMappingConfiguration(source, destination).config();
	}

	// PreserveStackTrace: we rethrow the original MapperException cause unwrapped from the
	// computeIfAbsent runtime wrapper; its own stack trace is preserved, so re-wrapping would
	// only nest it redundantly.
	@SuppressWarnings("PMD.PreserveStackTrace")
	private CachedMappingConfiguration getCachedMappingConfiguration(IClass<?> source, IClass<?> destination)
			throws MapperException {
		MappingKey key = new MappingKey(source, destination);
		try {
			return this.mappingConfigurations.computeIfAbsent(key, k -> {
				try {
					return this.createCachedMappingConfiguration(source, destination);
				} catch (MapperException e) {
					throw new MapperRuntimeException("Failed to create mapping configuration", e);
				}
			});
		} catch (MapperRuntimeException e) {
			throw (MapperException) e.getCause();
		}
	}

	/**
	 * Registers an externally-built {@link MappingConfiguration}, pre-computing and
	 * caching its regular/reverse executors under the source/destination key.
	 *
	 * @param config the configuration to install
	 * @throws MapperException if its rules cannot be turned into executors
	 */
	@Override
	public void register(MappingConfiguration config) throws MapperException {
		MappingKey key = new MappingKey(config.source(), config.destination());

		List<IMappingRuleExecutor> destExecutors = precomputeExecutors(MappingDirection.REGULAR,
				config.destinationRules(), config.source(), config.destination());
		List<IMappingRuleExecutor> srcExecutors = precomputeExecutors(MappingDirection.REVERSE,
				config.sourceRules(), config.source(), config.destination());

		this.mappingConfigurations.put(key, new CachedMappingConfiguration(config, destExecutors, srcExecutors));
	}

	@Override
	public void addListener(IMappingListener listener) {
		this.listeners.add(listener);
	}

	@Override
	public MapperMetrics getMetrics() {
		return this.metrics;
	}

	private static class MapperRuntimeException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		MapperRuntimeException(String message, MapperException cause) {
			super(message, cause);
		}
	}

}
