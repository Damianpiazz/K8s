package com.ecommerce.returns.model;

/**
 * Lifecycle of a return request (RMA).
 */
public enum ReturnStatus {
    /** Just created. */
    RETURN_REQUESTED,
    /** Approved automatically by the rule (or by an operator). */
    APPROVED,
    /** Rejected by an operator. */
    REJECTED,
    /** Does not qualify for auto-approval — needs a human decision. */
    PENDING_REVIEW
}