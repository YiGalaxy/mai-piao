import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/session'

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue'),
    meta: { title: '首页' }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { title: '注册', guestOnly: true }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { title: '我的', requiresAuth: true }
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('../views/CouponView.vue'),
    meta: { title: '我的优惠券', requiresAuth: true }
  },
  {
    // Anything unrecognised goes home rather than showing a blank screen.
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * Route guard.
 *
 * This is a UX guard, not a security boundary. Everything it protects is also
 * protected server-side by the gateway - a user who edits localStorage or
 * calls the API directly still gets a 401. Its job is to avoid showing a
 * screen that is guaranteed to fail.
 */
router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 麦票` : '麦票'

  const loggedIn = Boolean(getToken())

  if (to.meta.requiresAuth && !loggedIn) {
    // Keep the destination so login can return there.
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.guestOnly && loggedIn) {
    return { name: 'home' }
  }

  return true
})

export default router
