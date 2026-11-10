package com.garganttua.core.observability;

/**
 * A BLOCKING observer could not take an event ({@link GlobalObservers#addBlockingObserver}): the
 * observed operation must not go on as if it had been observed.
 *
 * <p>
 * Thrown once every observer — blocking or not — has been offered the event, so one failure never
 * hides an event from the others. The first failure is the cause; the others, if any, are suppressed.
 * </p>
 *
 * @since 3.0.0-ALPHA34
 */
public class ObserverFailureException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param event the event that could not be taken
	 * @param cause what the first blocking observer threw
	 */
	public ObserverFailureException(ObservableEvent event, RuntimeException cause) {
		super("A blocking observer could not take event " + event.source() + ": " + cause.getMessage(), cause);
	}
}
