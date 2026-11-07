package com.maipiao.common.core.result;

import com.maipiao.common.core.exception.BizException;

/** Feign 的 HTTP 200 也可能携带业务失败；关键写入必须显式检查。 */
public final class RemoteResult {
    private RemoteResult() {}

    public static <T> T requireSuccess(R<T> result, String operation) {
        if (result == null || !result.isSuccess()) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, operation + "失败，请稍后核对重试");
        }
        return result.getData();
    }
}
