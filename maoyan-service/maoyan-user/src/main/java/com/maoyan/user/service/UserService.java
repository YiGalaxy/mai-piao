package com.maoyan.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maoyan.common.core.exception.BizException;
import com.maoyan.common.core.result.ErrorCode;
import com.maoyan.common.web.util.JwtUtil;
import com.maoyan.user.dto.UserDtos;
import com.maoyan.user.entity.User;
import com.maoyan.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and the small amount of user lookup other flows need.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    // ------------------------------------------------------------
    // registration
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long register(UserDtos.RegisterRequest request) {
        // Fast path: a readable error instead of a constraint violation.
        // This check is NOT the guarantee - two concurrent registrations both
        // pass it and only the unique index stops the second one. See the catch.
        Long existing = userMapper.selectCount(
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
            // Lost the race against a concurrent registration of the same phone.
            // Translating it here keeps the API contract consistent regardless
            // of which path detected the duplicate.
            throw new BizException(ErrorCode.USER_PHONE_EXISTS);
        }

        log.info("user registered: id={}, phone={}", user.getId(), maskPhone(user.getPhone()));
        return user.getId();
    }

    // ------------------------------------------------------------
    // login
    // ------------------------------------------------------------

    public UserDtos.LoginResponse login(UserDtos.LoginRequest request) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getPhone, request.phone()));

        // Same error for "no such user" and "wrong password" on purpose: telling
        // them apart lets an attacker enumerate which phone numbers are registered.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.USER_PASSWORD_WRONG);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        String token = jwtUtil.generateUserToken(user.getId(), user.getPhone());
        log.info("user logged in: id={}", user.getId());

        return new UserDtos.LoginResponse(
                token, user.getId(), user.getPhone(), user.getNickname(), user.getAvatar());
    }

    /**
     * Revokes a token immediately.
     *
     * <p>A JWT cannot be "deleted" - it is valid until it expires, and every
     * service that trusts the signature will accept it. So logout records the
     * token's jti in Redis for the remainder of its lifetime, and the gateway
     * rejects anything on that list. The cost is one Redis lookup per
     * authenticated request; the benefit is that logout actually logs you out.
     */
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokenBlacklistService.revoke(rawToken);
    }

    // ------------------------------------------------------------
    // lookup
    // ------------------------------------------------------------

    public UserDtos.UserVO getUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    /** Internal use: returns the entity, callers must not expose it directly. */
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
