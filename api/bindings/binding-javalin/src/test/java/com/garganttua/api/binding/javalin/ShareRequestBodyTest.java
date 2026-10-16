package com.garganttua.api.binding.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.binding.jackson.JacksonJsonSerializer;
import com.garganttua.api.core.entity.ShareRequest;
import com.garganttua.core.reflection.IClass;

/**
 * The body of {@code PUT /<domain>/{uuid}/shares} as the JSON serializer reads it: the DESERIALIZE
 * stage hands the share script a {@link ShareRequest}, never the domain entity.
 */
@DisplayName("share body — JSON to ShareRequest")
class ShareRequestBodyTest {

	@BeforeAll
	static void installReflection() {
		JavalinInterfaceTest.installReflection();
	}

	@Test
	@DisplayName("a grants array reads into a ShareRequest, order kept")
	void readsGrants() throws Exception {
		ShareRequest request = new JacksonJsonSerializer().deserialize(
				"{\"grants\":[\"r:tenant:B\",\"w:owner:users:dave\"]}".getBytes(StandardCharsets.UTF_8),
				IClass.getClass(ShareRequest.class));
		assertEquals(List.of("r:tenant:B", "w:owner:users:dave"), request.grants());
	}

	@Test
	@DisplayName("an absent grants array reads as no grant: sharing stops")
	void absentGrants() throws Exception {
		ShareRequest request = new JacksonJsonSerializer().deserialize("{}".getBytes(StandardCharsets.UTF_8),
				IClass.getClass(ShareRequest.class));
		assertEquals(List.of(), request.grants());
	}
}
