package com.maoyan.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maoyan_user.t_user_user}.
 *
 * <p>{@code createTime} / {@code updateTime} are left null on insert so that the
 * column defaults in the DDL apply - MyBatis-Plus skips null fields by default.
 * Setting them from the application clock would let a clock-skewed instance
 * write timestamps that disagree with every other row.
 */
@Data
@TableName("t_user_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Login account. Unique. */
    private String phone;

    /** BCrypt hash. Never logged, never returned to a client. */
    private String password;

    private String nickname;

    private String avatar;

    /** 0 = disabled, 1 = active. */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
