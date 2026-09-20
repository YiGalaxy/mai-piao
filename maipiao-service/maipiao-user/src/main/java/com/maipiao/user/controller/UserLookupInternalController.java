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
 * User lookups for other services.
 *
 * <p>A separate controller from the coupon one because it is a different kind
 * of thing: the coupon endpoints are transactional branches that throw, these
 * are reads that return empty. Mixing them would mean one class whose methods
 * need different rules about failure.
 *
 * <p>Not routed from outside. The gateway rejects {@code /api/*&#47;inner/**}
 * before consulting its whitelist.
 */
@Slf4j
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class UserLookupInternalController {

    private final UserMapper userMapper;

    /**
     * Finds a user by phone.
     *
     * <p>Returns the id, nickname and status but never the password hash. A
     * caller has no use for it and the surest way for it not to leak through
     * an endpoint is for the endpoint not to select it.
     *
     * <p>A phone that matches nothing returns {@code null} rather than an
     * error: "no such user" is an ordinary answer to a search, and the caller
     * turns it into an empty result.
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
     * Phone numbers for a set of user ids.
     *
     * <p>Batched on purpose: the order list needs a phone per row, and one
     * call per row would turn a page of twenty orders into twenty round trips
     * to render something nobody reads closely.
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
