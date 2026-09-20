package com.maipiao.common.core.exception;

import com.maipiao.common.core.result.ErrorCode;

import java.io.Serial;

/**
 * 业务异常。服务端遇到预期内、可恢复的失败时抛它
 * （座位已被占、订单已过期、优惠券不可用……）。
 *
 * <p>全局异常处理器把它转成 HTTP 200 的 {@code R.fail(...)}，因为"座位被占了"是
 * 一次成功请求的正常结果，不是传输层故障。真正的 bug（NPE、SQL 错误）则以
 * {@link ErrorCode#SYSTEM_ERROR} 加 HTTP 500 的形式冒出来。
 *
 * <p>关闭了堆栈填充：这些属于预期内的控制流，不带堆栈时捕获它的开销更低。
 */
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage(), null, false, false);
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.code = errorCode.getCode();
    }

    /**
     * 包装一个更底层的失败，同时保留业务码。
     * cause 会保留下来，但不填充堆栈。
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause, false, false);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }

    // ------------------------------------------------------------
    // 便捷工厂方法 —— 让调用处短一些
    // ------------------------------------------------------------

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** 当 {@code condition} 为 true 时抛出。适合写守卫式的前置判断。 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    /** 当 {@code condition} 为 true 时抛出，可自定义消息。 */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BizException(errorCode, message);
        }
    }
}
