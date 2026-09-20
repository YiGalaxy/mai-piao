package com.maipiao.user.controller;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.user.dto.UserDtos;
import com.maipiao.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User endpoints.
 *
 * <p>Note there is no try/catch anywhere: {@code BizException} is translated by
 * the shared {@code GlobalExceptionHandler}. Controllers here do three things
 * only - bind and validate input, delegate, wrap in {@link R}.
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public R<Long> register(@Valid @RequestBody UserDtos.RegisterRequest request) {
        return R.ok(userService.register(request));
    }

    @PostMapping("/login")
    public R<UserDtos.LoginResponse> login(@Valid @RequestBody UserDtos.LoginRequest request) {
        return R.ok(userService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout(
            @RequestHeader(value = CommonConstants.HEADER_AUTHORIZATION, required = false)
            String authorization) {
        userService.logout(authorization);
        return R.ok();
    }

    /** The gateway has already verified the token; the user id arrives in a header. */
    @GetMapping("/info")
    public R<UserDtos.UserVO> info() {
        return R.ok(userService.getUser(UserContext.require()));
    }
}
