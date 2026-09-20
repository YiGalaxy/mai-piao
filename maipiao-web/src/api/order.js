import request from './request'

/**
 * order-service 的接口，经网关访问。
 */

/**
 * 为调用方已经占住的座位创建订单。
 *
 * <p>占座过期、或者期间有座位被别人拿走时都会返回失败，
 * 所以调用方不能假设它一定成功。
 *
 * @param {{lockToken: string, scheduleId: string, seatIndexes: number[],
 *          seatLabels: string[], couponId?: string, discountAmount?: number}} payload
 */
export function createOrder(payload) {
  return request.post('/order/create', payload)
}

/** @param {string} [status] 不传表示全部 */
export function fetchOrders(status) {
  return request.get('/order/list', { params: status === undefined ? {} : { status } })
}

export function fetchOrderDetail(orderNo) {
  return request.get(`/order/${orderNo}`)
}

/**
 * 取消一笔未支付的订单，并释放它的座位。
 *
 * @returns {Promise<boolean>} 返回 false 表示订单已经被取消过了 ——
 *          通常是超时任务恰好比用户早一步，这不是一个值得弹出来吓人的错误。
 */
export function cancelOrder(orderNo) {
  return request.post(`/order/${orderNo}/cancel`)
}

/**
 * 查询这一单现在能不能退。
 *
 * <p>单独一个接口，而不是让前端自己按「距开演是否超过 2 小时」算 ——
 * 退票窗口的性质是场次的，不是用户耐心的，规则必须只有一个地方说了算。
 * 页面拿它来决定按钮是否可点；服务端在真正退款时会再判一次。
 *
 * @returns {Promise<{allowed: boolean, reason: string, amount: number}>}
 */
export function fetchRefundable(orderNo) {
  return request.get(`/order/${orderNo}/refundable`)
}

/**
 * 申请退款。
 *
 * <p>服务端先落一条退款意图、再请求渠道，所以这个调用返回成功只代表
 * 「已经受理」，不代表钱到账了。到账与否看订单状态变成已退款。
 */
export function requestRefund(orderNo, reason) {
  return request.post(`/order/${orderNo}/refund`, null, { params: { reason } })
}
