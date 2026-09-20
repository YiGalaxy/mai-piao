import request from './request'

/**
 * seat-service 的接口，都是经网关访问的。
 *
 * 座位图是公开的 —— 看哪些座位空着不该要求登录。锁座则必须登录，因为占座
 * 要记在某个用户名下，将来也得有个人能让它释放。
 */

/** 某场次的完整座位图：布局、价格，以及每个座位的可售状态。 */
export function fetchSeatMap(scheduleId) {
  return request.get(`/seat/map/${scheduleId}`)
}

/**
 * 为当前用户锁下座位。
 *
 * @param {{scheduleId: number, seatIndexes: number[]}} payload
 * @returns {Promise<{lockToken: string, seatLabels: string[], amount: number, expireSeconds: number}>}
 */
export function lockSeats(payload) {
  return request.post('/seat/lock', payload)
}

/** 用户没付款就离开时，把占的座还回去。 */
export function releaseSeats(scheduleId, lockToken) {
  return request.post('/seat/release', { scheduleId }, { params: { lockToken } })
}

/**
 * 不指定座位，让系统来分配。
 *
 * 用于不提供座位图的场次 —— 体育场演唱会没法让每个人去翻两千个座位 ——
 * 买家只选票价档位和张数。返回结构和锁座完全一致，所以结算页不关心
 * 这个锁是哪条路径产生的。
 *
 * @param {{scheduleId: number, tierId: number, quantity: number, adjacent?: boolean}} payload
 * @returns {Promise<{lockToken: string, seatIndexes: number[], seatLabels: string[], amount: number}>}
 */
export function assignSeats(payload) {
  return request.post('/seat/assign', payload)
}
