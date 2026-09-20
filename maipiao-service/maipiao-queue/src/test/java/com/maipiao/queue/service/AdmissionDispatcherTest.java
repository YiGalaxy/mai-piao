package com.maipiao.queue.service;

import com.maipiao.queue.config.QueueProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 放行人数怎么算。
 *
 * <p>整个排队设计就压在这一个数字上，而它最该被单独测的原因是：算错了不会报错。
 * 算少了，票卖不完；算多了，几千人排了半天的队，等的是一张从来就不存在的票。
 * 两种都是安静的失败。
 *
 * <p>{@code computeQuota} 被抽成纯函数，正是为了能像这样直接断言，
 * 而不用去读一遍调度器再推断它的意图。
 */
class AdmissionDispatcherTest {

    private static AdmissionDispatcher dispatcher(double factor, int maxBatch) {
        QueueProperties properties = new QueueProperties();
        properties.setAdmitFactor(factor);
        properties.setMaxBatch(maxBatch);
        // QueueService 只被 dispatch() 用到，纯函数这条路径不碰它。
        return new AdmissionDispatcher(null, properties);
    }

    @Test
    @DisplayName("超发系数：剩余 100 个座位、还没有人在飞，放 150 人")
    void admitsMoreThanTheRemainingStock() {
        // 1.5 不是随手取的：被放进来的人并不是个个都会下单 —— 有人犹豫、有人
        // 关掉标签页、有人发现想要的座位没了就走了。严格按座位数放人，最后几个
        // 座位就卖不掉。
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        assertThat(dispatcher.computeQuota(100, 0)).isEqualTo(150);
    }

    @Test
    @DisplayName("已经在飞的人要扣掉，否则调度器每醒一次就再放一批")
    void subtractsThoseAlreadyAdmitted() {
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        // 目标是 150，已经有 100 人在飞，这一轮只能再放 50 个。
        assertThat(dispatcher.computeQuota(100, 100)).isEqualTo(50);
    }

    @Test
    @DisplayName("在飞人数超过目标就一个都不放，不是放负数")
    void neverGoesNegative() {
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        // 一个已经超发的队列，就该原地不动。返负数会让 ZPOPMIN 收到一个
        // 没有意义的参数。
        assertThat(dispatcher.computeQuota(100, 200)).isZero();
    }

    @Test
    @DisplayName("没票了就不放人，不管队列里排了多少")
    void admitsNobodyWhenNothingIsLeft() {
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        assertThat(dispatcher.computeQuota(0, 0)).isZero();
        // 剩余为负是数据出问题时才会有的值，同样不能放人。
        assertThat(dispatcher.computeQuota(-5, 0)).isZero();
    }

    @Test
    @DisplayName("单轮有上限，大批量的场次不会一次涌进来")
    void oneRoundIsCapped() {
        // 目标是 1.5 万，但一轮最多 500 —— 否则场馆一大，放行会在一个 tick 里
        // 把所有人同时推给座位服务，而那正是排队要避免的事。
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        assertThat(dispatcher.computeQuota(10_000, 0)).isEqualTo(500);
    }

    @Test
    @DisplayName("目标数向上取整：剩 1 个座位时也要放 2 个人，而不是 1 个")
    void roundsUpSoALastSeatIsNotLeftUnsold() {
        AdmissionDispatcher dispatcher = dispatcher(1.5, 500);

        // ceil(1 × 1.5) = 2。向下取整会得到 1，而如果那一个人没买，
        // 最后一个座位就永远卖不掉了。
        assertThat(dispatcher.computeQuota(1, 0)).isEqualTo(2);
    }

    @Test
    @DisplayName("系数为 1.0 时严格按座位数放人")
    void factorOfOneAdmitsExactlyTheStock() {
        // 这个值本身是错的（票卖不完），但计算必须如实反映配置 ——
        // 把取舍藏在算术里，比把取舍写在配置里更糟。
        AdmissionDispatcher dispatcher = dispatcher(1.0, 500);

        assertThat(dispatcher.computeQuota(100, 0)).isEqualTo(100);
    }
}
