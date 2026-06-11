import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import Auth from '../views/Auth.vue'
import ModelMarket from '../views/ModelMarket.vue'
import UserLayout from '../components/UserLayout.vue'
import AdminLayout from '../components/AdminLayout.vue'
import Channels from '../views/Channels.vue'
import ModelMappings from '../views/ModelMappings.vue'
import AdminTokens from '../views/Tokens.vue'
import CommandCenter from '../views/CommandCenter.vue'
import PlusStore from '../views/PlusStore.vue'
import AdminPlusOrders from '../views/AdminPlusOrders.vue'
import AdminFinishedProducts from '../views/AdminFinishedProducts.vue'

const routes = [
  {
    path: '/',
    component: UserLayout,
    children: [
      { path: '', component: Home },
      { path: 'market', component: ModelMarket },
      {
        path: 'plus',
        component: PlusStore,
        meta: { requiresAuth: true }
      },
      { path: 'login', component: Auth },
      { path: 'register', component: Auth },
      { path: 'oauth/callback/:provider', component: Auth },
      {
        path: 'console',
        component: CommandCenter,
        meta: { requiresAuth: true }
      }
    ]
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/channels' },
      { path: 'channels', component: Channels },
      { path: 'tokens', component: AdminTokens },
      { path: 'mappings', component: ModelMappings },
      { path: 'finished-products', component: AdminFinishedProducts },
      { path: 'plus-orders', component: AdminPlusOrders }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const userStr = localStorage.getItem('user')
  const user = userStr ? JSON.parse(userStr) : null

  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.meta.requiresAdmin && (!token || user?.role !== 'ADMIN')) {
    next('/')
  } else {
    next()
  }
})

export default router
