package com.garganttua.events.connectors.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

public class KafkaProducer_ implements IProducer {

	private final KafkaProducer<String, byte[]> kafkaProducer;
	private final String topic;
	private final String dataflowUuid;

	public KafkaProducer_(KafkaProducer<String, byte[]> kafkaProducer, String topic, String dataflowUuid) {
		this.kafkaProducer = kafkaProducer;
		this.topic = topic;
		this.dataflowUuid = dataflowUuid;
	}

	@Override
	public void publish(byte[] value) throws ConnectorException {
		ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, dataflowUuid, value);
		kafkaProducer.send(record);
	}

	@Override
	public void stop() throws ConnectorException {
		kafkaProducer.close();
	}
}
