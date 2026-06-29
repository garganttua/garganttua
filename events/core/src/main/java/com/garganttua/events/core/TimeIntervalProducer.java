package com.garganttua.events.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.TimeIntervalDef;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * {@link IProducer} decorator implementing {@code TIME_INTERVAL} publication: {@link #publish} does
 * not emit immediately — it buffers the payload, and a scheduled task flushes the buffer to the
 * wrapped producer at the configured interval.
 *
 * <p>Three modes, selected by the subscription's {@code buffered} / {@code bufferPersisted}:</p>
 * <ul>
 *   <li><b>not buffered</b> — last-wins sampling: only the most recent payload is kept and emitted
 *       on each tick (the legacy {@code GGTimeIntervalProducer} semantics);</li>
 *   <li><b>buffered (in-memory)</b> — every payload of the interval is accumulated and emitted as a
 *       batch on each tick;</li>
 *   <li><b>buffered + persisted</b> — same batch semantics, but the buffer is file-backed and
 *       survives a restart (replayed on the next flush).</li>
 * </ul>
 *
 * @since 3.0.0-ALPHA04
 */
final class TimeIntervalProducer implements IProducer {

	private static final Logger log = Logger.getLogger(TimeIntervalProducer.class);

	private final IProducer delegate;
	private final long interval;
	private final TimeUnit unit;
	private final boolean buffered;
	private final PersistentBuffer persistent;

	private final Object lock = new Object();
	private byte[] latest;
	private final List<byte[]> batch = new ArrayList<>();
	private ScheduledFuture<?> future;

	TimeIntervalProducer(IProducer delegate, TimeIntervalDef timeInterval, boolean buffered,
			PersistentBuffer persistent) {
		this.delegate = delegate;
		this.interval = Math.max(1, timeInterval.interval());
		this.unit = parseUnit(timeInterval.unit());
		this.buffered = buffered;
		this.persistent = persistent;
	}

	private static TimeUnit parseUnit(String unit) {
		try {
			return unit == null ? TimeUnit.SECONDS : TimeUnit.valueOf(unit.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return TimeUnit.SECONDS;
		}
	}

	/** Schedules the periodic flush; called once the connectors are started. */
	void start(ScheduledExecutorService scheduler) {
		this.future = scheduler.scheduleAtFixedRate(this::flush, interval, interval, unit);
	}

	// ArrayIsStoredDirectly: last-wins sampling intentionally keeps a reference to the latest payload
	// array (the caller hands ownership over to the producer); a defensive copy would only add churn
	// for bytes that are about to be published verbatim.
	@Override
	@SuppressWarnings("PMD.ArrayIsStoredDirectly")
	public void publish(byte[] value) {
		if (persistent != null) {
			persistent.append(value);
			return;
		}
		synchronized (lock) {
			if (buffered) {
				batch.add(value);
			} else {
				latest = value;
			}
		}
	}

	// NullAssignment: clearing the last-wins slot to null after draining is the buffer's reset; it is
	// a deliberate "consumed" marker, not a forgotten cleanup, so the next tick emits nothing until a
	// new payload arrives.
	/** Emits the buffered payload(s) to the wrapped producer; never throws. */
	@SuppressWarnings("PMD.NullAssignment")
	private void flush() {
		try {
			if (persistent != null) {
				for (byte[] value : persistent.drain()) {
					delegate.publish(value);
				}
				return;
			}
			List<byte[]> toSend = new ArrayList<>();
			synchronized (lock) {
				if (buffered) {
					toSend.addAll(batch);
					batch.clear();
				} else if (latest != null) {
					toSend.add(latest);
					latest = null;
				}
			}
			for (byte[] value : toSend) {
				delegate.publish(value);
			}
		} catch (ConnectorException | RuntimeException e) {
			log.error("TIME_INTERVAL flush failed", e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		if (future != null) {
			future.cancel(false);
		}
		flush();
		if (persistent != null) {
			persistent.close();
		}
		delegate.stop();
	}

	/**
	 * A simple file-backed FIFO buffer of length-prefixed frames. {@link #append} adds one frame,
	 * {@link #drain} reads and clears all frames. Survives a restart: an existing file is replayed.
	 */
	static final class PersistentBuffer implements AutoCloseable {

		private final Path file;
		private final Object fileLock = new Object();

		PersistentBuffer(Path file) throws IOException {
			this.file = file;
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (!Files.exists(file)) {
				Files.createFile(file);
			}
		}

		void append(byte[] value) {
			synchronized (fileLock) {
				try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
						Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
					out.writeInt(value.length);
					out.write(value);
				} catch (IOException e) {
					log.error("Failed to append to persistent buffer {}", file, e);
				}
			}
		}

		List<byte[]> drain() {
			synchronized (fileLock) {
				List<byte[]> frames = new ArrayList<>();
				try (DataInputStream in = new DataInputStream(new BufferedInputStream(
						Files.newInputStream(file)))) {
					while (true) {
						int length = in.readInt();
						frames.add(in.readNBytes(length));
					}
				} catch (EOFException eof) {
					log.trace("Reached end of persistent buffer {}", file);
				} catch (IOException e) {
					log.error("Failed to read persistent buffer {}", file, e);
				}
				try {
					Files.write(file, new byte[0]);
				} catch (IOException e) {
					log.error("Failed to truncate persistent buffer {}", file, e);
				}
				return frames;
			}
		}

		@Override
		public void close() {
			// the file is left in place; a restart replays any unflushed frames
		}
	}
}
