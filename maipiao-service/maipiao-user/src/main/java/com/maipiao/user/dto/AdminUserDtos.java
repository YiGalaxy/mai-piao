package com.maipiao.user.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 用户管理相关的结构。 */
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    /**
     * 用户列表的一行。
     *
     * <p>没有 password 字段，连打码的都没有。它是一段 bcrypt 哈希，
     * 这个界面上没有任何东西能拿它做出有用的事，
     * 所以要让它不泄露，最稳妥的办法就是从来不把它查出来。
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

    /** 一页用户，带上总数，好让客户端分页。 */
    public record UserPage(List<UserRow> rows, long total, int page, int size) {
    }

    /**
     * 一个用户的完整信息。
     *
     * <p>{@code orderCount} 和 {@code paidAmount} 是客户打电话来时管理员真正想要的两个数字：
     * 这个人买过东西没有，花了多少。它们靠问 order-service 填上，
     * 而问不到的时候它们是 0 而不是致命错误 ——
     * 哪怕关于某个用户的一个数字拿不到，这条用户记录也值得显示。
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
