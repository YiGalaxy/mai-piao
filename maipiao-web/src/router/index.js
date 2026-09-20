import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/session'

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue')
  },
  {
    path: '/films',
    name: 'films',
    component: () => import('../views/FilmListView.vue')
  },
  {
    path: '/films/:id',
    name: 'film-detail',
    component: () => import('../views/FilmDetailView.vue')
  },
  {
    path: '/schedules/:id/seats',
    name: 'seat-select',
    component: () => import('../views/SeatSelectView.vue'),
    meta: { requiresAuth: true }
  },
  // 另一种买座方式：由场馆来分配座位。做成独立路由而不是座位图里的一个
  // 模式，因为这两个页面没有任何共享状态 —— 一个拉座位图并跟踪选中项，
  // 另一个拉票价档位和张数 —— 而且两者的选择在任一页面加载之前就已经定了。
  {
    path: '/schedules/:id/tickets',
    name: 'ticket-select',
    component: () => import('../views/TicketSelectView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/schedules/:id/queue',
    name: 'rush-queue',
    component: () => import('../views/QueueView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/checkout',
    name: 'checkout',
    component: () => import('../views/CheckoutView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrderListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/orders/:orderNo',
    name: 'order-detail',
    component: () => import('../views/OrderDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { guestOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { guestOnly: true }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('../views/CouponView.vue'),
    meta: { requiresAuth: true }
  },
  {
    // 认不出来的路径一律回首页，而不是给一张白页。
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
 * 路由守卫。
 *
 * 这是体验层的守卫，不是安全边界。它挡住的每一件事，网关在服务端也都拦着 ——
 * 改 localStorage 也好、直接打 API 也好，照样是 401。它的职责只是别把一个
 * 注定会失败的页面显示出来。
 */
router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 麦票` : '麦票'

  const loggedIn = Boolean(getToken())

  if (to.meta.requiresAuth && !loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.guestOnly && loggedIn) {
    return { name: 'home' }
  }

  return true
})

export default router
