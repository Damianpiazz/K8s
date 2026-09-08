package com.ecommerce.notification.model;

/**
 * Notification categories — mirrors the event types emitted by the platform.
 */
public enum NotificationType {
    ORDER_CONFIRMED,
    PAYMENT_RECEIVED,
    PAYMENT_DECLINED,
    SHIPPED,
    DELIVERED,
    PROMOTIONAL,
    SYSTEM
}