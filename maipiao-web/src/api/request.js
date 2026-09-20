import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { clearSession, getToken } from '../utils/session'

/**
 * 全应用共用的那一个 axios 实例。
 *
 * 它强制了两条约定，调用方不必自己记着：
 *
 * 1. 拆信封。后端每个响应都是 `{code, message, data}`，`code: 0` 表示成功。
 *    调用方直接拿到 `data`，永远不用写 `res.data.data`。
 *
 * 2. 业务调用失败就 reject。对于可预期的失败（座位被占、优惠券不可用），
 *    后端返回的是 HTTP 200 加一个非 0 的 code；不这样处理的话，
 *    `await login()` 在密码错误时照样会 resolve，调用方就拿着 `undefined`
 *    继续往下走了。
 *
 * 401 统一处理：丢掉会话，把用户送回登录页，并记住他原来在哪。
 */
const request = axios.create({
  // 用相对路径，交给 Vite 开发代理（生产环境则是 Nginx）去转发。
  // 浏览器始终不需要知道网关的地址。
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

    // 不是信封格式（比如文件下载，或者某个没用统一处理器的服务）。
    // 原样交回去，别去动它。
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code === 0) {
      return body.data
    }

    handleUnauthorized(body.code)
    ElMessage.error(body.message || '请求失败')

    // 业务 code 跟着 rejection 一起带出去。失败跟失败并不是同一回事 ——
    // 「这一档里没有连座了」是个正常结果，而且有补救办法，不是弹个提示
    // 就完事的错误 —— 而只能看到 message 的调用方，就得去匹配文案
    // 才能把它们区分开。
    const failure = new Error(body.message || 'request failed')
    failure.code = body.code
    failure.data = body.data
    return Promise.reject(failure)
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

    ElMessage.error(message)
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
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
}

export default request
