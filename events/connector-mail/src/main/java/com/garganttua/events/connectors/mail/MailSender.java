package com.garganttua.events.connectors.mail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.core.observability.Logger;

import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Sends a mail per message. The recipient, sender, subject, content type, body and attachments are
 * resolved from a per-message {@link MailEnvelope} (JSON payload) when present, falling back
 * field-by-field to the connector configuration; a non-JSON payload is treated as a plain-text body.
 * SMTP transport parameters always come from the connector config.
 *
 * <p>When no recipient can be resolved (neither the envelope nor the config provides {@code to}),
 * the mail is skipped with a warning rather than failing the pipeline. A single malformed attachment
 * (invalid Base64 / blank file name) is likewise skipped with a warning and never fails the mail.</p>
 */
public class MailSender {

	private static final Logger log = Logger.getLogger(MailSender.class);

	private static final String DEFAULT_ATTACHMENT_TYPE = "application/octet-stream";

	private final Properties properties;
	private final String from;
	private final String to;
	private final String subject;
	private final String body;
	private final String username;
	private final String password;
	private final String contentType;
	private final ObjectMapper mapper;

	public MailSender(Properties properties, String from, String to, String subject,
			String body, String username, String password, String contentType) {
		// Defensive copy: avoid storing a caller-owned mutable Properties (EI_EXPOSE_REP2).
		this.properties = new Properties();
		if (properties != null) {
			this.properties.putAll(properties);
		}
		this.from = from;
		this.to = to;
		this.subject = subject;
		this.body = body;
		this.username = username;
		this.password = password;
		this.contentType = contentType;
		this.mapper = new ObjectMapper()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	public void send(byte[] value) throws MessagingException {
		ResolvedMail mail = resolve(value);
		if (isBlank(mail.to())) {
			log.warn("Mail not sent: no recipient ('to') in the message envelope or connector config");
			return;
		}

		Session session = Session.getInstance(properties, new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(username, password);
			}
		});

		Transport.send(buildMessage(session, mail));
	}

	/**
	 * Builds the {@link MimeMessage} for one resolved mail. When the mail carries no attachments the
	 * body is set directly ({@code setContent(body, contentType)}); otherwise a multipart message is
	 * built with the body as the first part and one part per attachment.
	 *
	 * @param session the mail session
	 * @param mail    the resolved mail
	 * @return the built MIME message, ready for {@link Transport#send}
	 * @throws MessagingException when the JavaMail API rejects the message structure
	 */
	MimeMessage buildMessage(Session session, ResolvedMail mail) throws MessagingException {
		MimeMessage message = new MimeMessage(session);
		message.setFrom(new InternetAddress(mail.from()));
		message.addRecipient(Message.RecipientType.TO, new InternetAddress(mail.to()));
		message.setSubject(mail.subject());
		if (mail.attachments().isEmpty()) {
			message.setContent(mail.body(), mail.contentType());
		} else {
			message.setContent(buildMultipart(mail));
		}
		return message;
	}

	/**
	 * Builds the multipart payload: the body part followed by one part per attachment. A malformed
	 * attachment is skipped (logged) so it never fails the whole mail.
	 *
	 * @param mail the resolved mail (with a non-empty attachment list)
	 * @return the assembled multipart
	 * @throws MessagingException when the body part cannot be created
	 */
	private MimeMultipart buildMultipart(ResolvedMail mail) throws MessagingException {
		MimeMultipart multipart = new MimeMultipart();
		MimeBodyPart bodyPart = new MimeBodyPart();
		bodyPart.setContent(mail.body(), mail.contentType());
		multipart.addBodyPart(bodyPart);
		for (MailAttachment attachment : mail.attachments()) {
			addAttachment(multipart, attachment);
		}
		return multipart;
	}

	/**
	 * Decodes one attachment and appends it as a part. Invalid Base64 or a blank file name is logged
	 * and skipped rather than propagated, mirroring the connector's non-throwing posture.
	 *
	 * @param multipart  the multipart being assembled
	 * @param attachment the attachment to add
	 */
	private void addAttachment(MimeMultipart multipart, MailAttachment attachment) {
		if (attachment == null || isBlank(attachment.filename())) {
			log.warn("Skipping attachment with no file name");
			return;
		}
		try {
			byte[] bytes = Base64.getDecoder().decode(
					attachment.content() == null ? "" : attachment.content());
			String type = firstNonBlank(attachment.contentType(), DEFAULT_ATTACHMENT_TYPE);
			MimeBodyPart part = new MimeBodyPart();
			part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, type)));
			part.setFileName(attachment.filename());
			part.setDisposition(Part.ATTACHMENT);
			multipart.addBodyPart(part);
		} catch (IllegalArgumentException | MessagingException e) {
			log.warn("Skipping malformed attachment '{}': {}", attachment.filename(), e.getMessage());
		}
	}

	/**
	 * Resolves the outgoing mail by merging the per-message envelope (when the payload is one) over
	 * the connector configuration. A non-envelope payload becomes the body (backwards compatible).
	 * Attachments are per-message only — they come from the envelope, with no connector-config
	 * fallback — and default to an empty list.
	 *
	 * @param value the raw message payload
	 * @return the resolved mail; its {@code to} may be {@code null} when none was configured
	 */
	ResolvedMail resolve(byte[] value) {
		Optional<MailEnvelope> envelope = MailEnvelope.tryParse(value, mapper);
		String effectiveTo = firstNonBlank(envelope.map(MailEnvelope::to).orElse(null), to);
		String effectiveFrom = firstNonBlank(envelope.map(MailEnvelope::from).orElse(null), from, username);
		String effectiveSubject = firstNonBlank(envelope.map(MailEnvelope::subject).orElse(null),
				subject, "Event notification");
		String effectiveType = firstNonBlank(envelope.map(MailEnvelope::contentType).orElse(null),
				contentType, "text/plain");
		String effectiveBody = envelope.isPresent()
				? firstNonBlank(envelope.get().body(), body, "")
				: (body != null ? body : new String(value, StandardCharsets.UTF_8));
		List<MailAttachment> attachments = envelope.map(MailEnvelope::attachments).orElse(List.of());
		return new ResolvedMail(effectiveFrom, effectiveTo, effectiveSubject, effectiveType,
				effectiveBody, attachments);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** The fully resolved fields of one outgoing mail, including its (possibly empty) attachments. */
	record ResolvedMail(String from, String to, String subject, String contentType, String body,
			List<MailAttachment> attachments) {

		/** Canonical constructor; null-normalises {@code attachments} to an empty immutable list. */
		ResolvedMail {
			attachments = attachments == null ? List.of() : List.copyOf(attachments);
		}
	}
}
