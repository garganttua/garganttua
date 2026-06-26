package com.garganttua.core.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link GlobalObservers#hasObservers()}: it reports {@code true} only while at least one
 * firehose observer is registered, and returns to {@code false} once observers are removed.
 */
@DisplayName("GlobalObservers.hasObservers()")
class GlobalObserversHasObserversTest {

	@Nested
	@DisplayName("registration state")
	class RegistrationState {

		@Test
		@DisplayName("false when no observer is registered")
		void falseWhenEmpty() {
			assertFalse(GlobalObservers.hasObservers(),
					"no observer registered → hasObservers() must be false");
		}

		@Test
		@DisplayName("true after add, false again after remove")
		void trueAfterAddFalseAfterRemove() {
			IObserver<ObservableEvent> observer = e -> { /* no-op probe */ };
			GlobalObservers.addObserver(observer);
			try {
				assertTrue(GlobalObservers.hasObservers(),
						"after adding an observer → hasObservers() must be true");
			} finally {
				GlobalObservers.removeObserver(observer);
			}
			assertFalse(GlobalObservers.hasObservers(),
					"after removing the observer → hasObservers() must be false again");
		}
	}
}
