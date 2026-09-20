package com.maipiao.queue.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * Reads a screening's rush configuration from movie-service.
 *
 * <p>Only for metadata - whether the sale is on rush, when it opens, how many
 * seats there are. The seats themselves are never fetched: how many are left
 * comes from the bitmap, which is both cheaper to read and the same number the
 * seat service is acting on.
 */
@FeignClient(name = "maipiao-movie", path = "/inner/schedule")
public interface MovieClient {

    /** Session facts, including {@code totalSeat} and {@code rushStartTime}. */
    @GetMapping("/{sessionId}/snapshot")
    R<Map<String, Object>> snapshot(@PathVariable("sessionId") Long sessionId);
}
