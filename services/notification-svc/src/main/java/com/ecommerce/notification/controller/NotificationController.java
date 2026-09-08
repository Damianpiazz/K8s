package com.ecommerce.notification.controller;

import com.ecommerce.notification.model.Notification;
import com.ecommerce.notification.model.NotificationType;
import com.ecommerce.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Notification REST API. Mounted under /api/notifications so the api-gateway
 * forwards /api/notifications/** untouched.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * Request body: {"type": "ORDER_CONFIRMED", "recipient": "a@b.c",
     * "subject": "…", "body": "…"}.
     */
    public record NotificationRequest(String type, String recipient,
                                      String subject, String body) {
    }

    /** Creates a notification record (demo: no external dispatch). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Notification create(@RequestBody NotificationRequest request) {
        NotificationType type;
        try {
            type = NotificationType.valueOf(request.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown notification type: " + request.type());
        }
        return service.create(type, request.recipient(), request.subject(), request.body());
    }

    /** Fetch a single notification; 404 if unknown. */
    @GetMapping("/{id}")
    public Notification get(@PathVariable long id) {
        return service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification " + id + " not found"));
    }

    /** List all notifications (demo store visibility). */
    @GetMapping
    public List<Notification> list() {
        return service.findAll();
    }
}