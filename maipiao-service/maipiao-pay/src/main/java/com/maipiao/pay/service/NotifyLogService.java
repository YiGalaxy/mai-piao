package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.entity.NotifyLog;
import com.maipiao.pay.mapper.NotifyLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Layer L1: the callback log.
 *
 * <p>Its only job is to answer one question - has this callback been handled
 * successfully before - and the answer decides whether the business logic runs
 * again. It deliberately does <em>not</em> decide whether to answer the
 * provider with success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyLogService {

    private final NotifyLogMapper notifyLogMapper;

    /**
     * @param alreadySucceeded true when a previous attempt completed, in which
     *                         case the caller can answer the provider and stop
     */
    public record RecordResult(Long logId, boolean alreadySucceeded, int retryTimes) {
    }

    public RecordResult record(String channel, String channelTradeNo, String notifyType,
                               String rawBody, boolean signVerified) {

        // The unique key needs a non-null trade number. A callback without one
        // cannot be deduped, so it is recorded under a placeholder and the
        // business logic downstream will reject it for other reasons.
        String tradeNo = channelTradeNo == null || channelTradeNo.isBlank()
                ? "UNKNOWN-" + System.currentTimeMillis()
                : channelTradeNo;

        Long logId = SnowflakeIdGenerator.next();
        try {
            notifyLogMapper.insertOrCount(logId, channel, tradeNo, notifyType, rawBody,
                    signVerified ? 1 : 0);
        } catch (DuplicateKeyException e) {
            // Only possible if two callbacks land in the same instant; the
            // second one simply reads the row the first created.
            log.debug("concurrent callback insert: tradeNo={}", tradeNo);
        }

        NotifyLog entry = notifyLogMapper.selectOne(channel, tradeNo, notifyType);
        if (entry == null) {
            // Should not happen - the insert above either created it or hit an
            // existing row. Treated as "process it", which is the safe default.
            return new RecordResult(logId, false, 0);
        }

        boolean alreadySucceeded = entry.getProcessStatus() != null
                && entry.getProcessStatus() == NotifyLog.STATUS_SUCCESS;

        return new RecordResult(entry.getId(), alreadySucceeded,
                entry.getRetryTimes() == null ? 0 : entry.getRetryTimes());
    }

    public void markProcessed(Long logId, int status, String result) {
        notifyLogMapper.markProcessed(logId, status, truncate(result));
    }

    public void markFailed(Long logId, String error) {
        notifyLogMapper.markProcessed(logId, NotifyLog.STATUS_FAILED, truncate(error));
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
