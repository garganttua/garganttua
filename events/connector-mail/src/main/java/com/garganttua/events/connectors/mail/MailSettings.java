package com.garganttua.events.connectors.mail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * An immutable snapshot of the mail connector's configuration: the SMTP transport properties, the credentials and
 * the per-message fallbacks (from, to, subject, body, content type).
 *
 * <p>The connector holds exactly one current snapshot and swaps it atomically on reconfiguration; a
 * {@link MailSender} reads it once per message, so a mail being sent finishes with the snapshot it started with.</p>
 */
record MailSettings(Map<String, String> raw, Properties smtpProperties, String from, String to, String subject,
		String body, String username, String password, String contentType) {

	/** Copies what it is given: the snapshot shares no mutable object with its caller. */
	MailSettings {
		raw = Collections.unmodifiableMap(new HashMap<>(raw));
		Properties properties = new Properties();
		if (smtpProperties != null) {
			properties.putAll(smtpProperties);
		}
		smtpProperties = properties;
	}

	private static MailSettings parse(Map<String, String> configuration) {
		Properties properties = new Properties();
		properties.put("mail.smtp.auth", Boolean.parseBoolean(configuration.getOrDefault("auth", "false")));
		properties.put("mail.smtp.starttls.enable", configuration.getOrDefault("starttls", "false"));
		properties.put("mail.smtp.ssl.enable", configuration.getOrDefault("ssl", "false"));
		properties.put("mail.smtp.host", configuration.getOrDefault("host", "localhost"));
		properties.put("mail.smtp.port", configuration.getOrDefault("port", "587"));
		return new MailSettings(configuration, properties, configuration.get("from"), configuration.get("to"),
				configuration.get("object"), configuration.get("body"), configuration.get("username"),
				configuration.get("password"), configuration.get("contentType"));
	}

	/** The snapshot of a full configuration map — the keys {@code configure} has always read. */
	static MailSettings of(Map<String, String> configuration) {
		return parse(configuration == null ? Map.of() : configuration);
	}

	/**
	 * The snapshot of this configuration with {@code changes} merged in: present keys replace, absent keys are kept,
	 * an empty value clears.
	 *
	 * @throws IllegalArgumentException when the merged port is not a number in 1..65535
	 */
	MailSettings merge(Map<String, String> changes) {
		Map<String, String> merged = new HashMap<>(this.raw);
		changes.forEach((key, value) -> {
			if (value == null || value.isEmpty()) {
				merged.remove(key);
			} else {
				merged.put(key, value);
			}
		});
		String port = merged.get("port");
		if (port != null) {
			int value;
			try {
				value = Integer.parseInt(port.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("port is not a number: " + port, e);
			}
			if (value < 1 || value > 65535) {
				throw new IllegalArgumentException("port out of range: " + port);
			}
		}
		return parse(merged);
	}

	/** A copy of the SMTP properties — a session must never share a mutable object with the snapshot. */
	@Override
	public Properties smtpProperties() {
		Properties copy = new Properties();
		copy.putAll(this.smtpProperties);
		return copy;
	}
}
