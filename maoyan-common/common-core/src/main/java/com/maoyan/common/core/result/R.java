package com.maoyan.common.core.result;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serial;
import java.io.Serializable;

/**
 * Unified response envelope. Every HTTP endpoint returns this shape, so the
 * frontend has exactly one place to look for success/failure.
 *
 * <p>Controllers never build failure responses by hand - they throw
 * {@link com.maoyan.common.core.exception.BizException} and let the global
 * exception handler translate it. That keeps try/catch out of controllers.
 *
 * @param <T> payload type
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 0 means success; see {@link ErrorCode} for the rest. */
    private int code;

    private String message;

    private T data;

    /** Server epoch millis, handy for client-side clock-skew debugging. */
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
    // success
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
    // failure
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

    /** True when the business call succeeded. Callers should check this, not the HTTP status. */
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
