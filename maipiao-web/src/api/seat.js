import request from './request'

/**
 * seat-service endpoints, as reached through the gateway.
 *
 * The seat map is public - looking at which seats are free should not require
 * an account. Locking requires one, because a hold is attributed to a user and
 * there has to be somebody to release it for.
 */

/** Full seat map for a screening: layout, price, and per-seat availability. */
export function fetchSeatMap(scheduleId) {
  return request.get(`/seat/map/${scheduleId}`)
}

/**
 * Claims seats for the current user.
 *
 * @param {{scheduleId: number, seatIndexes: number[]}} payload
 * @returns {Promise<{lockToken: string, seatLabels: string[], amount: number, expireSeconds: number}>}
 */
export function lockSeats(payload) {
  return request.post('/seat/lock', payload)
}

/** Gives a hold back when the user leaves without paying. */
export function releaseSeats(scheduleId, lockToken) {
  return request.post('/seat/release', { scheduleId }, { params: { lockToken } })
}
