package com.garganttua.events.connectors.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.WakeupException;

import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.IAcknowledgingConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * A Kafka consumer that commits an offset only once its message is SAFE — the route succeeded, or handed it to
 * its error subscription — for a subscription declaring
 * {@link com.garganttua.events.api.context.QualityOfService#AT_LEAST_ONCE}.
 *
 * <p>
 * Each poll's records are handed to the route — concurrently when the subscription allows it — and their
 * outcomes awaited. Then, partition by partition, the offset is committed up to the first message that is not
 * safe, and the partition is SOUGHT back to it: that message, and those after it in its partition, are read
 * again. A message may thus be processed twice — the contract of at-least-once: consumers are idempotent.
 * After a poll that failed, the next one waits, longer each time (from 200 ms, doubling, at most 5 s), so that
 * a route that keeps failing does not spin; a message that never succeeds and has no error subscription
 * blocks its partition, which is what an audit trail must do rather than lose it.
 * </p>
 *
 * <p>
 * Auto-commit must be off — the connector forces it. A route slower than {@code max.poll.interval.ms} over a
 * whole poll makes the member leave its group; the next poll rejoins and the uncommitted records are delivered
 * again, which is the protocol.
 * </p>
 */
public final class KafkaAcknowledgingConsumer implements IAcknowledgingConsumer {

	private static final Logger LOG = Logger.getLogger(KafkaAcknowledgingConsumer.class);
	private static final long FIRST_REDELIVERY_DELAY_MILLIS = 200L;
	private static final long MAX_REDELIVERY_DELAY_MILLIS = 5_000L;

	private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
	private final long pollIntervalMs;
	private final long firstDelayMs;
	private final long maxDelayMs;
	private volatile boolean running;

	/**
	 * @param consumer       the Kafka consumer, subscribed, auto-commit off
	 * @param pollIntervalMs how long a poll waits for records
	 */
	public KafkaAcknowledgingConsumer(org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
			long pollIntervalMs) {
		this(consumer, pollIntervalMs, FIRST_REDELIVERY_DELAY_MILLIS, MAX_REDELIVERY_DELAY_MILLIS);
	}

	KafkaAcknowledgingConsumer(org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
			long pollIntervalMs, long firstDelayMs, long maxDelayMs) {
		this.consumer = consumer;
		this.pollIntervalMs = pollIntervalMs;
		this.firstDelayMs = firstDelayMs;
		this.maxDelayMs = maxDelayMs;
	}

	/** Without outcomes, every message is safe once handled: committed message by message, after it. */
	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		startAcknowledged(bytes -> {
			messageHandler.accept(bytes);
			return CompletableFuture.completedFuture(Boolean.TRUE);
		});
	}

	@Override
	public void startAcknowledged(Function<byte[], CompletionStage<Boolean>> messageHandler) throws ConnectorException {
		this.running = true;
		int failedPolls = 0;
		while (this.running) {
			try {
				ConsumerRecords<String, byte[]> records = this.consumer.poll(Duration.ofMillis(this.pollIntervalMs));
				if (records.isEmpty()) {
					continue;
				}
				failedPolls = acknowledge(records, messageHandler) ? 0 : failedPolls + 1;
				if (failedPolls > 0) {
					backOff(failedPolls);
				}
			} catch (CommitFailedException | RebalanceInProgressException e) {
				// The partitions moved to another member, which reads them again from the last commit.
				LOG.warn("Kafka consumer: offsets not committed after a rebalance — the uncommitted records will be "
						+ "delivered again to the partitions' current owners; rejoining the group ({})", e.getMessage());
			} catch (WakeupException e) {
				if (this.running) {
					throw e;
				}
			}
		}
	}

	/**
	 * Hands a poll's records to the route, awaits them, commits what is safe and seeks back to what is not.
	 *
	 * @return whether every record was safe
	 */
	private boolean acknowledge(ConsumerRecords<String, byte[]> records,
			Function<byte[], CompletionStage<Boolean>> handler) {
		Map<TopicPartition, List<Pending>> byPartition = new LinkedHashMap<>();
		for (TopicPartition partition : records.partitions()) {
			List<Pending> pending = new ArrayList<>();
			for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
				pending.add(new Pending(record.offset(), outcomeOf(handler, record.value())));
			}
			byPartition.put(partition, pending);
		}
		Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
		boolean allSafe = true;
		for (Map.Entry<TopicPartition, List<Pending>> entry : byPartition.entrySet()) {
			long nextToRead = -1L;
			Long firstUnsafe = null;
			for (Pending pending : entry.getValue()) {
				// Awaited even past an unsafe one: none of this poll may still be running when its copy is re-read.
				boolean safe = safe(pending.outcome());
				if (firstUnsafe == null && safe) {
					nextToRead = pending.offset() + 1;
				} else if (firstUnsafe == null) {
					firstUnsafe = pending.offset();
				}
			}
			if (nextToRead >= 0) {
				commits.put(entry.getKey(), new OffsetAndMetadata(nextToRead));
			}
			if (firstUnsafe != null) {
				this.consumer.seek(entry.getKey(), firstUnsafe);
				LOG.warn("Kafka consumer: message {} of {} is not safe — it will be read again", firstUnsafe,
						entry.getKey());
				allSafe = false;
			}
		}
		if (!commits.isEmpty()) {
			this.consumer.commitSync(commits);
		}
		return allSafe;
	}

	private static CompletableFuture<Boolean> outcomeOf(Function<byte[], CompletionStage<Boolean>> handler, byte[] value) {
		try {
			CompletionStage<Boolean> stage = handler.apply(value);
			return stage == null ? CompletableFuture.completedFuture(Boolean.FALSE) : stage.toCompletableFuture();
		} catch (RuntimeException e) {
			LOG.error("Kafka consumer: the route could not take a message", e);
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}
	}

	private static boolean safe(CompletableFuture<Boolean> outcome) {
		try {
			return Boolean.TRUE.equals(outcome.join());
		} catch (CompletionException | CancellationException e) {
			return false;
		}
	}

	private void backOff(int failedPolls) {
		long delay = Math.min(this.firstDelayMs << Math.min(failedPolls - 1, 10), this.maxDelayMs);
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.running = false;
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
		this.consumer.wakeup();
	}

	/** A record handed to the route, and its outcome to come. */
	private record Pending(long offset, CompletableFuture<Boolean> outcome) {
	}
}
