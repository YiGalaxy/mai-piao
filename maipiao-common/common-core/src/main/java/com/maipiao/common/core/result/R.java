package com.maipiao.common.core.result;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一的响应封装。每个 HTTP 接口都返回这个形状，前端判断成功/失败只需看这一个地方。
 *
 * <p>controller 从不手工拼失败响应 —— 它们抛
 * {@link com.maipiao.common.core.exception.BizException}，由全局异常处理器去转换。
 * 这样 controller 里就不会出现 try/catch。
 *
 * @param <T> 载荷类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 0 表示成功；其余取值见 {@link ErrorCode}。 */
    private int code;

    private String message;

    private T data;

    /** 服务端的 epoch 毫秒，排查客户端时钟偏差时好用。 */
    private long timestamp;

    public R() {
        this.timestamp = System.currentTimeMillis();
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ------------------------------------------------------------
    // 成功
    // ------------------------------------------------------------

    public static <T> R<T> ok() {
        return new R<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.getCode(), message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    // ------------------------------------------------------------

    /** 业务调用成功时为 true。调用方该看这个，而不是 HTTP 状态码。 */
    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "R{code=" + code + ", message='" + message + "', data=" + data + '}';
    }
}
