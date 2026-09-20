package com.maipiao.user.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.user.entity.CouponTemplate;
import com.maipiao.user.entity.User;
import com.maipiao.user.mapper.CouponTemplateMapper;
import com.maipiao.user.mapper.UserMapper;
import com.maipiao.user.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时创建演示账号。
 *
 * <p>为什么用 Java 写而不是在 {@code seed_user.sql} 里放一行：password 列存的是
 * BCrypt 哈希，而一个硬编码在 SQL 里的哈希，必须和 {@code BCryptPasswordEncoder.matches}
 * 期望的字节完全一致 —— 包括 cost 因子和盐的编码方式。在这里生成，
 * 意味着存进去的值是由之后负责校验它的同一个编码器产出的，
 * 所以登录永远不会因为种子数据猜错了而失败。
 *
 * <p>幂等：已存在的用户原样不动，所以重启服务
 * （或者手动改过昵称之后再重启）不会重置任何东西。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "maipiao.demo.init-users", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    /** 手机号、密码、昵称 */
    private static final String[][] DEMO_USERS = {
            {"13800000001", "123456", "阿狸"},
            {"13800000002", "123456", "桃子"},
            {"13800000003", "123456", "小明"},
            {"13800000004", "123456", "大黄"},
            {"13800000005", "123456", "糖糖"},
    };

    private final UserMapper userMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final CouponService couponService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        List<CouponTemplate> templates = couponTemplateMapper.selectList(
                Wrappers.<CouponTemplate>lambdaQuery()
                        .eq(CouponTemplate::getStatus, CouponTemplate.STATUS_ONLINE));

        for (String[] spec : DEMO_USERS) {
            String phone = spec[0];
            Long userId = ensureUser(phone, spec[1], spec[2]);
            if (userId == null) {
                continue;
            }
            issueWelcomeCoupons(userId, templates);
        }
    }

    /**
     * @return 用户 id；账号本来就存在时返回 {@code null}
     *         （那种情况下不会重复发券）
     */
    private Long ensureUser(String phone, String rawPassword, String nickname) {
        User existing = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
        if (existing != null) {
            return null;
        }

        User user = new User();
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname);
        user.setAvatar("");
        user.setStatus(1);
        userMapper.insert(user);

        log.info("demo user created: phone={}, id={}", phone, user.getId());
        return user.getId();
    }

    /** 给一个新建的演示账号每种优惠券模板各发一张。 */
    private void issueWelcomeCoupons(Long userId, List<CouponTemplate> templates) {
        LocalDateTime now = LocalDateTime.now();
        for (CouponTemplate template : templates) {
            couponService.issue(
                    userId,
                    template.getId(),
                    template.getAmount(),
                    template.getThreshold(),
                    template.getValidDays() == null ? 30 : template.getValidDays());
        }
        if (!templates.isEmpty()) {
            log.info("demo coupons issued: userId={}, count={}, at={}", userId, templates.size(), now);
        }
    }
}
