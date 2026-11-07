package com.maipiao.common.web.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个管理员写操作，由 {@link AdminAuditLogAspect} 记录执行结果。
 *
 * <p>建议标在管理 Controller 的写方法上，而不是底层 Service 上：这样事务已经在 Service 返回前完成，审计记录不会跟着业务事务一起回滚；同时一个 HTTP
 * 操作只记一次。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminAuditLog {

    /** 稳定的动作名称，例如 CREATE、UPDATE、DELETE、SET_STATUS。 */
    String action();

    /** 资源名称，例如 PROJECT、SESSION、USER。 */
    String resource();
}
