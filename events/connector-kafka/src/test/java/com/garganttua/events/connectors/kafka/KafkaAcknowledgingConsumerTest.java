package com.garganttua.events.connectors.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AT_LEAST_ONCE over Kafka: an offset is committed only once its message is safe, and a message that is not
 * is read again.
 */
@DisplayName("Kafka — acknowledged after the route")
class KafkaAcknowledgingConsumerTest {

	private static final TopicPartition PARTITION = new TopicPartition("audit", 0);

	private static MockConsumer<String, byte[]> mock() {
		MockConsumer<String, byte[]> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
		mock.assign(List.of(PARTITION));
		mock.updateBeginningOffsets(Map.of(PARTITION, 0L));
		return mock;
	}

	private static ConsumerRecord<String, byte[]> record(long offset, String value) {
		return new ConsumerRecord<>("audit", 0, offset, "k", value.getBytes());
	}

	private static long committed(MockConsumer<String, byte[]> mock) {
		OffsetAndMetadata committed = mock.committed(java.util.Set.of(PARTITION)).get(PARTITION);
		return committed == null ? -1L : committed.offset();
	}

	@Test
	@DisplayName("every message safe: the poll is committed up to its end")
	void allSafe() throws Exception {
		MockConsumer<String, byte[]> mock = mock();
		mock.addRecord(record(0, "a"));
		mock.addRecord(record(1, "b"));
		mock.addRecord(record(2, "c"));
		KafkaAcknowledgingConsumer consumer = new KafkaAcknowledgingConsumer(mock, 10L, 1L, 1L);
		List<String> handled = Collections.synchronizedList(new ArrayList<>());
		mock.schedulePollTask(() -> { });
		mock.schedulePollTask(() -> assertDoesNotThrow(consumer::stop));

		consumer.startAcknowledged(value -> {
			handled.add(new String(value));
			return CompletableFuture.completedFuture(true);
		});

		assertEquals(List.of("a", "b", "c"), handled);
		assertEquals(3L, committed(mock), "the next offset to read after the last safe one");
	}

	@Test
	@DisplayName("a message not safe: committed up to it, the partition sought back to it, and it is read again")
	void unsafeIsReadAgain() throws Exception {
		MockConsumer<String, byte[]> mock = mock();
		mock.addRecord(record(0, "a"));
		mock.addRecord(record(1, "b"));
		mock.addRecord(record(2, "c"));
		KafkaAcknowledgingConsumer consumer = new KafkaAcknowledgingConsumer(mock, 10L, 1L, 1L);
		AtomicInteger triesOfB = new AtomicInteger();
		List<Long> positions = new ArrayList<>();
		List<Long> commits = new ArrayList<>();
		mock.schedulePollTask(() -> { });
		mock.schedulePollTask(() -> {
			// What the broker delivers after the seek: the unsafe message and those after it.
			positions.add(mock.position(PARTITION));
			commits.add(committed(mock));
			mock.addRecord(record(1, "b"));
			mock.addRecord(record(2, "c"));
		});
		mock.schedulePollTask(() -> assertDoesNotThrow(consumer::stop));

		consumer.startAcknowledged(value -> {
			boolean safe = !"b".equals(new String(value)) || triesOfB.incrementAndGet() > 1;
			return CompletableFuture.completedFuture(safe);
		});

		assertEquals(List.of(1L), positions, "sought back to the unsafe message");
		assertEquals(List.of(1L), commits, "nothing past it was committed");
		assertEquals(2, triesOfB.get(), "read again, and safe the second time");
		assertEquals(3L, committed(mock));
	}

	@Test
	@DisplayName("outcomes completing later, on other threads, are awaited before anything is committed")
	void concurrentOutcomes() throws Exception {
		MockConsumer<String, byte[]> mock = mock();
		mock.addRecord(record(0, "a"));
		mock.addRecord(record(1, "b"));
		KafkaAcknowledgingConsumer consumer = new KafkaAcknowledgingConsumer(mock, 10L, 1L, 1L);
		mock.schedulePollTask(() -> { });
		mock.schedulePollTask(() -> assertDoesNotThrow(consumer::stop));

		consumer.startAcknowledged(value -> CompletableFuture.supplyAsync(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return true;
		}));

		assertEquals(2L, committed(mock));
	}

	@Test
	@DisplayName("a route that throws instead of answering: the message is not safe")
	void throwingRoute() throws Exception {
		MockConsumer<String, byte[]> mock = mock();
		mock.addRecord(record(0, "a"));
		KafkaAcknowledgingConsumer consumer = new KafkaAcknowledgingConsumer(mock, 10L, 1L, 1L);
		mock.schedulePollTask(() -> { });
		mock.schedulePollTask(() -> assertDoesNotThrow(consumer::stop));

		consumer.startAcknowledged(value -> {
			throw new IllegalStateException("no route");
		});

		assertEquals(-1L, committed(mock), "nothing committed");
		assertEquals(0L, mock.position(PARTITION), "and it will be read again");
	}
}
