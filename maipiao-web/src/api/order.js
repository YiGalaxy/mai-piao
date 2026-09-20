import request from './request'

/**
 * order-service endpoints, as reached through the gateway.
 */

/**
 * Creates an order for seats the caller already holds.
 *
 * <p>Returns a failure when the hold has lapsed or somebody took a seat in the
 * meantime, so callers must not assume success.
 *
 * @param {{lockToken: string, scheduleId: string, seatIndexes: number[],
 *          seatLabels: string[], couponId?: string, discountAmount?: number}} payload
 */
export function createOrder(payload) {
  return request.post('/order/create', payload)
}

/** @param {string} [status] omit for all */
export function fetchOrders(status) {
  return request.get('/order/list', { params: status === undefined ? {} : { status } })
}

export function fetchOrderDetail(orderNo) {
  return request.get(`/order/${orderNo}`)
}

/**
 * Cancels an unpaid order and frees its seats.
 *
 * @returns {Promise<boolean>} false when the order was already cancelled -
 *          typically the timeout job firing just before the user pressed it,
 *          which is not an error worth showing.
 */
export function cancelOrder(orderNo) {
  return request.post(`/order/${orderNo}/cancel`)
}
