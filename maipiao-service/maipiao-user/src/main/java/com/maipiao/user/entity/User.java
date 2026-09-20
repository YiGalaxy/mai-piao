package com.maipiao.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_user.t_user_user}。
 *
 * <p>插入时 {@code createTime} / {@code updateTime} 故意留成 null，好让 DDL 里的列默认值生效 ——
 * MyBatis-Plus 默认会跳过 null 字段。用应用自己的时钟去写它们，
 * 会让一台时钟偏移的实例写出和其他所有行都对不上的时间戳。
 */
@Data
@TableName("t_user_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录账号。唯一。 */
    private String phone;

    /** BCrypt 哈希。绝不进日志，绝不返回给客户端。 */
    private String password;

    private String nickname;

    private String avatar;

    /** 0 = 停用，1 = 启用。 */
    private Integer status;

    /**
     * USER 或 ADMIN。
     *
     * <p>存下来而不是靠推断，这样「一个 token 能碰到什么」就变成了账号自身的属性。
     * JwtUtil 一直都有 generateAdminToken 方法，却从来没人调用过它，
     * 于是角色是由调用方碰巧调了哪个方法来决定的 —— 而唯一的调用方调的是普通用户那个。
     * 现在要改变谁是管理员，是一次数据库改动。
     */
    private String role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
