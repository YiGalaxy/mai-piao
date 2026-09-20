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
 * 用户管理。
 *
 * <p>仅限管理员，由网关强制执行：网关会在查自己的公开白名单之前，
 * 就把没有管理员角色的 token 挡在 {@code /api/*&#47;admin/**} 之外。
 * 在这里再查一遍等于多出第二个可能写错的地方，而第一个是一段共用的过滤器，
 * 不是每个 controller 都得记着去做的事。
 *
 * <p>controller 做了一件 service 做不了的事：它知道是谁在发问。
 * 防自锁的那些守卫 —— 你不能停用自己，不能摘掉自己的管理员角色 —— 需要调用方的身份，
 * 而 service 不应该为了拿这个身份去读 thread-local。
 */
@Slf4j
@RestController
@RequestMapping("/user/admin")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService adminService;

    /** 按手机号、昵称，或两者一起分页搜索。 */
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
     * 启用或停用一个账号。
     *
     * <p>停用是这里唯一的「删除」手段：订单引用着用户，
     * 而一个被删掉的客户会留下不属于任何人的票。被停用的账号登不进来，
     * 已有的 token 在下次被拿去和黑名单比对时就会失效。
     */
    @PutMapping("/users/{userId}/status")
    public R<Void> setStatus(@PathVariable Long userId, @RequestParam Integer status) {
        adminService.setStatus(userId, status, UserContext.require());
        return R.ok();
    }

    /** 授予或撤销管理员角色。 */
    @PutMapping("/users/{userId}/role")
    public R<Void> setRole(@PathVariable Long userId, @RequestParam String role) {
        adminService.setRole(userId, role, UserContext.require());
        return R.ok();
    }
}
