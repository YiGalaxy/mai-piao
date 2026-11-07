import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/session'

const routes = [
  { path: '/', redirect: '/performances' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  {
    path: '/performances',
    name: 'performances',
    component: () => import('../views/PerformanceListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/performances/new',
    name: 'performance-new',
    component: () => import('../views/PerformanceEditView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/performances/:id',
    name: 'performance-detail',
    component: () => import('../views/PerformanceDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/venues',
    name: 'venues',
    component: () => import('../views/VenueListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/users',
    name: 'users',
    component: () => import('../views/UserListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrderListView.vue'),
    meta: { requiresAuth: true }
  },
  { path: '/:pathMatch(.*)*', redirect: '/performances' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 给没登录的人一个重定向，不是安全边界。
 *
 * localStorage 里的 token 完全说明不了它是不是管理员的，这个守卫也不假装去查。
 * 网关会拒绝非管理员 token 调管理端 API，所以绕过这里的人看到的会是空页面和
 * 报错 —— 这正是应有的结果，也是真正的检查放在那边的理由。
 */
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
