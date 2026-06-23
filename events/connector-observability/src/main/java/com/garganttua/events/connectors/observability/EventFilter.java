package com.garganttua.events.connectors.observability;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.StartEvent;

/**
 * Decides which {@link ObservableEvent}s a consumer forwards, by event type and an optional
 * glob on {@link ObservableEvent#source()}.
 *
 * <p>Built from connector configuration: an {@code events} comma list (subset of
 * {@code start,end,error,log}) and a {@code sourcePattern} glob (e.g. {@code api:operation:*}).
 * An empty {@code events} list means "all types"; an absent {@code sourcePattern} means
 * "any source".</p>
 */
public final class EventFilter {

	/** The four forwardable event kinds, keyed by their lowercase configuration token. */
	public enum Kind {
		START, END, ERROR, LOG;

		static Kind of(ObservableEvent event) {
			return switch (event) {
				case StartEvent ignored -> START;
				case EndEvent ignored -> END;
				case ErrorEvent ignored -> ERROR;
				case LogEvent ignored -> LOG;
			};
		}
	}

	private final Set<Kind> kinds;
	private final Pattern sourcePattern;

	private EventFilter(Set<Kind> kinds, Pattern sourcePattern) {
		this.kinds = kinds;
		this.sourcePattern = sourcePattern;
	}

	/**
	 * Build a filter from the raw configuration tokens.
	 *
	 * @param eventsCsv     comma-separated event kinds (case-insensitive); {@code null}/blank = all
	 * @param sourceGlob    a glob on the event source; {@code null}/blank = any source
	 * @return the corresponding filter
	 */
	public static EventFilter of(String eventsCsv, String sourceGlob) {
		Set<Kind> kinds = parseKinds(eventsCsv);
		Pattern pattern = parseGlob(sourceGlob);
		return new EventFilter(kinds, pattern);
	}

	private static Set<Kind> parseKinds(String eventsCsv) {
		Set<Kind> kinds = EnumSet.allOf(Kind.class);
		if (eventsCsv == null || eventsCsv.isBlank()) {
			return kinds;
		}
		Set<Kind> selected = EnumSet.noneOf(Kind.class);
		for (String token : eventsCsv.split(",")) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				selected.add(Kind.valueOf(trimmed.toUpperCase(Locale.ROOT)));
			}
		}
		return selected.isEmpty() ? kinds : selected;
	}

	private static Pattern parseGlob(String sourceGlob) {
		if (sourceGlob == null || sourceGlob.isBlank()) {
			return null;
		}
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < sourceGlob.length(); i++) {
			char c = sourceGlob.charAt(i);
			switch (c) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append('.');
				default -> regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString());
	}

	/**
	 * @param event the event to test
	 * @return {@code true} if the event's kind is enabled and its source matches the glob
	 */
	public boolean matches(ObservableEvent event) {
		if (!this.kinds.contains(Kind.of(event))) {
			return false;
		}
		return this.sourcePattern == null
				|| this.sourcePattern.matcher(String.valueOf(event.source())).matches();
	}
}
