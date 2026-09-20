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

/**
 * Asks the system for seats instead of naming them.
 *
 * For screenings that do not offer a seat map - a stadium concert cannot let
 * everyone browse 2000 seats - the buyer picks a price band and a quantity.
 * The reply has the same shape as a lock, so the checkout does not care which
 * one produced it.
 *
 * @param {{scheduleId: number, tierId: number, quantity: number, adjacent?: boolean}} payload
 * @returns {Promise<{lockToken: string, seatIndexes: number[], seatLabels: string[], amount: number}>}
 */
export function assignSeats(payload) {
  return request.post('/seat/assign', payload)
}
