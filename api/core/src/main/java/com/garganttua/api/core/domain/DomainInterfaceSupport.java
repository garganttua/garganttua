package com.garganttua.api.core.domain;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.api.core.event.EventPublisherObserver;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.supply.ISupplier;

/**
 * Interface / event-publisher lifecycle helpers for {@link Domain}: resolves the {@code .interfasse}
 * suppliers and applies a lifecycle action to each ({@code handle}/{@code onInit}/{@code onStart}/
 * {@code onStop}/{@code onFlush}), registers DAOs with the domain definition, and wires
 * {@code .events(...)} publishers as observers. Extracted from {@code Domain} to keep that wide
 * context under the file-size gate; behaviour is identical.
 */
final class DomainInterfaceSupport {

    private static final Logger log = Logger.getLogger(DomainInterfaceSupport.class);

    private DomainInterfaceSupport() {
    }

    /** Hands each DAO its domain definition (no-op for DAOs that don't care). */
    static void registerDomainOnDaos(List<IDtoContext<?>> dtoContexts,
            DomainDefinition<?> domainDefinition) {
        for (IDtoContext<?> dtoContext : dtoContexts) {
            dtoContext.getDao().registerDomain(domainDefinition);
        }
    }

    /** Applies a lifecycle action to every resolved interface. */
    static void forEach(List<ISupplier<IInterface>> interfaces, String domainName,
            Consumer<IInterface> action) {
        for (ISupplier<IInterface> supplier : interfaces) {
            try {
                IInterface intf = supplier.supply()
                        .orElseThrow(() -> new ApiException("Interface supplier returned empty Optional"));
                action.accept(intf);
            } catch (RuntimeException e) {
                throw new ApiException("Interface action failed for domain " + domainName, e);
            }
        }
    }

    /** Applies a lifecycle action with an argument to every resolved interface. */
    static <T> void forEach(List<ISupplier<IInterface>> interfaces, String domainName,
            BiConsumer<IInterface, T> action, T arg) {
        for (ISupplier<IInterface> supplier : interfaces) {
            try {
                IInterface intf = supplier.supply()
                        .orElseThrow(() -> new ApiException("Interface supplier returned empty Optional"));
                action.accept(intf, arg);
            } catch (RuntimeException e) {
                throw new ApiException("Interface action failed for domain " + domainName, e);
            }
        }
    }

    /**
     * Subscribes one {@link EventPublisherObserver} per {@code .events(...)} registration onto the
     * domain's observable registry. Registering a publisher flips the domain onto the observability
     * slow path (via {@code hasObservers()}), so a domain with no events and no {@code @Observer} pays
     * nothing.
     */
    static void initializeEvents(List<ISupplier<IEventPublisher>> events,
            ObservableRegistry observableRegistry, String domainName) {
        for (ISupplier<IEventPublisher> supplier : events) {
            try {
                IEventPublisher publisher = supplier.supply()
                        .orElseThrow(() -> new ApiException("Event publisher supplier returned empty Optional"));
                observableRegistry.addObserver(new EventPublisherObserver(publisher));
            } catch (Exception e) {
                throw new ApiException("Failed to initialize event publisher for domain " + domainName, e);
            }
        }
        log.debug("Registered {} event publisher(s) as observers for domain {}", events.size(), domainName);
    }
}
