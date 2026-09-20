package com.maipiao.order.entity;

import java.util.Set;

/**
 * 订单状态机。
 *
 * <pre>
 *   0 PENDING_PAY --支付--------> 2 PAID --场次结束--> 3 COMPLETED
 *        |                          |
 *        | 超时 / 取消               | 申请退款
 *        v                          v
 *   4 CANCELLED                5 REFUNDING --退款成功--> 6 REFUNDED
 *                                   |
 *                                   | 退款失败（重试次数耗尽）
 *                                   v
 *                               2 PAID  （回到出发的地方）
 * </pre>
 *
 * <p>有三个状态是终态：COMPLETED、CANCELLED、REFUNDED。任何东西都不能离开它们，
 * 而下面这些守卫就是强制执行这件事的东西。
 *
 * <p>PAYING（1）存在的意义，是让「用户打开了支付页」和「用户还没开始支付」可以区分开。
 * 这对超时任务很重要 —— 停在 PAYING 的订单比停在 PENDING_PAY 的更接近已支付，
 * 而这两种情况任务在取消之前都会先去问一下支付渠道。
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    /** 已创建，还没有发起支付。 */
    public static final int PENDING_PAY = 0;
    /** 支付单已经存在，在等渠道。 */
    public static final int PAYING = 1;
    /** 已支付，票已出。 */
    public static final int PAID = 2;
    /** 场次已结束。 */
    public static final int COMPLETED = 3;
    /** 超时，或被用户取消。 */
    public static final int CANCELLED = 4;
    /** 已申请退款，在等渠道。 */
    public static final int REFUNDING = 5;
    /** 已退款。 */
    public static final int REFUNDED = 6;

    public static final Set<Integer> TERMINAL = Set.of(COMPLETED, CANCELLED, REFUNDED);

    /** 支付成功的回调可以作用在哪些状态上。 */
    public static final Set<Integer> PAYABLE = Set.of(PENDING_PAY, PAYING);

    /** 可以从哪些状态发起退款申请。 */
    public static final Set<Integer> REFUNDABLE = Set.of(PAID, COMPLETED);

    /** 超时任务可以取消哪些状态。 */
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
