package com.maoyan.user.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maoyan.user.entity.CouponTemplate;
import com.maoyan.user.entity.User;
import com.maoyan.user.mapper.CouponTemplateMapper;
import com.maoyan.user.mapper.UserMapper;
import com.maoyan.user.service.CouponService;
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
 * Creates the demo accounts on startup.
 *
 * <p>Why this is Java and not a row in {@code seed_user.sql}: the password
 * column stores a BCrypt hash, and a hash hard-coded in SQL has to be
 * byte-identical to what {@code BCryptPasswordEncoder.matches} expects -
 * including the cost factor and the salt encoding. Generating it here means
 * the stored value is produced by the same encoder that will later verify it,
 * so login can never fail because the seed data guessed wrong.
 *
 * <p>Idempotent: existing users are left untouched, so restarting the service
 * (or restarting it after changing a nickname by hand) does not reset anything.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "maoyan.demo.init-users", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    /** phone, password, nickname */
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
     * @return the user id, or {@code null} when the account already existed
     *         (in which case coupons are not re-issued)
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

    /** Gives a fresh demo account one of each coupon template. */
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
