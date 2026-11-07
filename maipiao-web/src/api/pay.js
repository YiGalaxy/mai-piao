import request from './request'

/**
 * pay-service 的接口，都是经网关访问的。
 *
 * <p>回调刻意不放在这里：提供商往入驻时给它的那个 URL 上推，那个 URL 直接
 * 指向 pay-service。支付通知不走我们的网关。
 */

/**
 * 为订单创建（或复用）支付单，并返回该把用户送到哪儿去。
 *
 * 按订单幂等：刷新支付页会复用已有的支付单，而不是再开一个可能被并行支付的
 * 新支付单。
 */
export function precreatePayment(payload) {
  return request.post('/pay/precreate', payload)
}

/** 支付状态，供提供商处理期间轮询。 */
export function fetchPayment(paymentNo) {
  return request.get(`/pay/query/${paymentNo}`)
}

/** 取某订单的支付单，订单页不用先拿到支付单号就能找到它。 */
export function fetchPaymentByOrder(orderNo) {
  return request.get(`/pay/order/${orderNo}`)
}
