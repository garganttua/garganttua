package com.garganttua.events.connectors.kafka;

import java.time.Duration;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerRecords;

import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

public class KafkaConsumer_ implements IConsumer {

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
			Duration duration = Duration.ofMillis(pollIntervalMs);
			ConsumerRecords<String, byte[]> records = consumer.poll(duration);
			records.forEach(record -> messageHandler.accept(record.value()));
			if (!autoCommit && !records.isEmpty()) {
				consumer.commitSync();
			}
		}
	}

	@Override
	public void stop() throws ConnectorException {
		this.running = false;
		consumer.wakeup();
	}
}
