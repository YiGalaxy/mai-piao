package com.maipiao.order.entity;

import java.util.Set;

/**
 * The order state machine.
 *
 * <pre>
 *   0 PENDING_PAY --pay--------> 2 PAID --show ends--> 3 COMPLETED
 *        |                          |
 *        | timeout / cancel         | refund requested
 *        v                          v
 *   4 CANCELLED                5 REFUNDING --refund ok--> 6 REFUNDED
 *                                   |
 *                                   | refund fails (retries exhausted)
 *                                   v
 *                               2 PAID  (back where it started)
 * </pre>
 *
 * <p>Three states are terminal: COMPLETED, CANCELLED, REFUNDED. Nothing may
 * leave them, and the guards below are what enforce that.
 *
 * <p>PAYING (1) exists so that "the user opened the payment page" is
 * distinguishable from "the user has not started paying". It matters for the
 * timeout job - an order sitting in PAYING is closer to being paid than one in
 * PENDING_PAY, and the job checks with the payment channel before cancelling
 * either.
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    /** Created, no payment initiated. */
    public static final int PENDING_PAY = 0;
    /** A payment order exists; waiting on the channel. */
    public static final int PAYING = 1;
    /** Paid, tickets issued. */
    public static final int PAID = 2;
    /** Screening finished. */
    public static final int COMPLETED = 3;
    /** Timed out or cancelled by the user. */
    public static final int CANCELLED = 4;
    /** Refund requested, waiting on the channel. */
    public static final int REFUNDING = 5;
    /** Refunded. */
    public static final int REFUNDED = 6;

    public static final Set<Integer> TERMINAL = Set.of(COMPLETED, CANCELLED, REFUNDED);

    /** Statuses a successful payment callback may act on. */
    public static final Set<Integer> PAYABLE = Set.of(PENDING_PAY, PAYING);

    /** Statuses from which a refund may be requested. */
    public static final Set<Integer> REFUNDABLE = Set.of(PAID, COMPLETED);

    /** Statuses the timeout job may cancel. */
    public static final Set<Integer> CANCELLABLE = Set.of(PENDING_PAY);

    public static boolean isTerminal(int status) {
        return TERMINAL.contains(status);
    }

    public static String name(int status) {
        return switch (status) {
            case PENDING_PAY -> "待支付";
            case PAYING -> "支付中";
            case PAID -> "已支付";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case REFUNDING -> "退款中";
            case REFUNDED -> "已退款";
            default -> "未知";
        };
    }
}
