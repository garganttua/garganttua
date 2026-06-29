package com.garganttua.events.connectors.mail;

/**
 * A single mail attachment ("pièce jointe") carried inside a {@link MailEnvelope}. The raw bytes are
 * transported as a Base64 string so the attachment stays JSON-friendly inside the message payload.
 *
 * <p>The expected JSON shape (inside the envelope's {@code attachments} array) is:</p>
 * <pre>{ "filename": "report.pdf", "contentType": "application/pdf", "content": "&lt;base64&gt;" }</pre>
 *
 * @param filename    the file name of the attachment part (e.g. {@code report.pdf}); a part with a
 *                    blank file name is skipped by {@link MailSender}
 * @param contentType the MIME type of the attachment, e.g. {@code application/pdf}; when blank,
 *                    {@link MailSender} defaults it to {@code application/octet-stream}
 * @param content     the Base64 encoding of the attachment's raw bytes; content that is not valid
 *                    Base64 causes the attachment to be skipped rather than failing the mail
 */
public record MailAttachment(String filename, String contentType, String content) {
}
