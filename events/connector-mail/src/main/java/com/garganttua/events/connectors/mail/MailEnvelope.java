package com.garganttua.events.connectors.mail;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Self-describing mail envelope carried in the message payload, letting each event drive its own
 * recipient / subject / body / attachments instead of being pinned to the connector configuration.
 *
 * <p>The expected JSON shape is:</p>
 * <pre>{ "to": "...", "from": "...", "subject": "...", "contentType": "text/html", "body": "...",
 *   "attachments": [ { "filename": "a.pdf", "contentType": "application/pdf", "content": "&lt;base64&gt;" } ] }</pre>
 *
 * <p>Any field may be omitted — {@link MailSender} falls back to the connector config for absent
 * fields. The {@code attachments} field is optional and backwards compatible: a payload without it
 * parses with an empty (never {@code null}) list. A payload that is not a JSON object is treated as a
 * plain-text body (backwards compatibility), in which case {@link #tryParse} returns
 * {@link Optional#empty()}.</p>
 *
 * @param to          the recipient address (may be {@code null} to fall back to config)
 * @param from        the sender address (may be {@code null})
 * @param subject     the subject (may be {@code null})
 * @param contentType the MIME content type, e.g. {@code text/html} (may be {@code null})
 * @param body        the message body (may be {@code null})
 * @param attachments the attachments ("pièces jointes"); {@code null} is normalised to an empty,
 *                    unmodifiable list, so this accessor never returns {@code null}
 */
public record MailEnvelope(String to, String from, String subject, String contentType, String body,
		List<MailAttachment> attachments) {

	/**
	 * Canonical constructor; null-normalises {@code attachments} to an empty list and stores an
	 * immutable defensive copy so the record stays immutable.
	 */
	public MailEnvelope {
		attachments = attachments == null ? List.of() : List.copyOf(attachments);
	}

	/**
	 * Returns the attachments as an unmodifiable list (never {@code null}). A fresh defensive copy is
	 * returned so callers cannot reach the stored list.
	 *
	 * @return the attachments ("pièces jointes"), possibly empty
	 */
	@Override
	public List<MailAttachment> attachments() {
		return List.copyOf(attachments);
	}

	/**
	 * Attempts to read a mail envelope from the payload. Only a payload whose first non-whitespace
	 * byte is <code>{</code> is parsed as JSON; anything else (plain text, empty) yields
	 * {@link Optional#empty()} so the caller can treat the payload as a raw body. Parsing never
	 * throws — malformed JSON also yields {@link Optional#empty()}.
	 *
	 * @param value  the raw message payload
	 * @param mapper a lenient {@link ObjectMapper} (unknown properties ignored)
	 * @return the parsed envelope, or empty when the payload is not a JSON object
	 */
	public static Optional<MailEnvelope> tryParse(byte[] value, ObjectMapper mapper) {
		if (value == null) {
			return Optional.empty();
		}
		int index = 0;
		while (index < value.length && Character.isWhitespace(value[index])) {
			index++;
		}
		if (index >= value.length || value[index] != '{') {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(mapper.readValue(value, MailEnvelope.class));
		} catch (IOException e) {
			return Optional.empty();
		}
	}
}
