package com.maipiao.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.user.dto.AdminUserDtos;
import com.maipiao.user.entity.User;
import com.maipiao.user.feign.OrderStatsClient;
import com.maipiao.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 用户管理。
 *
 * <p>这里有两条规则贯穿始终，而两条都是为了防止把所有人锁在后台外面：
 *
 * <ul>
 *   <li>你不能停用自己的账号。最必定会被后悔的那个操作，
 *       就是那个会终结执行它的这次会话的操作。</li>
 *   <li>你不能摘掉自己的管理员角色，而最后一个管理员根本不能被降级。
 *       从「没人能管理这个系统」这个状态里没有恢复路径，除非去改数据库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserMapper userMapper;
    private final OrderStatsClient orderStatsClient;

    private static final int MAX_PAGE_SIZE = 100;

    public AdminUserDtos.UserPage search(String keyword, Integer status, String role,
                                         int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        var query = Wrappers.<User>lambdaQuery().orderByDesc(User::getId);
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            // 手机号或昵称。手机号走精确前缀，因为在一个九位数字上做部分匹配，
            // 返回的会是表里的大半；昵称走包含匹配，因为人搜名字就是这么搜的。
            query.and(w -> w.likeRight(User::getPhone, trimmed)
                    .or().like(User::getNickname, trimmed));
        }
        if (status != null) {
            query.eq(User::getStatus, status);
        }
        if (role != null && !role.isBlank()) {
            query.eq(User::getRole, role);
        }

        IPage<User> result = userMapper.selectPage(new Page<>(safePage, safeSize), query);

        List<AdminUserDtos.UserRow> rows = result.getRecords().stream()
                .map(u -> new AdminUserDtos.UserRow(u.getId(), u.getPhone(), u.getNickname(),
                        u.getStatus(), u.getRole(), u.getCreateTime()))
                .toList();

        return new AdminUserDtos.UserPage(rows, result.getTotal(), safePage, safeSize);
    }

    public AdminUserDtos.UserDetail detail(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        int orderCount = 0;
        BigDecimal paid = BigDecimal.ZERO;
        try {
            var response = orderStatsClient.stats(userId);
            if (response != null && response.isSuccess() && response.getData() != null) {
                Map<String, Object> stats = response.getData();
                orderCount = toInt(stats.get("orderCount"));
                paid = toDecimal(stats.get("paidAmount"));
            }
        } catch (Exception e) {
            // 即使关于某个用户的一个数字拿不到，这条用户记录本身也值得显示。
            // 因为 order-service 眨了下眼就让整个页面挂掉，是不划算的取舍。
            log.warn("could not read order stats for user {}: {}", userId, e.getMessage());
        }

        return new AdminUserDtos.UserDetail(user.getId(), user.getPhone(), user.getNickname(),
                user.getAvatar(), user.getStatus(), user.getRole(), user.getCreateTime(),
                orderCount, paid);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(Long userId, Integer status, Long callerId) {
        if (userId.equals(callerId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能停用自己的账号");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "状态只能是 0（停用）或 1（启用）");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        if (status == 0 && JwtUtil.ROLE_ADMIN.equals(user.getRole()) && adminCount() <= 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "这是最后一个管理员，不能停用");
        }

        user.setStatus(status);
        userMapper.updateById(user);
        log.warn("user status changed: userId={}, status={}, by={}", userId, status, callerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setRole(Long userId, String role, Long callerId) {
        if (!JwtUtil.ROLE_ADMIN.equals(role) && !JwtUtil.ROLE_USER.equals(role)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色只能是 ADMIN 或 USER");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        boolean demoting = JwtUtil.ROLE_ADMIN.equals(user.getRole())
                && !JwtUtil.ROLE_ADMIN.equals(role);

        if (demoting && userId.equals(callerId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能撤销自己的管理员权限");
        }
        if (demoting && adminCount() <= 1) {
            // 唯一一个没有出路的处境：没人能管理任何东西，
            // 而唯一的修复手段是在数据库上动手。
            throw new BizException(ErrorCode.FORBIDDEN, "这是最后一个管理员，不能撤销");
        }

        user.setRole(role);
        userMapper.updateById(user);
        log.warn("user role changed: userId={}, role={}, by={}", userId, role, callerId);
    }

    /** 还活着的管理员。被停用的不算 —— 他们登不进来。 */
    private long adminCount() {
        Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, JwtUtil.ROLE_ADMIN)
                .eq(User::getStatus, 1));
        return count == null ? 0 : count;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
