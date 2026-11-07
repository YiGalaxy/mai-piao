import request from './request'

/**
 * user-service 的接口，都是经网关访问的。
 *
 * 路径相对于 axios 的 baseURL（/api），所以这里写的是网关的路径，
 * 不是服务本身的路径。
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

/** 这个金额的订单能用的优惠券（门槛过滤在服务端做）。 */
export function fetchAvailableCoupons(amount) {
  return request.get('/user/coupon/available', { params: { amount } })
}

/** 用户手上的全部优惠券，包括已使用和已过期的。 */
export function fetchMyCoupons() {
  return request.get('/user/coupon/list')
}
