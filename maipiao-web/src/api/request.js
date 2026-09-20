import axios from 'axios'
import { showToast } from 'vant'
import router from '../router'
import { clearSession, getToken } from '../utils/session'

/**
 * The single axios instance the app uses.
 *
 * Two conventions this enforces, so no caller has to remember them:
 *
 * 1. Unwrap the envelope. Every backend response is
 *    `{code, message, data}`, where `code: 0` means success. Callers get
 *    `data` directly and never write `res.data.data` again.
 *
 * 2. A failed business call rejects. The backend returns HTTP 200 for
 *    expected failures (seat taken, coupon unusable) with a non-zero code;
 *    without this, `await login()` would resolve on a wrong password and the
 *    caller would happily continue with `undefined`.
 *
 * 401 is handled centrally: the token is dropped and the user is sent to the
 * login page. That is the one failure every screen would otherwise repeat.
 */
const request = axios.create({
  // Relative, so the Vite dev proxy (and Nginx in production) handles it.
  // The browser never needs to know the gateway's address.
  baseURL: '/api',
  timeout: 15000
})

request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response) => {
    const body = response.data

    // Non-envelope response (a file download, or a service that does not use
    // the shared handler). Hand it back untouched rather than mangling it.
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code === 0) {
      return body.data
    }

    handleUnauthorized(body.code)
    showToast(body.message || '请求失败')
    return Promise.reject(new Error(body.message || 'request failed'))
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      handleUnauthorized(401)
      return Promise.reject(error)
    }

    const message =
      status === 503
        ? '服务暂时不可用，请稍后重试'
        : error.code === 'ECONNABORTED'
          ? '请求超时，请检查网络'
          : '网络异常，请稍后重试'

    showToast(message)
    return Promise.reject(error)
  }
)

function handleUnauthorized(code) {
  if (code !== 401) {
    return
  }
  clearSession()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    // Remember where the user was, so login can send them back.
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
}

export default request
