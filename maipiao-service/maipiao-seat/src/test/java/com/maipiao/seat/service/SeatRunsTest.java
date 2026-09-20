package com.maipiao.seat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连座段的划分规则。
 *
 * <p>这是整个分配功能里最值得单独测的一段，理由有两个。
 *
 * <p>一是它最容易错且错得不明显：判"相邻"如果写成"索引加一"，在没有过道的场馆上
 * 结果完全正确，到了有走道的场馆就会把走道两边的人当成邻居 —— 而这种错误不会报错，
 * 只会让两个人到现场发现座位隔着一条通道。
 *
 * <p>二是它可以脱离一切基础设施来测。{@link SeatRuns} 不碰 Spring、不碰数据库、
 * 不碰 Redis，就是收位置、还区间，所以这条规则能直接断言，而不用从一次真实分配的
 * 结果里倒推。
 */
class SeatRunsTest {

    /** 造一排座位：从第 1 列开始，列号连续。 */
    private static List<SeatRuns.SeatRef> row(int rowNum, int fromCol, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new SeatRuns.SeatRef(rowNum * 100 + fromCol + i, rowNum, fromCol + i))
                .toList();
    }

    @Test
    @DisplayName("同一排里连续的位置属于同一段")
    void contiguousSeatsInOneRowFormOneRun() {
        List<SeatRuns.Segment> runs = SeatRuns.plan(row(1, 1, 5));

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).length()).isEqualTo(5);
    }

    @Test
    @DisplayName("过道把一段切断 —— 索引连续但列号跳了，就不算挨着")
    void anAisleBreaksTheRunEvenWhenIndexesAreConsecutive() {
        // 这是真实数据里的样子：某场馆第 1 行可用列是 2,3,4,6,7,8,9,...
        // 第 5 列是过道，所以索引 2（第 4 列）和索引 3（第 6 列）在编号上挨着，
        // 在房间里隔着一条通道。
        List<SeatRuns.SeatRef> seats = List.of(
                new SeatRuns.SeatRef(0, 1, 2),
                new SeatRuns.SeatRef(1, 1, 3),
                new SeatRuns.SeatRef(2, 1, 4),   // 第 5 列是过道，下一列直接跳到 6
                new SeatRuns.SeatRef(3, 1, 6),
                new SeatRuns.SeatRef(4, 1, 7));

        List<SeatRuns.Segment> runs = SeatRuns.plan(seats);

        // 分成 [索引0..2]（第 2~4 列）和 [索引3..4]（第 6~7 列）两段。
        // 如果按索引相邻判，这里会是一整段 5 个座位 —— 那就会把第 4 列和第 6 列
        // 当成邻居发出去。
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).startIndex()).isEqualTo(0);
        assertThat(runs.get(0).length()).isEqualTo(3);
        assertThat(runs.get(1).startIndex()).isEqualTo(3);
        assertThat(runs.get(1).length()).isEqualTo(2);
    }

    @Test
    @DisplayName("段从不跨排 —— 一排末尾和下一排开头在哪个意义上都不相邻")
    void runsNeverCrossARow() {
        List<SeatRuns.SeatRef> seats = List.of(
                new SeatRuns.SeatRef(0, 1, 1),
                new SeatRuns.SeatRef(1, 1, 2),
                new SeatRuns.SeatRef(2, 2, 1),   // 第 2 排第 1 列
                new SeatRuns.SeatRef(3, 2, 2));

        List<SeatRuns.Segment> runs = SeatRuns.plan(seats);

        assertThat(runs).hasSize(2);
        assertThat(runs).allSatisfy(run -> assertThat(run.length()).isEqualTo(2));
    }

    @Test
    @DisplayName("按排号升序、再按列号升序——前排优先")
    void runsAreOrderedFrontOfHouseFirst() {
        // 故意打乱输入顺序，包括把第 2 排放在第 1 排前面。
        List<SeatRuns.SeatRef> seats = List.of(
                new SeatRuns.SeatRef(20, 2, 1),
                new SeatRuns.SeatRef(10, 1, 2),
                new SeatRuns.SeatRef(11, 1, 3),
                new SeatRuns.SeatRef(21, 2, 2));

        List<SeatRuns.Segment> runs = SeatRuns.plan(seats);

        assertThat(runs).hasSize(2);
        // 第一段是第 1 排的，即使它在输入里排在后面
        assertThat(runs.get(0).startIndex()).isEqualTo(10);
        assertThat(runs.get(1).startIndex()).isEqualTo(20);
    }

    @Test
    @DisplayName("索引跳变也断段——账本里缺了的座位不该被当成紧邻")
    void aGapInTheIndexesAlsoBreaksTheRun() {
        List<SeatRuns.SeatRef> seats = List.of(
                new SeatRuns.SeatRef(0, 1, 1),
                new SeatRuns.SeatRef(1, 1, 2),
                new SeatRuns.SeatRef(5, 1, 3),   // 索引从 1 跳到 5
                new SeatRuns.SeatRef(6, 1, 4));

        List<SeatRuns.Segment> runs = SeatRuns.plan(seats);

        assertThat(runs).hasSize(2);
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInputIsNotAnError() {
        assertThat(SeatRuns.plan(List.of())).isEmpty();
        assertThat(SeatRuns.plan(null)).isEmpty();
    }

    @Test
    @DisplayName("最长段用于告诉买家「最多能有几个人坐一起」")
    void longestReportsWhatTheAllocatorWouldHonour() {
        List<SeatRuns.Segment> runs = List.of(
                new SeatRuns.Segment(0, 2),
                new SeatRuns.Segment(10, 4),
                new SeatRuns.Segment(20, 1));

        assertThat(SeatRuns.longest(runs)).isEqualTo(4);
        assertThat(SeatRuns.longest(List.of())).isZero();
    }
}
