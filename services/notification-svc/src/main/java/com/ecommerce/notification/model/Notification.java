package com.ecommerce.notification.model;

import java.time.Instant;

/**
 * A stored notification.
 *
 * <p>Demo store: in-memory map keyed by incremental id (see NotificationService).
 * Production path: persist and dispatch through the provider of choice (e.g.
 * Azure Communication Services / SendGrid), with the store on Postgres.
 */
public class Notification {

    private final long id;
    private final NotificationType type;
    private final String recipient;
    private final String subject;
    private final String body;
    private final Instant createdAt;

    public Notification(long id, NotificationType type, String recipient,
                        String subject, String body) {
        this.id = id;
        this.type = type;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public long getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}