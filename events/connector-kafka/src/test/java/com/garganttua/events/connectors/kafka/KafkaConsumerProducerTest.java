package com.garganttua.events.connectors.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import com.garganttua.events.api.exceptions.ConnectorException;

class KafkaConsumerProducerTest {

	private static final TopicPartition PARTITION = new TopicPartition("orders", 0);

	@Test
	void aRefusedCommitDoesNotEndTheConsumerLoop() throws Exception {
		AtomicInteger commits = new AtomicInteger();
		MockConsumer<String, byte[]> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
			@Override
			public synchronized void commitSync() {
				if (commits.incrementAndGet() == 1) {
					// What the client throws when the handler outlived max.poll.interval.ms.
					throw new CommitFailedException();
				}
				super.commitSync();
			}
		};
		mock.assign(List.of(PARTITION));
		mock.updateBeginningOffsets(Map.of(PARTITION, 0L));
		mock.addRecord(new ConsumerRecord<>("orders", 0, 0L, "k", "first".getBytes()));
		KafkaConsumer_ consumer = new KafkaConsumer_(mock, false, 10L);
		List<String> handled = new ArrayList<>();
		// Second poll: a record that only a still-running loop can see; third poll: stop.
		mock.schedulePollTask(() -> { });
		mock.schedulePollTask(() -> mock.addRecord(new ConsumerRecord<>("orders", 0, 1L, "k", "second".getBytes())));
		mock.schedulePollTask(() -> assertDoesNotThrow(consumer::stop));

		consumer.start(value -> handled.add(new String(value)));

		assertEquals(List.of("first", "second"), handled, "the loop survived the refused commit");
		assertEquals(2, commits.get());
	}

	@Test
	void anUnacknowledgedPublishFailsWhenAnAckTimeoutIsSet() throws Exception {
		MockProducer<String, byte[]> neverAcked = new MockProducer<>(false, new StringSerializer(),
				new ByteArraySerializer());
		KafkaProducer_ waiting = new KafkaProducer_(neverAcked, "orders", "dataflow", 50L);
		ConnectorException refused = assertThrows(ConnectorException.class, () -> waiting.publish("x".getBytes()));
		assertEquals(true, refused.getMessage().contains("not acknowledged"), refused.getMessage());

		// 0 keeps the historical fire-and-forget.
		KafkaProducer_ fireAndForget = new KafkaProducer_(neverAcked, "orders", "dataflow", 0L);
		assertDoesNotThrow(() -> fireAndForget.publish("y".getBytes()));

		MockProducer<String, byte[]> acked = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
		assertDoesNotThrow(() -> new KafkaProducer_(acked, "orders", "dataflow", 50L).publish("z".getBytes()));
		assertEquals("dataflow", acked.history().get(0).key(), "keyed with the dataflow uuid, as before");
	}
}
