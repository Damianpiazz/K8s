package com.ecommerce.returns.service;

import com.ecommerce.returns.model.ReturnRequest;
import com.ecommerce.returns.model.ReturnStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory RMA store (cart-svc style).
 *
 * <p>Visible auto-approval rule — documented in the README:
 * <pre>
 *   APPROVED when all of:
 *     - reason ∈ returns.auto-approvable-reasons
 *     - createdAt within returns.return-window-days
 *     - quantity ≤ returns.max-quantity-auto-approve
 *   else PENDING_REVIEW
 * </pre>
 * Operators then approve/reject explicitly.
 */
@Service
public class ReturnsService {

    private final ConcurrentHashMap<Long, ReturnRequest> returns = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private final List<String> autoApprovableReasons;
    private final int returnWindowDays;
    private final int maxQuantityAutoApprove;

    public ReturnsService(
            @Value("${returns.auto-approvable-reasons:defective,wrong-item,not-as-described}")
            List<String> autoApprovableReasons,
            @Value("${returns.return-window-days:30}") int returnWindowDays,
            @Value("${returns.max-quantity-auto-approve:10}") int maxQuantityAutoApprove) {
        this.autoApprovableReasons = autoApprovableReasons;
        this.returnWindowDays = returnWindowDays;
        this.maxQuantityAutoApprove = maxQuantityAutoApprove;
    }

    /** Creates an RMA, applying the auto-approval rule. */
    public ReturnRequest create(String orderId, long productId, String reason, int quantity) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        long id = ids.getAndIncrement();
        Instant now = Instant.now();
        ReturnStatus status = autoApprovable(reason, now, quantity)
                ? ReturnStatus.APPROVED
                : ReturnStatus.PENDING_REVIEW;
        ReturnRequest request = new ReturnRequest(id, orderId, productId, reason,
                quantity, now, status);
        returns.put(id, request);
        return request;
    }

    public Optional<ReturnRequest> findById(long id) {
        return Optional.ofNullable(returns.get(id));
    }

    /** All RMAs of one order (newest first). */
    public List<ReturnRequest> findByOrderId(String orderId) {
        return returns.values().stream()
                .filter(r -> r.getOrderId().equals(orderId))
                .sorted(java.util.Comparator.comparingLong(ReturnRequest::getId).reversed())
                .toList();
    }

    /** Operator approval (idempotent for APPROVED; 409 for REJECTED). */
    public ReturnRequest approve(long id) {
        ReturnRequest request = returns.get(id);
        if (request == null) {
            throw new NoSuchElementException("Return " + id + " not found");
        }
        if (request.getStatus() == ReturnStatus.REJECTED) {
            throw new IllegalStateException("Return " + id + " is already rejected");
        }
        request.setStatus(ReturnStatus.APPROVED);
        return request;
    }

    /** Operator rejection (idempotent for REJECTED; 409 for APPROVED). */
    public ReturnRequest reject(long id) {
        ReturnRequest request = returns.get(id);
        if (request == null) {
            throw new NoSuchElementException("Return " + id + " not found");
        }
        if (request.getStatus() == ReturnStatus.APPROVED) {
            throw new IllegalStateException("Return " + id + " is already approved");
        }
        request.setStatus(ReturnStatus.REJECTED);
        return request;
    }

    private boolean autoApprovable(String reason, Instant createdAt, int quantity) {
        boolean knownReason = autoApprovableReasons.stream()
                .map(String::trim)
                .anyMatch(r -> r.equalsIgnoreCase(reason.trim()));
        boolean inWindow = Duration.between(createdAt, Instant.now())
                .toDays() <= returnWindowDays;
        return knownReason && inWindow && quantity <= maxQuantityAutoApprove;
    }
}