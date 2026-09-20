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
 * 把异常翻译成统一的 {@link R} 响应封装，这样 controller 永远不需要 try/catch。
 *
 * <p>HTTP 状态码策略：
 * <ul>
 *   <li>{@link BizException} 返回 <b>200</b> —— "座位已经被人占了"是一个结构良好的
 *       请求的合法结果，不是传输层故障。调用方读 {@code code} 来判断发生了什么。</li>
 *   <li>格式错误的请求返回 <b>400 / 405 / 404</b> —— 客户端发来的东西在结构上就是错的，
 *       而且应该被告知在传输层，这样网关和监控能看到。</li>
 *   <li>任何意料之外的情况返回 <b>500</b> —— 这是 bug，必须大声。客户端只拿到一句
 *       泛泛的消息；细节进日志。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------------------
    // 预期内的业务失败 -> HTTP 200
    // ------------------------------------------------------------

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        // 用 warn 而不是 error：这是正常的控制流，不该半夜把人叫起来
        log.warn("biz exception [{}] {} -> code={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    // ------------------------------------------------------------
    // 格式错误的请求 -> HTTP 400
    // ------------------------------------------------------------

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return R.fail(ErrorCode.PARAM_ERROR, joinFieldErrors(e));
    }

    /** 表单 / query 对象绑定时的 {@code @Valid} 校验失败。 */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e) {
        return R.fail(ErrorCode.PARAM_ERROR, joinFieldErrors(e));
    }

    /** 方法参数上的 {@code @Validated} 校验失败（例如 query 参数上的 {@code @Min}）。 */
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
    // 路由 -> 404 / 405
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
    // 其余一切 -> HTTP 500
    // ------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        // 这里打完整堆栈。客户端只拿到一句泛泛的消息，因为把 SQL 片段或文件路径
        // 泄漏给调用方就是信息泄露。
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
