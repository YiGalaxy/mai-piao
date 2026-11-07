package com.maipiao.user.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.audit.AdminAuditLog;
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

/** 用户管理接口；管理员鉴权由网关执行，当前操作者由 Controller 传入。 */
@Slf4j
@RestController
@RequestMapping("/user/admin")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService adminService;

    /** 分页搜索用户。 */
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

    /** 启用或停用账号；停用代替物理删除。 */
    @PutMapping("/users/{userId}/status")
    @AdminAuditLog(action = "SET_STATUS", resource = "USER")
    public R<Void> setStatus(@PathVariable Long userId, @RequestParam Integer status) {
        adminService.setStatus(userId, status, UserContext.require());
        return R.ok();
    }

    /** 授予或撤销管理员角色。 */
    @PutMapping("/users/{userId}/role")
    @AdminAuditLog(action = "SET_ROLE", resource = "USER")
    public R<Void> setRole(@PathVariable Long userId, @RequestParam String role) {
        adminService.setRole(userId, role, UserContext.require());
        return R.ok();
    }
}
