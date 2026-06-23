package com.garganttua.events.connectors.mail;

import java.util.Map;
import java.util.Properties;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.connectors.annotations.Connector;

@Connector(type = "mail")
@Reflected
public class MailConnector extends AbstractLifecycle implements IConnector {

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	private String name;

	@Override
	public String getName() {
		return this.name;
	}
	private String from;
	private String to;
	private String subject;
	private String body;
	private String username;
	private String password;
	private String contentType;
	private Properties smtpProperties;

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.name = configuration.getOrDefault("name", "mail");
		this.from = configuration.get("from");
		this.to = configuration.get("to");
		this.subject = configuration.get("object");
		this.body = configuration.get("body");
		this.username = configuration.get("username");
		this.password = configuration.get("password");
		this.contentType = configuration.get("contentType");

		this.smtpProperties = new Properties();
		smtpProperties.put("mail.smtp.auth", Boolean.parseBoolean(configuration.getOrDefault("auth", "false")));
		smtpProperties.put("mail.smtp.starttls.enable", configuration.getOrDefault("starttls", "false"));
		smtpProperties.put("mail.smtp.ssl.enable", configuration.getOrDefault("ssl", "false"));
		smtpProperties.put("mail.smtp.host", configuration.getOrDefault("host", "localhost"));
		smtpProperties.put("mail.smtp.port", configuration.getOrDefault("port", "587"));
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		// Mail connector does not support consumers
		throw new UnsupportedOperationException("Mail connector does not support consumers");
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		MailSender sender = new MailSender(smtpProperties, from, to, subject, body, username, password, contentType);
		return new MailProducer(sender);
	}

	@Override
	protected ILifecycle doInit() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStart() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doFlush() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		return this;
	}
}
