package com.maipiao.order.mapper;

import com.maipiao.order.entity.RefundRequest;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RefundRequestMapper {
    @Insert("INSERT INTO t_order_refund_request(order_no,amount) VALUES(#{orderNo},#{amount})")
    int insert(@Param("orderNo") String orderNo, @Param("amount") BigDecimal amount);

    @Select("SELECT * FROM t_order_refund_request WHERE order_no=#{orderNo}")
    RefundRequest find(String orderNo);

    @Select(
            "SELECT * FROM t_order_refund_request WHERE status=0 AND next_retry_time<=NOW(3) ORDER BY next_retry_time LIMIT 50")
    List<RefundRequest> pending();

    @Update(
            "UPDATE t_order_refund_request SET status=1,refund_no=#{refundNo},last_error=NULL WHERE order_no=#{orderNo} AND status=0")
    int sent(@Param("orderNo") String orderNo, @Param("refundNo") String refundNo);

    @Update(
            "UPDATE t_order_refund_request SET attempts=attempts+1,next_retry_time=DATE_ADD(NOW(3),INTERVAL 30 SECOND),last_error=#{error} WHERE order_no=#{orderNo} AND status=0")
    int failed(@Param("orderNo") String orderNo, @Param("error") String error);
}
