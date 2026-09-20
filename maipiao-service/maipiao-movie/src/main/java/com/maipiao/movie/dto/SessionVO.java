package com.maipiao.movie.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一场排片，连同客户端渲染它需要的那些名称。
 *
 * <p>join 放在一条查询里做，而不是拆成后续几次调用，因为排期列表被读得太频繁
 * （每个影院页、每个影片页），否则就是跨三张表的 N+1 —— 而这些数据在一次请求期间
 * 根本不会变。
 *
 * <p>座位只以数量的形式暴露 —— 逐座位的细节在 Redis 里，等用户真的打开座位图时
 * 再单独取。
 */
@Data
public class SessionVO {

    private Long id;

    private Long projectId;
    private String projectTitle;

    /** MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL。 */
    private String category;
    private Integer duration;
    private String posterUrl;

    private Long venueId;
    private String venueName;

    private Long placeId;
    private String placeName;
    private String placeType;

    private LocalDate showDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private BigDecimal price;

    private Integer totalSeat;
    private Integer lockedSeat;
    private Integer soldSeat;

    private Integer status;
    private Integer rushMode;
    private LocalDateTime rushStartTime;

    /** 0 = 买家选座，1 = 系统按票价档分配。 */
    private Integer seatMode;

    /** 开票时间，给还没开卖的场次用。 */
    private LocalDateTime saleStartTime;

    /** 推导出来的，不存储：total - locked - sold，下限为 0。 */
    private Integer remainingSeat;
}
