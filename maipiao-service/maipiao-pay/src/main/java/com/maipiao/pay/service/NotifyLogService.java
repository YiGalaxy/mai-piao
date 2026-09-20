package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.entity.NotifyLog;
import com.maipiao.pay.mapper.NotifyLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 第 L1 层：回调日志。
 *
 * <p>它唯一的职责就是回答一个问题 —— 这条回调此前有没有被成功处理过 —— 而这个答案
 * 决定业务逻辑要不要再跑一遍。它刻意<em>不</em>负责决定要不要回给渠道方一个成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyLogService {

    private final NotifyLogMapper notifyLogMapper;

    /**
     * @param alreadySucceeded 此前某次尝试已经完成时为 true，
     *                         这时调用方可以答复渠道方，就此打住
     */
    public record RecordResult(Long logId, boolean alreadySucceeded, int retryTimes) {
    }

    public RecordResult record(String channel, String channelTradeNo, String notifyType,
                               String rawBody, boolean signVerified) {

        // 唯一键需要一个非空的交易号。没有交易号的回调没法去重，所以就用一个占位值记下来，
        // 而下游的业务逻辑会因为别的原因把它拒掉。
        String tradeNo = channelTradeNo == null || channelTradeNo.isBlank()
                ? "UNKNOWN-" + System.currentTimeMillis()
                : channelTradeNo;

        Long logId = SnowflakeIdGenerator.next();
        try {
            notifyLogMapper.insertOrCount(logId, channel, tradeNo, notifyType, rawBody,
                    signVerified ? 1 : 0);
        } catch (DuplicateKeyException e) {
            // 只有两条回调在同一瞬间落地时才会走到这里；第二条读到的就是第一条建的那一行。
            log.debug("concurrent callback insert: tradeNo={}", tradeNo);
        }

        NotifyLog entry = notifyLogMapper.selectOne(channel, tradeNo, notifyType);
        if (entry == null) {
            // 不该发生 —— 上面的插入要么建出了这一行，要么撞上了已有的行。
            // 按「照常处理」对待，这是安全的默认选择。
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
