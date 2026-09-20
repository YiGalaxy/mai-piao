package com.maipiao.queue.service;

import com.maipiao.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Moves the line.
 *
 * <p>Not a cleanup job bolted on the side - the queue only advances because
 * this runs. Nobody is admitted by asking; they are admitted by being at the
 * front when the dispatcher next looks.
 *
 * <p>Runs on every instance, with no leader election and no lock. That is safe
 * because admission is a {@code ZPOPMIN}: Redis pops the members as one
 * operation, so two instances waking together split the batch between them
 * rather than both taking it. Adding a lock would be slower and would only
 * reintroduce the failure it was meant to prevent - a stalled holder stopping
 * the sale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionDispatcher {

    private final QueueService queueService;
    private final QueueProperties properties;

    /**
     * How many people to admit on this pass.
     *
     * <p>Extracted and pure because it is the one number the whole design turns
     * on, and it should be arguable without reading a scheduler.
     *
     * <p>Stock, not queue length, sets the target: admitting a fixed share of
     * the people waiting would be admitting faster the more hopeless the sale
     * got. The {@code admitFactor} overshoot exists because not everyone
     * admitted goes on to buy - hesitating, tab-closing and second-guessing are
     * all normal - and admitting exactly the number of seats leaves the last
     * few unsold. Clamped at zero because a line that is already oversubscribed
     * should simply not advance.
     */
    int computeQuota(int remainingSeats, int inFlight) {
        if (remainingSeats <= 0) {
            return 0;
        }
        int target = (int) Math.ceil(remainingSeats * properties.getAdmitFactor());
        int quota = target - inFlight;
        if (quota <= 0) {
            return 0;
        }
        return Math.min(quota, properties.getMaxBatch());
    }

    @Scheduled(fixedDelayString = "${maipiao.queue.dispatch-interval-ms:200}")
    public void dispatch() {
        List<Long> schedules;
        try {
            schedules = queueService.rushScheduleIds();
        } catch (Exception e) {
            // Redis is the queue. If it is unreachable there is nothing to
            // dispatch and nothing useful to say about it every 200ms.
            log.error("could not read the rush registry", e);
            return;
        }

        for (Long scheduleId : schedules) {
            try {
                dispatchOne(scheduleId);
            } catch (Exception e) {
                // One bad screening must not stop the others - every other
                // line is just as real.
                log.error("dispatch failed: schedule={}", scheduleId, e);
            }
        }
    }

    private void dispatchOne(Long scheduleId) {
        if (queueService.isSoldOut(scheduleId)) {
            // The line is left where it is rather than cleared. Everyone in it
            // is about to be told the sale is over by the position call, and
            // keeping them costs a key that expires - whereas clearing them
            // would make the queue unrecoverable if the sold-out flag were ever
            // wrong, or lifted when a refund puts seats back.
            return;
        }
        if (queueService.isPaused(scheduleId)) {
            return;
        }

        // Expired admissions are released before the count is taken, or the
        // people who gave up would go on holding places against the stock.
        queueService.reapExpired(scheduleId);

        int remaining = queueService.remaining(scheduleId);
        int inFlight = queueService.inflight(scheduleId);
        int quota = computeQuota(remaining, inFlight);

        if (quota <= 0) {
            if (queueService.waiting(scheduleId) == 0 && inFlight == 0) {
                // Nobody left in either set. The screening is still registered
                // only because the last person left without anyone noticing.
                queueService.deregister(scheduleId);
            }
            return;
        }

        queueService.admit(scheduleId, quota);
    }
}
