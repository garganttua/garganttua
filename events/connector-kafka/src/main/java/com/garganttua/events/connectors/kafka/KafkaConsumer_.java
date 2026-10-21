package com.garganttua.events.connectors.kafka;

import java.time.Duration;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

public class KafkaConsumer_ implements IConsumer {

	private static final Logger log = LoggerFactory.getLogger(KafkaConsumer_.class);

	private final org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer;
	private final boolean autoCommit;
	private final long pollIntervalMs;
	private volatile boolean running;

	public KafkaConsumer_(org.apache.kafka.clients.consumer.Consumer<String, byte[]> consumer,
			boolean autoCommit, long pollIntervalMs) {
		this.consumer = consumer;
		this.autoCommit = autoCommit;
		this.pollIntervalMs = pollIntervalMs;
	}

	@Override
	public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
		this.running = true;
		while (running) {
			try {
				Duration duration = Duration.ofMillis(pollIntervalMs);
				ConsumerRecords<String, byte[]> records = consumer.poll(duration);
				records.forEach(record -> messageHandler.accept(record.value()));
				if (!autoCommit && !records.isEmpty()) {
					consumer.commitSync();
				}
			} catch (CommitFailedException | RebalanceInProgressException e) {
				// The handler outlived max.poll.interval.ms (or a rebalance started meanwhile): this member
				// was evicted and its partitions handed to another member, which will re-deliver the records
				// of this poll from the last committed offset. The commit is refused — that is the protocol,
				// not a failure of this consumer. Ending the loop here (the previous behaviour) left the
				// member silently deaf until the process restarted: the next poll() rejoins the group.
				log.warn("Kafka consumer: offsets not committed after a rebalance — the records of this poll will "
						+ "be redelivered to the partition's current owner; rejoining the group ({})", e.getMessage());
			} catch (WakeupException e) {
				if (running) {
					throw e;
				}
				// stop() woke the poll up: leave the loop.
			}
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
		consumer.wakeup();
	}
}
