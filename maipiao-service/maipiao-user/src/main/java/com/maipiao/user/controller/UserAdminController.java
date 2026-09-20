package com.maipiao.user.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.user.dto.AdminUserDtos;
import com.maipiao.user.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration.
 *
 * <p>Administrators only, enforced at the gateway: it refuses
 * {@code /api/*&#47;admin/**} to a token without the admin role, before it
 * consults its public whitelist. Checking here as well would be a second place
 * to get it wrong, and the first one is a single shared filter rather than
 * something each controller has to remember.
 *
 * <p>The controller does one thing the service cannot: it knows who is asking.
 * Lockout guards - you cannot disable yourself, you cannot take your own admin
 * role away - need the caller's identity, and the service should not be
 * reading a thread-local to find it.
 */
@Slf4j
@RestController
@RequestMapping("/user/admin")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService adminService;

    /** Paged search by phone, nickname or both. */
    @GetMapping("/users")
    public R<AdminUserDtos.UserPage> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(adminService.search(keyword, status, role, page, size));
    }

    @GetMapping("/users/{userId}")
    public R<AdminUserDtos.UserDetail> user(@PathVariable Long userId) {
        return R.ok(adminService.detail(userId));
    }

    /**
     * Enables or disables an account.
     *
     * <p>Disabling is the only removal there is: orders reference the user, and
     * a deleted customer would leave tickets that belong to nobody. Disabled
     * accounts cannot log in and existing tokens stop working the next time
     * they are checked against the blacklist.
     */
    @PutMapping("/users/{userId}/status")
    public R<Void> setStatus(@PathVariable Long userId, @RequestParam Integer status) {
        adminService.setStatus(userId, status, UserContext.require());
        return R.ok();
    }

    /** Grants or revokes the admin role. */
    @PutMapping("/users/{userId}/role")
    public R<Void> setRole(@PathVariable Long userId, @RequestParam String role) {
        adminService.setRole(userId, role, UserContext.require());
        return R.ok();
    }
}
