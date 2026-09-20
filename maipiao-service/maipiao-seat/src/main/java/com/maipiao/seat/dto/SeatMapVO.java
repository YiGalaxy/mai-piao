package com.maipiao.seat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 选座页面需要的所有东西，一次响应给全。
 *
 * <p>布局（排、列、过道）来自厅的模板；座位状态来自 Redis。把它们一起发出去，是为了
 * 在用户正在等待的那一个瞬间省掉第二次往返。
 */
@Data
public class SeatMapVO {

    private Long scheduleId;

    private String projectTitle;
    private String venueName;
    private String placeName;
    private String placeType;

    private LocalDateTime startTime;
    private BigDecimal price;

    /** 厅的几何数据，用来摆放整个网格，包括其中的过道。 */
    private Integer rowCount;
    private Integer colCount;
    private List<Integer> aisleCols;

    private List<SeatItem> seats;

    /**
     * 本场次的票档。
     *
     * <p>电影恰好只有一个，覆盖全部座位；演出有多个，客户端按票档给座位图着色。发这个
     * 列表而不是每个座位带一个价格，是为了让响应体小一些 —— 几百个座位共用三个票档，
     * 否则同样三个对象要重复几百遍。
     *
     * <p>对于早于分档定价出现的场次，这里是空的，客户端把它当作"全都一个价"处理。
     */
    private List<TierItem> tiers;

    private Integer totalSeat;
    private Integer remainingSeat;

    /** 1 = 抢购场次，意味着客户端必须先拿到一个排队令牌。 */
    private Integer rushMode;

    /** 抢购开始的时间；不是抢购场次时为 null。 */
    private LocalDateTime rushStartTime;

    /**
     * 谁来挑座位：0 = 买家，1 = 系统。
     *
     * <p>这和 {@link #seatingMode} 问的不是同一件事。一个有座位的体育场，seatingMode
     * 是 SEATED，但它照样由系统分配座位，因为让十万人同时浏览一张座位图，不是任何团队
     * 跑得起来的服务。客户端读这个字段来决定到底要不要画座位图。
     */
    private Integer seatMode;

    /** SEATED / STANDING。站席场次没有座位图可画。 */
    private String seatingMode;

    /** 每单最多买几张票；0 表示不限。 */
    private Integer purchaseLimit;

    /** 1 = 每张票都必须登记一名观演人。 */
    private Integer requireRealName;

    /** 开票时间，给还没开售的场次用。 */
    private LocalDateTime saleStartTime;

    /**
     * 一个票档。
     *
     * @param id    票档 id；座位引用它
     * @param color 十六进制色值，用来给本票档的座位着色
     */
    public record TierItem(
            Long id,
            String name,
            java.math.BigDecimal price,
            String color
    ) {
    }

    /**
     * 座位图上的一个座位。
     *
     * @param seatId    "{row}_{col}"，稳定且人能看懂
     * @param seatIndex 在 bitmap 中的偏移；锁座调用回传的就是它
     * @param type      0 普通，1 情侣座，2 无障碍座
     * @param status    0 可选，1 已被占用（已锁或已售 —— 客户端不需要区分，也不应该
     *                  区分，因为告诉买家"有人把它放进购物车了"只会招来不停刷新，
     *                  一直刷到它空出来为止）
     * @param tierId    该座位属于哪个票档；从 {@link #tiers} 里解析
     */
    public record SeatItem(
            String seatId,
            int seatIndex,
            int row,
            int col,
            int type,
            int status,
            Long tierId
    ) {
    }
}
