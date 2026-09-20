import request from './request'

/**
 * user-service endpoints, as reached through the gateway.
 *
 * Paths are relative to the axios baseURL (/api), so what is written here is
 * the gateway's path, not the service's.
 */

export function login(payload) {
  return request.post('/user/login', payload)
}

export function register(payload) {
  return request.post('/user/register', payload)
}

export function logout() {
  return request.post('/user/logout')
}

export function fetchProfile() {
  return request.get('/user/info')
}

/** Coupons usable for an order of this amount (filters by threshold server-side). */
export function fetchAvailableCoupons(amount) {
  return request.get('/user/coupon/available', { params: { amount } })
}

/** Every coupon the user holds, including used and expired ones. */
export function fetchMyCoupons() {
  return request.get('/user/coupon/list')
}
