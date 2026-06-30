package com.garganttua.events.api.context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Jackson deserializer that accepts either a single JSON string or a JSON array of strings and
 * always yields a {@code List<String>}.
 *
 * <p>This keeps {@link RouteDef#to()} backward compatible: a legacy context with
 * {@code "to":"subId"} parses to {@code List.of("subId")}, while a multi-destination context with
 * {@code "to":["a","b"]} parses to {@code ["a","b"]}. A {@code null} or absent value yields an empty
 * list.</p>
 *
 * <p>Annotated {@link Reflected} so native-image keeps the no-arg constructor: Jackson instantiates
 * this class reflectively when it resolves the {@code @JsonDeserialize(using = ...)} reference on
 * {@code RouteDef.to}.</p>
 */
@Reflected(queryAllDeclaredConstructors = true)
public class StringOrListDeserializer extends JsonDeserializer<List<String>> {

	@Override
	public List<String> deserialize(JsonParser parser, DeserializationContext context)
			throws IOException {
		List<String> result = new ArrayList<>();
		JsonToken token = parser.currentToken();
		if (token == JsonToken.VALUE_NULL) {
			return result;
		}
		if (token == JsonToken.START_ARRAY) {
			while (parser.nextToken() != JsonToken.END_ARRAY) {
				if (parser.currentToken() != JsonToken.VALUE_NULL) {
					result.add(parser.getValueAsString());
				}
			}
			return result;
		}
		// Single scalar (string) value.
		String single = parser.getValueAsString();
		if (single != null) {
			result.add(single);
		}
		return result;
	}

	@Override
	public List<String> getNullValue(DeserializationContext context) {
		return new ArrayList<>();
	}
}
