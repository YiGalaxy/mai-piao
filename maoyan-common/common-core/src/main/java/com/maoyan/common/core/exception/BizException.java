package com.maoyan.common.core.exception;

import com.maoyan.common.core.result.ErrorCode;

import java.io.Serial;

/**
 * Business exception. Thrown by services for expected, recoverable failures
 * (seat taken, order expired, coupon unusable...).
 *
 * <p>The global exception handler converts this into {@code R.fail(...)} with
 * HTTP 200, because "the seat is taken" is a normal outcome of a successful
 * request, not a transport failure. Genuine bugs (NPE, SQL errors) surface as
 * {@link ErrorCode#SYSTEM_ERROR} with HTTP 500.
 *
 * <p>Stack trace filling is disabled: these are expected control flow, and
 * catching one is cheaper without the trace.
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
     * Wrap a lower-level failure while keeping the business code.
     * The cause is preserved but no stack trace is filled.
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause, false, false);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }

    // ------------------------------------------------------------
    // Convenience factories - keep call sites short
    // ------------------------------------------------------------

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** Throws when {@code condition} is true. Useful for guard clauses. */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    /** Throws when {@code condition} is true, with a custom message. */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BizException(errorCode, message);
        }
    }
}
