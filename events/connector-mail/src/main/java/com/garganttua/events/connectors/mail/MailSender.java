package com.garganttua.events.connectors.mail;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class MailSender {

	private final Properties properties;
	private final String from;
	private final String to;
	private final String subject;
	private final String body;
	private final String username;
	private final String password;
	private final String contentType;

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
	}

	public void send(byte[] value) throws MessagingException {
		Session session = Session.getInstance(properties, new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(username, password);
			}
		});

		MimeMessage message = new MimeMessage(session);
		message.setFrom(new InternetAddress(from != null ? from : username));
		message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
		message.setSubject(subject != null ? subject : "Event notification");
		String ct = contentType != null ? contentType : "text/plain";
		String bodyContent = body != null ? body : new String(value, StandardCharsets.UTF_8);
		message.setContent(bodyContent, ct);
		Transport.send(message);
	}
}
