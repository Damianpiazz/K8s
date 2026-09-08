package com.ecommerce.notification.service;

import com.ecommerce.notification.model.Notification;
import com.ecommerce.notification.model.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory notification store + creation.
 *
 * <p>Production path: persist to Postgres (JPA) and dispatch through Azure
 * Communication Services / SendGrid. The REST contract stays identical.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ConcurrentHashMap<Long, Notification> notifications = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    public Notification create(NotificationType type, String recipient,
                               String subject, String body) {
        Notification notification = new Notification(ids.getAndIncrement(),
                type, recipient, subject, body);
        notifications.put(notification.getId(), notification);
        log.info("Notification {} ({}) queued for {}", notification.getId(),
                type, recipient);
        return notification;
    }

    public Optional<Notification> findById(long id) {
        return Optional.ofNullable(notifications.get(id));
    }

    public List<Notification> findAll() {
        return List.copyOf(notifications.values());
    }
}