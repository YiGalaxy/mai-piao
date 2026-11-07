package com.maipiao.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.user.dto.UserDtos;
import com.maipiao.user.entity.User;
import com.maipiao.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册、登录，以及其他流程需要的那一点点用户查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    // ------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long register(UserDtos.RegisterRequest request) {
        // 快路径：给出一个读得懂的错误，而不是一个约束冲突。
        // 这个检查不构成保证 —— 两个并发注册都能通过它，真正拦住第二个的是唯一索引。
        // 见下面的 catch。
        Long existing =
                userMapper.selectCount(
                        Wrappers.<User>lambdaQuery().eq(User::getPhone, request.phone()));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.USER_PHONE_EXISTS);
        }

        User user = new User();
        user.setPhone(request.phone());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(
                request.nickname() == null || request.nickname().isBlank()
                        ? maskPhone(request.phone())
                        : request.nickname());
        user.setAvatar("");
        user.setStatus(1);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 在同一个手机号的并发注册中输掉了。在这里翻译成业务异常，
            // 能让 API 契约保持一致，不管重复是被哪条路径发现的。
            throw new BizException(ErrorCode.USER_PHONE_EXISTS);
        }

        log.info("user registered: id={}, phone={}", user.getId(), maskPhone(user.getPhone()));
        return user.getId();
    }

    // ------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------

    public UserDtos.LoginResponse login(UserDtos.LoginRequest request) {
        User user =
                userMapper.selectOne(
                        Wrappers.<User>lambdaQuery().eq(User::getPhone, request.phone()));

        // 「没有这个用户」和「密码错误」故意返回同一个错误：把它们区分开，
        // 等于让攻击者能枚举出哪些手机号已经注册过。
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.USER_PASSWORD_WRONG);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        boolean admin = JwtUtil.ROLE_ADMIN.equals(user.getRole());
        String token =
                admin
                        ? jwtUtil.generateAdminToken(user.getId(), user.getPhone())
                        : jwtUtil.generateUserToken(user.getId(), user.getPhone());
        log.info("user logged in: id={}, admin={}", user.getId(), admin);

        return new UserDtos.LoginResponse(
                token, user.getId(), user.getPhone(), user.getNickname(), user.getAvatar());
    }

    /** 将 token 的 jti 加入 Redis 黑名单，直到 token 自然过期。 */
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokenBlacklistService.revoke(rawToken);
    }

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    public UserDtos.UserVO getUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    /** 内部使用：返回实体本身，调用方不得把它直接暴露出去。 */
    public User getEntity(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    public UserDtos.UserVO toVO(User user) {
        return new UserDtos.UserVO(
                user.getId(), user.getPhone(), user.getNickname(), user.getAvatar());
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return "user";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
