package com.garganttua.events.connectors.mail;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

import jakarta.mail.MessagingException;

public class MailProducer implements IProducer {

	private final MailSender sender;

	public MailProducer(MailSender sender) {
		this.sender = sender;
	}

	@Override
	public void publish(byte[] value) throws ConnectorException {
		try {
			sender.send(value);
		} catch (MessagingException e) {
			throw new ConnectorException(e);
		}
	}

	@Override
	public void stop() throws ConnectorException {
		// Nothing to do
	}
}
