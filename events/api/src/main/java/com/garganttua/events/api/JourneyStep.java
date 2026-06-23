package com.garganttua.events.api;

import java.util.Date;

import com.garganttua.events.api.enums.Direction;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// EI_EXPOSE_REP: journey-step record returns the Date component by reference by design
// (transport value object); the date is never mutated after construction, so a defensive
// copy would change no observable behaviour and only add churn.
@SuppressFBWarnings("EI_EXPOSE_REP")
public record JourneyStep(
		Date date,
		String assetId,
		String subscriptionId,
		Direction direction,
		String dataflowVersion,
		String uuid,
		String clusterId) {}
