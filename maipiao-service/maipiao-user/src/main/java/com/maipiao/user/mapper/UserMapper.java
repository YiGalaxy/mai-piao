package com.maipiao.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * Plain CRUD comes from {@link BaseMapper}. Anything added here must be a
 * statement that genuinely needs hand-written SQL (a conditional update used
 * as a concurrency guard, a multi-table report) - not something the wrapper
 * already expresses.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
