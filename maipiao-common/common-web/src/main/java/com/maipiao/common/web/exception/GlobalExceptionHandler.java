package com.maipiao.common.web.exception;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * Translates exceptions into the unified {@link R} envelope so that controllers
 * never need try/catch.
 *
 * <p>HTTP status policy:
 * <ul>
 *   <li><b>200</b> for {@link BizException} - "the seat is already taken" is a
 *       legitimate outcome of a well-formed request, not a transport failure.
 *       The caller reads {@code code} to decide what happened.</li>
 *   <li><b>400 / 405 / 404</b> for malformed requests - the client sent
 *       something structurally wrong and should be told so at the transport
 *       layer, where gateways and monitoring can see it.</li>
 *   <li><b>500</b> for anything unexpected - this is a bug, and it must be
 *       loud. The client gets a generic message; the detail goes to the log.</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------
    // expected business failures -> HTTP 200
    // ------------------------------------------------------------

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        // warn, not error: this is normal control flow and should not page anyone
        log.warn("biz exception [{}] {} -> code={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    // ------------------------------------------------------------
    // malformed requests -> HTTP 400
    // ------------------------------------------------------------

    /** @Valid failure on a @RequestBody. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return R.fail(ErrorCode.PARAM_ERROR, joinFieldErrors(e));
    }

    /** @Valid failure on a form / query object binding. */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e) {
        return R.fail(ErrorCode.PARAM_ERROR, joinFieldErrors(e));
    }

    /** @Validated on a method parameter (e.g. @Min on a query param). */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return R.fail(ErrorCode.PARAM_ERROR, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(ErrorCode.PARAM_ERROR, "missing required parameter: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(ErrorCode.PARAM_ERROR,
                "parameter '" + e.getName() + "' has the wrong type");
    }

    // ------------------------------------------------------------
    // routing -> 404 / 405
    // ------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoHandler(NoHandlerFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND, "no handler for " + e.getRequestURL());
    }

    // ------------------------------------------------------------
    // everything else -> HTTP 500
    // ------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        // Full stack trace here. The client only gets a generic message, because
        // leaking SQL fragments or file paths to a caller is an information leak.
        log.error("unhandled exception [{}] {}", request.getMethod(), request.getRequestURI(), e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }

    // ------------------------------------------------------------

    private String joinFieldErrors(BindException e) {
        return e.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .collect(Collectors.joining("; "));
    }

    private String describeFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
