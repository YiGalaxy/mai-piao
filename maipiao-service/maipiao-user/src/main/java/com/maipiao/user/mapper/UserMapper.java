package com.maipiao.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 普通 CRUD 来自 {@link BaseMapper}。往这里加的任何东西，必须是真正需要手写 SQL 的语句
 * （用作并发守护的条件更新、多表报表），而不是 wrapper 已经能表达的东西。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
