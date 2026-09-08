package com.ecommerce.payment.service;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payment processing rules + in-memory store.
 *
 * <p>Approval rule (demo): amount ≤ 0 → DECLINED; a card whose placeholder
 * last-4 digits end in {@code 0000} → DECLINED; everything else → APPROVED.
 * In a real system the credentials would be tokenized by a PSP (Stripe/Adyen)
 * and the decision made there — this rule is a stand-in to demo the flow.
 *
 * <p>Production store: Azure Postgres. Swap this class for a
 * {@code PaymentRepository} (JPA) backed implementation — the REST contract
 * stays identical. The JPA/H2 stack is already on the classpath.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final ConcurrentHashMap<Long, Payment> payments = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    public Payment process(long orderId, BigDecimal amount, String method, String cardLast4) {
        PaymentStatus status = decide(amount, cardLast4);
        Payment payment = new Payment(ids.getAndIncrement(), orderId,
                amount, method != null ? method : "card", status);
        payments.put(payment.getId(), payment);
        log.info("Payment {} for order {} {} ({})",
                payment.getId(), orderId, status, amount);
        return payment;
    }

    private PaymentStatus decide(BigDecimal amount, String cardLast4) {
        if (amount == null || amount.signum() <= 0) {
            return PaymentStatus.DECLINED;
        }
        if (cardLast4 != null && cardLast4.endsWith("0000")) {
            return PaymentStatus.DECLINED;
        }
        return PaymentStatus.APPROVED;
    }

    public Optional<Payment> findById(long id) {
        return Optional.ofNullable(payments.get(id));
    }

    public List<Payment> findByOrder(long orderId) {
        return payments.values().stream()
                .filter(p -> p.getOrderId() == orderId)
                .toList();
    }

    public List<Payment> findAll() {
        return List.copyOf(payments.values());
    }
}