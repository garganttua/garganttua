package com.garganttua.events.connectors.kafka;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

public class KafkaProducer_ implements IProducer {

	private final Producer<String, byte[]> kafkaProducer;
	private final String topic;
	private final String dataflowUuid;
	private final long ackTimeoutMs;

	public KafkaProducer_(KafkaProducer<String, byte[]> kafkaProducer, String topic, String dataflowUuid) {
		this(kafkaProducer, topic, dataflowUuid, 0L);
	}

	/**
	 * @param ackTimeoutMs how long {@link #publish} waits for the broker's acknowledgement; {@code 0} keeps
	 *                     the historical fire-and-forget (a record the broker never stores is lost without
	 *                     a word to the publisher)
	 */
	// The connector must retain the live client to publish/close it: storing the reference is intended.
	public KafkaProducer_(Producer<String, byte[]> kafkaProducer, String topic, String dataflowUuid,
			long ackTimeoutMs) {
		this.kafkaProducer = kafkaProducer;
		this.topic = topic;
		this.dataflowUuid = dataflowUuid;
		this.ackTimeoutMs = ackTimeoutMs;
	}

	@Override
	public void publish(byte[] value) throws ConnectorException {
		ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, dataflowUuid, value);
		var sent = kafkaProducer.send(record);
		if (ackTimeoutMs <= 0) {
			return;
		}
		try {
			sent.get(ackTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConnectorException("Kafka publish on " + topic + " interrupted before its acknowledgement", e);
		} catch (ExecutionException e) {
			throw new ConnectorException("Kafka publish on " + topic + " refused by the broker", e);
		} catch (TimeoutException e) {
			throw new ConnectorException("Kafka publish on " + topic + " not acknowledged within " + ackTimeoutMs
					+ " ms", e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		kafkaProducer.close();
	}
}
