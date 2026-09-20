import { defineStore } from 'pinia'
import * as userApi from '../api/user'
import {
  clearSession,
  getStoredUser,
  getToken,
  setStoredUser,
  setToken
} from '../utils/session'

/**
 * 会话状态。
 *
 * 创建时从 localStorage 恢复，这样刷新页面不会把人登出；但 token 本身从不
 * 拿来直接展示 —— 只要不确定 `profile` 是不是最新的，就重新从服务端拉一次。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    profile: getStoredUser()
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.profile?.nickname || '未登录',
    maskedPhone: (state) => {
      const phone = state.profile?.phone
      return phone && phone.length === 11
        ? `${phone.slice(0, 3)}****${phone.slice(7)}`
        : ''
    }
  },

  actions: {
    async login(payload) {
      const data = await userApi.login(payload)
      this.applySession(data)
      return data
    },

    async register(payload) {
      const userId = await userApi.register(payload)
      // 注册接口不返回 token，所以直接就地登录 —— 用户刚设完密码又被要求
      // 再输一遍，是纯粹的摩擦，换不来任何安全性。
      await this.login({ phone: payload.phone, password: payload.password })
      return userId
    },

    /** 重新拉取资料。应用启动时和任何资料变更之后都会调用。 */
    async refreshProfile() {
      const profile = await userApi.fetchProfile()
      this.profile = profile
      setStoredUser(profile)
      return profile
    },

    async logout() {
      try {
        // 尽力而为：服务端会把 jti 加进黑名单。这一步失败了也没关系，
        // 本地会话照样清掉 —— 只是那个 token 会一直有效到自然过期，
        // 而这本来就是原来的行为。
        await userApi.logout()
      } catch {
        // 故意忽略
      } finally {
        this.clear()
      }
    },

    applySession({ token, userId, phone, nickname, avatar }) {
      this.token = token
      this.profile = { id: userId, phone, nickname, avatar }
      setToken(token)
      setStoredUser(this.profile)
    },

    clear() {
      this.token = ''
      this.profile = null
      clearSession()
    }
  }
})
