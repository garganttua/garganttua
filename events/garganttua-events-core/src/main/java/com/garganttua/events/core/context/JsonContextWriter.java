package com.garganttua.events.core.context;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.exceptions.EventsException;

public class JsonContextWriter {

	private static final ObjectMapper mapper = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT);

	public static String toJson(ContextDef context) throws EventsException {
		try {
			return mapper.writeValueAsString(context);
		} catch (IOException e) {
			throw new EventsException("Failed to serialize context to JSON", e);
		}
	}

	public static void toFile(ContextDef context, String path) throws EventsException {
		try {
			mapper.writeValue(new File(path), context);
		} catch (IOException e) {
			throw new EventsException("Failed to write context to file: " + path, e);
		}
	}
}
