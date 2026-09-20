package com.maipiao.user.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.result.R;
import com.maipiao.user.entity.User;
import com.maipiao.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 供其他服务调用的用户查询。
 *
 * <p>和优惠券那个 controller 分开，因为它们是两种不同的东西：
 * 优惠券接口是抛异常的事务分支，而这些是返回空结果的读操作。
 * 混在一起就变成一个类里的方法各自需要不同的失败规则。
 *
 * <p>不从外部路由。网关会在查白名单之前就拒掉 {@code /api/*&#47;inner/**}。
 */
@Slf4j
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class UserLookupInternalController {

    private final UserMapper userMapper;

    /**
     * 按手机号查一个用户。
     *
     * <p>返回 id、昵称和状态，但绝不返回密码哈希。调用方拿着它没有任何用，
     * 而要让它绝不可能从一个接口泄露出去，最稳妥的办法就是这个接口根本不去查它。
     *
     * <p>没匹配到任何人的手机号返回 {@code null} 而不是错误：
     * 「没有这个用户」是搜索的一种普通答案，由调用方把它变成一个空结果。
     */
    @GetMapping("/find-by-phone")
    public R<Map<String, Object>> findByPhone(@RequestParam String phone) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, phone)
                .last("LIMIT 1"));
        if (user == null) {
            return R.ok(null);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("id", user.getId());
        body.put("phone", user.getPhone());
        body.put("nickname", user.getNickname());
        body.put("status", user.getStatus());
        return R.ok(body);
    }

    /**
     * 一批用户 id 对应的手机号。
     *
     * <p>故意做成批量：订单列表每行都要一个手机号，
     * 而每行一次调用会把一页二十笔订单变成二十次往返，
     * 只为渲染一个没人细看的东西。
     */
    @GetMapping("/phones")
    public R<Map<Long, String>> phones(@RequestParam List<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return R.ok(result);
        }

        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getPhone)
                .in(User::getId, userIds));
        for (User user : users) {
            result.put(user.getId(), user.getPhone());
        }
        return R.ok(result);
    }
}
