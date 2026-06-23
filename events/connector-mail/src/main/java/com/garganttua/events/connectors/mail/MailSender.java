package com.garganttua.events.connectors.mail;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.core.observability.Logger;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends a mail per message. The recipient, sender, subject, content type and body are resolved from
 * a per-message {@link MailEnvelope} (JSON payload) when present, falling back field-by-field to the
 * connector configuration; a non-JSON payload is treated as a plain-text body. SMTP transport
 * parameters always come from the connector config.
 *
 * <p>When no recipient can be resolved (neither the envelope nor the config provides {@code to}),
 * the mail is skipped with a warning rather than failing the pipeline.</p>
 */
public class MailSender {

	private static final Logger log = Logger.getLogger(MailSender.class);

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

		MimeMessage message = new MimeMessage(session);
		message.setFrom(new InternetAddress(mail.from()));
		message.addRecipient(Message.RecipientType.TO, new InternetAddress(mail.to()));
		message.setSubject(mail.subject());
		message.setContent(mail.body(), mail.contentType());
		Transport.send(message);
	}

	/**
	 * Resolves the outgoing mail by merging the per-message envelope (when the payload is one) over
	 * the connector configuration. A non-envelope payload becomes the body (backwards compatible).
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
		return new ResolvedMail(effectiveFrom, effectiveTo, effectiveSubject, effectiveType, effectiveBody);
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

	/** The fully resolved fields of one outgoing mail. */
	record ResolvedMail(String from, String to, String subject, String contentType, String body) {}
}
