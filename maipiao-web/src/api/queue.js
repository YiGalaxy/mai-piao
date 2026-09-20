import request from './request'

/**
 * Rush-sale queue endpoints.
 *
 * Only screenings with rushMode = 1 go through here. A normal screening sells
 * straight through and never touches the queue, which is why nothing else in
 * the app calls these.
 */

/**
 * Joins the line for a screening.
 *
 * Idempotent: the queue is a sorted set keyed by user id, so joining again
 * returns the same place rather than adding a second entry. That is what lets
 * the waiting page call this on every mount without checking first.
 */
export function joinQueue(scheduleId) {
  return request.post('/queue/join', null, { params: { scheduleId } })
}

/**
 * Where the caller stands.
 *
 * @returns {Promise<{status: string, rank: number, ahead: number, total: number,
 *                    token: string, expiresIn: number}>}
 *          status is WAITING | PASSED | SOLD_OUT | PAUSED | NOT_STARTED
 */
export function fetchQueuePosition(scheduleId) {
  return request.get('/queue/position', { params: { scheduleId } })
}

/** Leaves the line. Best-effort - a token already issued keeps working. */
export function leaveQueue(scheduleId) {
  return request.post('/queue/leave', null, { params: { scheduleId } })
}
