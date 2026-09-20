package com.maipiao.queue.config;

import com.maipiao.common.core.constant.CommonConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for admission.
 *
 * <p>The defaults come from {@link CommonConstants} rather than being repeated
 * here, so the numbers the design talks about and the numbers the service runs
 * with cannot drift apart.
 */
@Data
@Component
@ConfigurationProperties(prefix = "maipiao.queue")
public class QueueProperties {

    /**
     * How many people to admit per remaining seat.
     *
     * <p>Not 1.0, because not everyone admitted goes on to order - some
     * hesitate, some close the tab, some find the seats they wanted gone and
     * leave. Admitting exactly as many as there are seats leaves the last few
     * unsold. Not 10.0 either, which would mean thousands of people waiting in
     * a line for a ticket that was never there.
     */
    private double admitFactor = CommonConstants.QUEUE_ADMIT_FACTOR;

    /** Lifetime of an admission token. Past it, the place is given up. */
    private int tokenSeconds = CommonConstants.QUEUE_TOKEN_SECONDS;

    /** How often the dispatcher wakes. */
    private long dispatchIntervalMs = CommonConstants.QUEUE_DISPATCH_INTERVAL_MS;

    /** Ceiling on one round's admissions, so a large sale does not arrive all at once. */
    private int maxBatch = CommonConstants.QUEUE_ADMIT_MAX_BATCH;

    /** How long a line survives with nobody in it before the registry entry is dropped. */
    private int scheduleTtlSeconds = 6 * 3600;

    /** How long fetched session metadata is trusted. */
    private int sessionCacheSeconds = 60;
}
