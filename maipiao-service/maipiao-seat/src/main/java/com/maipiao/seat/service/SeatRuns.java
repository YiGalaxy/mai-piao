package com.maipiao.seat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把一个票档里的座位变成一段段物理上彼此相邻的连座。
 *
 * <p>一段 run 就是分配器被允许当成一次预订发出去的东西，所以"相邻"必须是指在厅里
 * 挨着，而不只是编号上挨着。这是两回事，而且差别一点都不罕见：遇上两列宽的过道，
 * 索引 2 和索引 3 是同一排的第 4 列和第 6 列，把这两个座位当成一次两连座发出去，
 * 就等于把这俩人放在走道两边。
 *
 * <p>所以只要出现任何一种不连续，run 就断开 —— 要么是索引跳了，要么是列号跳了超过 1。
 * 索引检查能抓住从账本里被删掉的座位；列检查能抓住过道。两者单用都不够，而列检查
 * 恰恰是没法从索引推出来的那一个，这才是相邻性要从几何数据里读、而不是算出来的原因。
 *
 * <p>run 从不跨排。一排末尾和下一排开头的座位，在哪个意义上都不相邻，何况它们之间的
 * 通道比任何过道都宽。
 *
 * <p>纯函数：不碰 Spring、不碰数据库、不碰 Redis。它接收位置、返回区间，所以上面那条
 * 规则可以直接被测试，而不必从一次真实分配的运行结果里倒推。
 */
final class SeatRuns {

    /** 厅里彼此相邻的座位构成的一段最长序列。 */
    record Segment(int startIndex, int length) {

        int endIndex() {
            return startIndex + length - 1;
        }
    }

    /** 一个座位在这个票档里的位置。 */
    record SeatRef(int seatIndex, int row, int col) {
    }

    private SeatRuns() {
    }

    /**
     * 某个票档的连座段，按座位应当被发出去的顺序排列。
     *
     * <p>先按排号升序，再按排内位置升序：优先给前排，场馆是这么做的，买家也是这么
     * 预期的。排序规则是个产品决策，而且只存在于这一处 —— 分配器只是照着拿到的顺序
     * 依次尝试这些 run。
     */
    static List<Segment> plan(List<SeatRef> seats) {
        if (seats == null || seats.isEmpty()) {
            return List.of();
        }

        List<SeatRef> ordered = new ArrayList<>(seats);
        ordered.sort(Comparator.comparingInt(SeatRef::row).thenComparingInt(SeatRef::col));

        List<Segment> segments = new ArrayList<>();
        int start = ordered.get(0).seatIndex();
        int length = 1;
        SeatRef previous = ordered.get(0);

        for (int i = 1; i < ordered.size(); i++) {
            SeatRef current = ordered.get(i);

            boolean nextInRow = current.row() == previous.row();
            boolean indexAdjacent = current.seatIndex() == previous.seatIndex() + 1;
            boolean colAdjacent = current.col() == previous.col() + 1;

            if (nextInRow && indexAdjacent && colAdjacent) {
                length++;
            } else {
                segments.add(new Segment(start, length));
                start = current.seatIndex();
                length = 1;
            }
            previous = current;
        }
        segments.add(new Segment(start, length));

        return segments;
    }

    /**
     * 一组段里最长的那个 run。
     *
     * <p>用来回答"最多能有几个人坐在一起"，在请求更多座位失败时给出。报的是分配器
     * 搜索过的同一份列表，所以它给出的数字是分配器真的会兑现的那个。
     */
    static int longest(List<Segment> segments) {
        int longest = 0;
        for (Segment segment : segments) {
            longest = Math.max(longest, segment.length());
        }
        return longest;
    }
}
