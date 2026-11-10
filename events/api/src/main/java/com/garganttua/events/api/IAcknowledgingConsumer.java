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
 * message is SAFE — its route succeeded, or, after the route's {@code exceptions.attempts}, the error subscription
 * took it — and {@code false} when it is nowhere (it still fails and there is no error subscription, or handing it
 * over failed). The connector acknowledges on {@code true} and gives the message back on {@code false} —
 * at-least-once delivery up to the end of the route.
 *
 * <p>Before 3.0.0-ALPHA34, a message handed to the error subscription completed with {@code false}: a connector that
 * gave it back then delivered it again AND had it in the error subscription.
 *
 * <p>The engine starts a connector this way unless its subscription declares
 * {@link com.garganttua.events.api.context.QualityOfService#AT_MOST_ONCE}; one declaring
 * {@link com.garganttua.events.api.context.QualityOfService#AT_LEAST_ONCE} requires it.
 *
 * <p>The stage completes on the route's worker thread; a connector must not block it.
 */
public interface IAcknowledgingConsumer extends IConsumer {

	void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) throws ConnectorException;
}
