package com.garganttua.events.connectors.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.exceptions.ConnectorException;

@DisplayName("MailConnector hot reconfiguration")
class MailConnectorReconfigureTest {

	private static final byte[] RAW = "hello".getBytes(StandardCharsets.UTF_8);

	private MailConnector connector;
	private MailSender senderCreatedBefore;

	@BeforeEach
	void setUp() {
		connector = new MailConnector();
		Map<String, String> config = new HashMap<>();
		config.put("name", "mail");
		config.put("host", "smtp-old.example");
		config.put("port", "25");
		config.put("auth", "true");
		config.put("username", "old-user");
		config.put("password", "old-password");
		config.put("from", "old@example.org");
		config.put("to", "admin@example.org");
		connector.configure(config, new ConnectorContext("asset", "tenant", "cluster"));
		senderCreatedBefore = ((MailProducer) connector.createProducer(null, null)).sender();
	}

	@Test
	@DisplayName("a producer created BEFORE the reconfiguration uses the new settings at its next mail")
	void existingProducerSeesNewSettings() throws Exception {
		connector.reconfigure(Map.of("host", "smtp-new.example", "from", "new@example.org",
				"password", "new-password"));

		MailSettings next = senderCreatedBefore.currentSettings();
		assertEquals("smtp-new.example", next.smtpProperties().get("mail.smtp.host"));
		assertEquals("new-password", next.password());
		assertEquals("new@example.org", senderCreatedBefore.resolve(RAW).from());
	}

	@Test
	@DisplayName("absent keys are kept, a key given empty is cleared")
	void mergeSemantics() throws Exception {
		connector.reconfigure(Map.of("to", ""));

		MailSettings next = senderCreatedBefore.currentSettings();
		assertEquals("25", next.smtpProperties().get("mail.smtp.port"));
		assertEquals("old-user", next.username());
		assertNull(senderCreatedBefore.resolve(RAW).to());
	}

	@Test
	@DisplayName("an invalid configuration is refused and the current one stays in force")
	void invalidRefusedCurrentKept() {
		assertThrows(ConnectorException.class, () -> connector.reconfigure(Map.of("host", "x", "port", "abc")));
		assertThrows(ConnectorException.class, () -> connector.reconfigure(Map.of("port", "70000")));
		assertThrows(ConnectorException.class, () -> connector.reconfigure(null));

		MailSettings current = senderCreatedBefore.currentSettings();
		assertEquals("smtp-old.example", current.smtpProperties().get("mail.smtp.host"));
		assertEquals("25", current.smtpProperties().get("mail.smtp.port"));
	}

	@Test
	@DisplayName("a snapshot is immutable: its SMTP properties cannot be altered through a copy")
	void snapshotImmutable() {
		MailSettings current = senderCreatedBefore.currentSettings();
		current.smtpProperties().put("mail.smtp.host", "tampered");
		assertEquals("smtp-old.example", senderCreatedBefore.currentSettings().smtpProperties().get("mail.smtp.host"));
	}
}
