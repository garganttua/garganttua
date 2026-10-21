package com.garganttua.events.api;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * A consumer that acknowledges a message only once its ROUTE HAS RUN.
 *
 * <p>With {@link IConsumer#start}, the engine takes the message and dispatches it to the route; the connector learns
 * nothing of the outcome, so it can only acknowledge at the hand-off — a message whose route fails is lost (or must
 * be retried by business means). A connector that implements this interface is started with
 * {@link #startAcknowledged} instead: for each message the engine returns a stage completed with {@code true} once the
 * route succeeded, {@code false} once it failed (after the error subscription received it, when one is configured).
 * The connector acknowledges on {@code true} and gives the message back on {@code false} — at-least-once delivery up
 * to the end of the route.
 *
 * <p>The stage completes on the route's worker thread; a connector must not block it.
 */
public interface IAcknowledgingConsumer extends IConsumer {

	void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) throws ConnectorException;
}
