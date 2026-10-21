package com.garganttua.events.connectors.mail;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

import jakarta.mail.MessagingException;

public class MailProducer implements IProducer {

	private final MailSender mailSender;

	// The producer sends through the connector's live sender, which reads the current settings: it must not be copied.
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "Live MailSender shared with the connector must be retained by reference, not copied.")
	public MailProducer(MailSender sender) {
		this.mailSender = sender;
	}

	/** The sender behind this producer (package-private, for tests). */
	MailSender sender() {
		return this.mailSender;
	}

	@Override
	public void publish(byte[] value) throws ConnectorException {
		try {
			this.mailSender.send(value);
		} catch (MessagingException e) {
			throw new ConnectorException(e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		// Nothing to do
	}
}
