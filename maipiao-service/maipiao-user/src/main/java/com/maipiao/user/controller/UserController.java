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
 * 用户接口。
 *
 * <p>注意这里到处都没有 try/catch：{@code BizException} 由共用的
 * {@code GlobalExceptionHandler} 统一翻译。这里的 controller 只做三件事 ——
 * 绑定并校验入参、委派、包进 {@link R}。
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

    /** 网关已经校验过 token 了；用户 id 是通过请求头传过来的。 */
    @GetMapping("/info")
    public R<UserDtos.UserVO> info() {
        return R.ok(userService.getUser(UserContext.require()));
    }
}
