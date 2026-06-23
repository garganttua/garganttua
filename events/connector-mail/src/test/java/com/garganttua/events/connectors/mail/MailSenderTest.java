package com.garganttua.events.connectors.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.events.connectors.mail.MailSender.ResolvedMail;

/**
 * Behaviour of {@link MailSender#resolve(byte[])}: a per-message JSON envelope drives the mail and
 * the connector config fills any field it omits; a non-JSON payload stays the plain-text body.
 */
class MailSenderTest {

	private static MailSender withConfig(String from, String to, String subject, String body,
			String contentType) {
		return new MailSender(new Properties(), from, to, subject, body, "user@smtp", "pwd", contentType);
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@Nested
	@DisplayName("envelope payload")
	class EnvelopePayload {

		@Test
		@DisplayName("a full envelope drives every field, overriding the config")
		void fullEnvelopeWins() {
			MailSender sender = withConfig("config-from", "config-to", "config-subject",
					"config-body", "text/plain");
			ResolvedMail mail = sender.resolve(bytes("{\"to\":\"alice@example.com\","
					+ "\"from\":\"bob@example.com\",\"subject\":\"Hi\","
					+ "\"contentType\":\"text/html\",\"body\":\"<b>hello</b>\"}"));

			assertEquals("alice@example.com", mail.to());
			assertEquals("bob@example.com", mail.from());
			assertEquals("Hi", mail.subject());
			assertEquals("text/html", mail.contentType());
			assertEquals("<b>hello</b>", mail.body());
		}

		@Test
		@DisplayName("omitted envelope fields fall back to the connector config")
		void partialEnvelopeFallsBackToConfig() {
			MailSender sender = withConfig("config-from", "config-to", "config-subject",
					"config-body", "text/plain");
			ResolvedMail mail = sender.resolve(bytes("{\"to\":\"alice@example.com\",\"body\":\"ping\"}"));

			assertEquals("alice@example.com", mail.to());
			assertEquals("config-from", mail.from());
			assertEquals("config-subject", mail.subject());
			assertEquals("text/plain", mail.contentType());
			assertEquals("ping", mail.body());
		}
	}

	@Nested
	@DisplayName("plain-text payload (backwards compatible)")
	class PlainTextPayload {

		@Test
		@DisplayName("the raw text becomes the body and the recipient comes from config")
		void rawTextIsBody() {
			MailSender sender = withConfig(null, "config-to", null, null, null);
			ResolvedMail mail = sender.resolve(bytes("just some text"));

			assertEquals("just some text", mail.body());
			assertEquals("config-to", mail.to());
			assertEquals("user@smtp", mail.from(), "from falls back to the SMTP username");
			assertEquals("Event notification", mail.subject(), "subject falls back to the default");
			assertEquals("text/plain", mail.contentType());
		}

		@Test
		@DisplayName("a configured body still overrides a plain-text payload")
		void configBodyOverridesText() {
			MailSender sender = withConfig(null, "config-to", null, "config-body", null);
			ResolvedMail mail = sender.resolve(bytes("ignored text"));

			assertEquals("config-body", mail.body());
		}
	}

	@Test
	@DisplayName("no recipient anywhere resolves to a null 'to' (the sender then skips the send)")
	void noRecipientResolvesToNull() {
		MailSender sender = withConfig(null, null, null, null, null);
		ResolvedMail mail = sender.resolve(bytes("body only, no recipient"));

		assertNull(mail.to());
	}
}
