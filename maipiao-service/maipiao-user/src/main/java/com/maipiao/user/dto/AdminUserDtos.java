package com.maipiao.user.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Shapes for user administration. */
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    /**
     * One row of the user list.
     *
     * <p>No password field, not even a masked one. It is a bcrypt hash and
     * nothing on this screen could do anything useful with it, so the surest
     * way for it not to leak is for it never to be selected.
     */
    public record UserRow(
            Long id,
            String phone,
            String nickname,
            Integer status,
            String role,
            LocalDateTime createTime
    ) {
    }

    /** A page of users, with the total so the client can paginate. */
    public record UserPage(List<UserRow> rows, long total, int page, int size) {
    }

    /**
     * One user, in full.
     *
     * <p>{@code orderCount} and {@code paidAmount} are the two numbers an
     * administrator actually wants when a customer calls: has this person
     * bought anything, and for how much. They are filled by asking
     * order-service, and are zero rather than fatal when it cannot be reached
     * - a user record is worth showing even when one number about it is not
     * available.
     */
    public record UserDetail(
            Long id,
            String phone,
            String nickname,
            String avatar,
            Integer status,
            String role,
            LocalDateTime createTime,
            int orderCount,
            java.math.BigDecimal paidAmount
    ) {
    }
}
