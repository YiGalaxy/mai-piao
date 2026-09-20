package com.maoyan.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for the user endpoints.
 *
 * <p>Grouped in one file on purpose: they are all tiny, they only ever change
 * together, and splitting four records across four files makes the contract
 * harder to read than it makes it organised.
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** Mainland China mobile number. Kept strict so registration cannot create
     *  accounts that no SMS provider would ever accept. */
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
     * Returned by login. The password hash is of course absent, and so is
     * anything else the client has no use for.
     */
    public record LoginResponse(
            String token,
            Long userId,
            String phone,
            String nickname,
            String avatar
    ) {
    }

    /** Public view of a user. Note there is no password field at all - not a
     *  nulled one - so it cannot be leaked by an accidental serialization. */
    public record UserVO(
            Long id,
            String phone,
            String nickname,
            String avatar
    ) {
    }
}
