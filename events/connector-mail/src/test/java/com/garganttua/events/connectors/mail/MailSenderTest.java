package com.garganttua.events.connectors.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.events.connectors.mail.MailSender.ResolvedMail;

import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

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

	private static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] readPart(BodyPart part) throws Exception {
		try (InputStream in = part.getDataHandler().getDataSource().getInputStream()) {
			return in.readAllBytes();
		}
	}

	@Nested
	@DisplayName("buildMessage attachments")
	class BuildMessageAttachments {

		private final Session session = Session.getInstance(new Properties());

		private final MailSender sender = withConfig("from@x", "to@x", "s", "b", "text/plain");

		@Test
		@DisplayName("two attachments produce a 3-part multipart (body + 2), bytes round-trip")
		void twoAttachmentsBuildMultipart() throws Exception {
			ResolvedMail mail = new ResolvedMail("from@x", "to@x", "Subject", "text/plain",
					"the body", List.of(
							new MailAttachment("a.txt", "text/plain", base64("hello")),
							new MailAttachment("b.pdf", "application/pdf", base64("world"))));

			MimeMessage message = sender.buildMessage(session, mail);

			Object content = message.getContent();
			MimeMultipart multipart = assertInstanceOf(MimeMultipart.class, content);
			assertEquals(3, multipart.getCount(), "1 body part + 2 attachments");

			MimeBodyPart bodyPart = (MimeBodyPart) multipart.getBodyPart(0);
			assertEquals("the body", bodyPart.getContent());

			BodyPart a = multipart.getBodyPart(1);
			assertEquals("a.txt", a.getFileName());
			assertEquals(Part.ATTACHMENT, a.getDisposition());
			assertEquals("hello", new String(readPart(a), StandardCharsets.UTF_8));

			BodyPart b = multipart.getBodyPart(2);
			assertEquals("b.pdf", b.getFileName());
			assertEquals(Part.ATTACHMENT, b.getDisposition());
			assertEquals("world", new String(readPart(b), StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("no attachments builds a simple non-multipart body (backwards compatible)")
		void noAttachmentsBuildsSimpleBody() throws Exception {
			ResolvedMail mail = new ResolvedMail("from@x", "to@x", "Subject", "text/plain",
					"plain body", List.of());

			MimeMessage message = sender.buildMessage(session, mail);

			Object content = message.getContent();
			assertTrue(content instanceof String, "expected a plain String body, not a multipart");
			assertEquals("plain body", content);
		}

		@Test
		@DisplayName("an invalid-base64 attachment is skipped while a valid one is still attached")
		void badAttachmentSkippedValidKept() throws Exception {
			ResolvedMail mail = new ResolvedMail("from@x", "to@x", "Subject", "text/plain",
					"body", List.of(
							new MailAttachment("bad.bin", "application/octet-stream", "!!!not base64!!!"),
							new MailAttachment("good.txt", "text/plain", base64("ok"))));

			MimeMessage message = sender.buildMessage(session, mail);

			MimeMultipart multipart = assertInstanceOf(MimeMultipart.class, message.getContent());
			assertEquals(2, multipart.getCount(), "1 body part + only the valid attachment");

			BodyPart good = multipart.getBodyPart(1);
			assertEquals("good.txt", good.getFileName());
			assertEquals("ok", new String(readPart(good), StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("a blank-filename attachment is skipped")
		void blankFilenameSkipped() throws Exception {
			ResolvedMail mail = new ResolvedMail("from@x", "to@x", "Subject", "text/plain",
					"body", List.of(
							new MailAttachment("  ", "text/plain", base64("x")),
							new MailAttachment("kept.txt", "text/plain", base64("y"))));

			MimeMultipart multipart = assertInstanceOf(MimeMultipart.class,
					sender.buildMessage(session, mail).getContent());
			assertEquals(2, multipart.getCount());
			assertEquals("kept.txt", multipart.getBodyPart(1).getFileName());
		}
	}

	@Nested
	@DisplayName("MailEnvelope.tryParse attachments")
	class EnvelopeAttachments {

		private final ObjectMapper mapper = new ObjectMapper()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		@Test
		@DisplayName("an attachments array parses into the record")
		void attachmentsArrayParses() {
			MailEnvelope envelope = MailEnvelope.tryParse(bytes("{\"to\":\"a@x\",\"body\":\"hi\","
					+ "\"attachments\":[{\"filename\":\"a.pdf\",\"contentType\":\"application/pdf\","
					+ "\"content\":\"" + base64("data") + "\"}]}"), mapper).orElseThrow();

			assertEquals(1, envelope.attachments().size());
			MailAttachment attachment = envelope.attachments().get(0);
			assertEquals("a.pdf", attachment.filename());
			assertEquals("application/pdf", attachment.contentType());
			assertEquals(base64("data"), attachment.content());
		}

		@Test
		@DisplayName("an envelope without attachments parses with an empty (non-null) list")
		void noAttachmentsIsEmptyList() {
			MailEnvelope envelope = MailEnvelope.tryParse(
					bytes("{\"to\":\"a@x\",\"body\":\"hi\"}"), mapper).orElseThrow();

			assertTrue(envelope.attachments().isEmpty());
		}
	}
}
