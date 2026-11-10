package com.garganttua.events.connectors.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

/**
 * AT_LEAST_ONCE over BigQueue, on a real queue on disk: a message leaves the disk only once it is safe.
 */
@DisplayName("BigQueue — acknowledged after the route")
class BusAcknowledgingConsumerTest {

	@TempDir
	Path dir;

	private IBigQueue queue;

	@AfterEach
	void close() throws Exception {
		if (this.queue != null) {
			this.queue.close();
		}
	}

	private IBigQueue queue(String... values) throws Exception {
		this.queue = new BigQueueImpl(this.dir.toString(), "audit");
		for (String value : values) {
			this.queue.enqueue(new BusMessage("dataflow", value.getBytes(StandardCharsets.UTF_8)).toBytes());
		}
		return this.queue;
	}

	/** Runs the consumer until the queue is empty or a limit of route calls is reached. */
	private static void runUntil(BusAcknowledgingConsumer consumer, IBigQueue queue, AtomicInteger calls, int maxCalls)
			throws Exception {
		Thread stopper = new Thread(() -> {
			long deadline = System.currentTimeMillis() + 5_000;
			while (System.currentTimeMillis() < deadline && !queue.isEmpty() && calls.get() < maxCalls) {
				Thread.onSpinWait();
			}
			try {
				consumer.stop();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		stopper.start();
		stopper.join(6_000);
	}

	@Test
	@DisplayName("safe messages leave the disk, in order")
	void safe() throws Exception {
		IBigQueue q = queue("a", "b", "c");
		List<String> handled = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger calls = new AtomicInteger();
		BusAcknowledgingConsumer consumer = new BusAcknowledgingConsumer(q, new Object(), 5L, 1L, 1L);
		Thread worker = new Thread(() -> {
			try {
				consumer.startAcknowledged(value -> {
					calls.incrementAndGet();
					handled.add(new String(value, StandardCharsets.UTF_8));
					return CompletableFuture.completedFuture(true);
				});
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		worker.start();
		runUntil(consumer, q, calls, 3);
		worker.join(5_000);

		assertEquals(List.of("a", "b", "c"), handled);
		assertTrue(q.isEmpty());
	}

	@Test
	@DisplayName("a message whose route fails stays at the head, is tried again, and is not lost")
	void unsafeStays() throws Exception {
		IBigQueue q = queue("a", "b");
		List<String> handled = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger failuresOfA = new AtomicInteger();
		BusAcknowledgingConsumer consumer = new BusAcknowledgingConsumer(q, new Object(), 5L, 1L, 1L);
		Thread worker = new Thread(() -> {
			try {
				consumer.startAcknowledged(value -> {
					calls.incrementAndGet();
					String text = new String(value, StandardCharsets.UTF_8);
					handled.add(text);
					boolean safe = !"a".equals(text) || failuresOfA.incrementAndGet() > 2;
					return CompletableFuture.completedFuture(safe);
				});
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		worker.start();
		runUntil(consumer, q, calls, 4);
		worker.join(5_000);

		assertEquals(List.of("a", "a", "a", "b"), handled, "a, twice refused, then taken; b only after it");
		assertTrue(q.isEmpty());
	}

	@Test
	@DisplayName("a message that cannot be decoded is removed: no route could ever take it")
	void undecodable() throws Exception {
		IBigQueue q = queue();
		q.enqueue("no-separator".getBytes(StandardCharsets.UTF_8));
		q.enqueue(new BusMessage("dataflow", "ok".getBytes(StandardCharsets.UTF_8)).toBytes());
		List<String> handled = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger calls = new AtomicInteger();
		BusAcknowledgingConsumer consumer = new BusAcknowledgingConsumer(q, new Object(), 5L, 1L, 1L);
		Thread worker = new Thread(() -> {
			try {
				consumer.startAcknowledged(value -> {
					calls.incrementAndGet();
					handled.add(new String(value, StandardCharsets.UTF_8));
					return CompletableFuture.completedFuture(true);
				});
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		worker.start();
		runUntil(consumer, q, calls, 1);
		worker.join(5_000);

		assertEquals(List.of("ok"), handled);
		assertTrue(q.isEmpty());
	}
}
