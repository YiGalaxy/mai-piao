import request from './request'

/**
 * 抢票排队的接口。
 *
 * 只有 rushMode = 1 的场次才走这里。普通场次直接卖，从不碰队列，
 * 所以应用里没有别的地方会调这些接口。
 */

/**
 * 排入某场次的队列。
 *
 * 幂等：队列是一个以用户 id 为成员的 sorted set，所以重复排队拿到的是同一个
 * 位置，而不会多出一条记录。正因如此，等待页每次 mount 都能直接调它，
 * 不必先判断有没有排过。
 */
export function joinQueue(scheduleId) {
  return request.post('/queue/join', null, { params: { scheduleId } })
}

/**
 * 调用者现在排到什么位置了。
 *
 * @returns {Promise<{status: string, rank: number, ahead: number, total: number,
 *                    token: string, expiresIn: number}>}
 *          status 取值为 WAITING | PASSED | SOLD_OUT | PAUSED | NOT_STARTED
 */
export function fetchQueuePosition(scheduleId) {
  return request.get('/queue/position', { params: { scheduleId } })
}

/** 退出队列。尽力而为 —— 已经发出的 token 仍然有效。 */
export function leaveQueue(scheduleId) {
  return request.post('/queue/leave', null, { params: { scheduleId } })
}
