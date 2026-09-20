package com.maipiao.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户接口的请求与响应结构。
 *
 * <p>故意集中在一个文件里：它们都很小，而且永远是一起变的；
 * 把四个 record 拆到四个文件里，只会让这份契约更难读，而不是更整齐。
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** 中国大陆手机号。收得严一点，免得注册出任何短信服务商都不会接受的账号。 */
    private static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    public record RegisterRequest(
            @NotBlank(message = "phone is required")
            @Pattern(regexp = PHONE_REGEX, message = "phone number format is invalid")
            String phone,

            @NotBlank(message = "password is required")
            @Size(min = 6, max = 32, message = "password must be 6 to 32 characters")
            String password,

            @Size(max = 50, message = "nickname must be at most 50 characters")
            String nickname
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "phone is required")
            String phone,

            @NotBlank(message = "password is required")
            String password
    ) {
    }

    /**
     * 登录返回的东西。密码哈希自然不在其中，
     * 其他任何客户端拿着也没用的东西同样不在。
     */
    public record LoginResponse(
            String token,
            Long userId,
            String phone,
            String nickname,
            String avatar
    ) {
    }

    /** 对外的用户视图。注意这里根本没有 password 字段 —— 不是置空的那种 ——
     *  所以它不可能因为一次意外的序列化而泄露。 */
    public record UserVO(
            Long id,
            String phone,
            String nickname,
            String avatar
    ) {
    }
}
