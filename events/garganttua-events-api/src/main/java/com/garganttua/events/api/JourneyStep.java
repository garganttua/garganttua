package com.garganttua.events.api;

import java.util.Date;

import com.garganttua.events.api.enums.Direction;

public record JourneyStep(
		Date date,
		String assetId,
		String subscriptionId,
		Direction direction,
		String dataflowVersion,
		String uuid,
		String clusterId) {}
