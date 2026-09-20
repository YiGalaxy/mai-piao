package com.maipiao.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.dto.AdminOrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.UserClient;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单查询，给接电话的那些人用。
 *
 * <p>这里每个查询长的都是一个样：有人打来电话，手里有一个手机号和一肚子抱怨，
 * 而订单号恰恰是他没有的。所以筛选条件要先从管理员手上真正握着的那个开始 ——
 * 先手机号，再状态，最后才是日期区间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAdminService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserClient userClient;

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 搜索订单。
     *
     * <p>{@code phone} 会先经由 user-service 换成一个用户 id。
     * 另一条路 —— 把手机号存在订单上 —— 在设计订单时就否决了：
     * 那等于在一个必须凝固的历史记录上，放一份会变的值的副本。
     */
    public AdminOrderDtos.OrderPage search(String orderNo, String phone, Long userId,
                                           Integer status, LocalDate from, LocalDate to,
                                           int page, int size) {

        Long effectiveUserId = userId;
        if (effectiveUserId == null && phone != null && !phone.isBlank()) {
            effectiveUserId = resolvePhone(phone.trim());
            if (effectiveUserId == null) {
                // 未知手机号不是错误，是一个空结果。
                // 回答「没有这个用户」，等于泄露了哪些号是存在的。
                return new AdminOrderDtos.OrderPage(List.of(), 0, page, size);
            }
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        var query = Wrappers.<Order>lambdaQuery().orderByDesc(Order::getCreateTime);
        if (orderNo != null && !orderNo.isBlank()) {
            query.likeRight(Order::getOrderNo, orderNo.trim());
        }
        query.eq(effectiveUserId != null, Order::getUserId, effectiveUserId);
        query.eq(status != null, Order::getStatus, status);
        query.ge(from != null, Order::getShowDate, from);
        query.le(to != null, Order::getShowDate, to);

        IPage<Order> result = orderMapper.selectPage(new Page<>(safePage, safeSize), query);

        Map<Long, String> phones = phonesOf(result.getRecords().stream()
                .map(Order::getUserId).distinct().toList());

        List<AdminOrderDtos.OrderRow> rows = new ArrayList<>(result.getRecords().size());
        for (Order order : result.getRecords()) {
            rows.add(new AdminOrderDtos.OrderRow(
                    order.getOrderNo(), order.getUserId(),
                    phones.getOrDefault(order.getUserId(), ""),
                    order.getProjectTitle(), order.getVenueName(), order.getShowTime(),
                    order.getSeatCount(), order.getPayAmount(), order.getStatus(),
                    OrderStatus.name(order.getStatus()),
                    order.getCreateTime(), order.getPayTime()));
        }
        return new AdminOrderDtos.OrderPage(rows, result.getTotal(), safePage, safeSize);
    }

    public AdminOrderDtos.OrderDetail detail(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));
        return new AdminOrderDtos.OrderDetail(order, items, OrderStatus.name(order.getStatus()));
    }

    /** 某个用户买了什么，给用户详情页用。 */
    public AdminOrderDtos.UserOrderStats statsOf(Long userId) {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId));

        int live = 0;
        BigDecimal paid = BigDecimal.ZERO;
        for (Order order : orders) {
            int status = order.getStatus() == null ? -1 : order.getStatus();
            // 已取消和已退款不算购买。其余的都是客户手上握着、
            // 或者正在等着的东西。
            if (status == OrderStatus.CANCELLED || status == OrderStatus.REFUNDED) {
                continue;
            }
            live++;
            paid = paid.add(order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount());
        }
        return new AdminOrderDtos.UserOrderStats(live, paid);
    }

    // ------------------------------------------------------------

    /**
     * 把手机号换成用户 id。
     *
     * <p>这个 id 到达时是<b>字符串</b>，不是数字。有一个全局 Jackson 定制器把每个 Long
     * 都这样序列化，好让 id 能扛过 JavaScript 的 2^53 上限 —— 这么做是对的，
     * 而且它对 Feign 响应的作用和对浏览器响应完全一样。
     * 把它当 Number 读会静默地什么都匹配不上，于是每一次按手机号搜索都返回空列表，
     * 而它要找的那些订单就躺在表里。
     */
    private Long resolvePhone(String phone) {
        try {
            var response = userClient.findByPhone(phone);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return null;
            }
            Object id = response.getData().get("id");
            if (id instanceof Number number) {
                return number.longValue();
            }
            return id == null ? null : Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            // 一个非数字的 id 是契约被打破，不是用户不存在，所以值得说出来，
            // 而不是返回一个空列表。
            log.error("user service returned an unreadable id for phone {}", phone, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "用户服务返回了意外的数据");
        } catch (Exception e) {
            // 在这里放开（fail-open）会匹配到系统里的每一笔订单，
            // 所以这里选择拒绝，并且说明原因。
            log.error("could not resolve phone to a user: {}", phone, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "用户服务暂不可用");
        }
    }

    /** 一页订单的手机号，一次调用取回，而不是每行各来一次。 */
    private Map<Long, String> phonesOf(List<Long> userIds) {
        Map<Long, String> phones = new HashMap<>();
        if (userIds.isEmpty()) {
            return phones;
        }
        try {
            var response = userClient.phones(userIds);
            if (response != null && response.isSuccess() && response.getData() != null) {
                response.getData().forEach((key, value) -> phones.put(key, String.valueOf(value)));
            }
        } catch (Exception e) {
            // 列表里手机号空着，是一个降级了的页面，不是一个坏掉的页面。
            log.warn("could not load phone numbers for {} users", userIds.size(), e);
        }
        return phones;
    }
}
