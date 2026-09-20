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
 * Order lookup, for the people who answer the phone.
 *
 * <p>Every query here has the same shape: somebody rings up with a phone
 * number and a complaint, and the order number is what they do not have. So
 * the filters lead with what an administrator is actually holding - phone
 * first, then status, then a date range.
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
     * Searches orders.
     *
     * <p>{@code phone} is resolved to a user id first, through user-service.
     * The alternative - storing the phone on the order - was rejected when the
     * order was designed: it would be a second copy of a value that changes,
     * on a historical record that must not.
     */
    public AdminOrderDtos.OrderPage search(String orderNo, String phone, Long userId,
                                           Integer status, LocalDate from, LocalDate to,
                                           int page, int size) {

        Long effectiveUserId = userId;
        if (effectiveUserId == null && phone != null && !phone.isBlank()) {
            effectiveUserId = resolvePhone(phone.trim());
            if (effectiveUserId == null) {
                // An unknown phone is not an error, it is an empty result.
                // Answering "no such user" would leak which numbers exist.
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

    /** What a user has bought, for the user detail screen. */
    public AdminOrderDtos.UserOrderStats statsOf(Long userId) {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId));

        int live = 0;
        BigDecimal paid = BigDecimal.ZERO;
        for (Order order : orders) {
            int status = order.getStatus() == null ? -1 : order.getStatus();
            // Cancelled and refunded are not purchases. Everything else is
            // something the customer is holding or waiting on.
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
     * Turns a phone number into a user id.
     *
     * <p>The id arrives as a <b>string</b>, not a number. A global Jackson
     * customiser serialises every Long that way so that ids survive
     * JavaScript's 2^53 limit - which is right, and applies to Feign responses
     * exactly as it does to browser ones. Reading it as a Number silently
     * matched nothing, so every phone search returned an empty list while the
     * orders it was looking for sat in the table.
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
            // A non-numeric id is a contract break, not an absent user, so it
            // is worth saying rather than returning an empty list.
            log.error("user service returned an unreadable id for phone {}", phone, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "用户服务返回了意外的数据");
        } catch (Exception e) {
            // Failing open here would match every order in the system, so this
            // refuses instead and says so.
            log.error("could not resolve phone to a user: {}", phone, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "用户服务暂不可用");
        }
    }

    /** Phone numbers for a page of orders, in one call rather than one each. */
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
            // Blank phones in a list is a degraded screen, not a broken one.
            log.warn("could not load phone numbers for {} users", userIds.size(), e);
        }
        return phones;
    }
}
