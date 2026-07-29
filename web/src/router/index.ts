import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import FlowScreen from '@/views/FlowScreen.vue'
import PublicSite from '@/views/PublicSite.vue'
import UserConsole from '@/views/UserConsole.vue'
import AdminLayout from '@/components/AdminLayout.vue'
import AdminConsole from '@/views/AdminConsole.vue'
import AdminLogin from '@/views/AdminLogin.vue'
import AdminOtherServices from '@/views/AdminOtherServices.vue'
import AdminVmCardTest from '@/views/AdminVmCardTest.vue'
import { getToken, getUser } from '@/utils/auth'

export const shopGptEnabled = import.meta.env.VITE_ENABLE_SHOPGPT === 'true'
const ProductItem = () => import('@/views/ProductItem.vue')
const CreativeStudio = () => import('@/views/CreativeStudio.vue')
const PaymentLinkService = () => import('@/views/PaymentLinkService.vue')
const Service07Subscription = () => import('@/views/Service07Subscription.vue')

const adminChild = (path: string, module: string, title: string): RouteRecordRaw => ({
  path,
  component: AdminConsole,
  props: { module },
  meta: { title, role: 'admin' }
})

const routes: RouteRecordRaw[] = [
  ...(shopGptEnabled
    ? [
        { path: '/item', component: ProductItem, meta: { title: 'GPT RT Plus 成品卡', role: 'user' } },
        { path: '/item/68', redirect: '/item' }
      ] as RouteRecordRaw[]
    : [
        { path: '/item', redirect: '/pricing' },
        { path: '/item/68', redirect: '/pricing' }
      ] as RouteRecordRaw[]),
  { path: '/', component: PublicSite, meta: { title: '首页', role: 'public' } },
  { path: '/studio', component: CreativeStudio, meta: { title: 'AI 创作工作台', role: 'public' } },
  { path: '/market', component: PublicSite, meta: { title: '模型市场', role: 'public' } },
  { path: '/services/7', component: Service07Subscription, meta: { title: 'Service 07 订阅', role: 'user' } },
  { path: '/services', component: PublicSite, meta: { title: '其他服务', role: 'public' } },
  { path: '/pricing', component: PublicSite, meta: { title: '套餐价格', role: 'public' } },
  { path: '/docs', component: PublicSite, meta: { title: '开发文档', role: 'public' } },
  { path: '/console', component: UserConsole, meta: { title: '用户总览', role: 'user' } },
  { path: '/console/keys', component: UserConsole, meta: { title: 'API Key 管理', role: 'user' } },
  { path: '/console/playground', component: UserConsole, meta: { title: '在线调试', role: 'user' } },
  { path: '/console/logs', component: UserConsole, meta: { title: '用量日志', role: 'user' } },
  { path: '/console/wallet', component: UserConsole, meta: { title: '钱包充值', role: 'user' } },
  { path: '/console/plus-orders', redirect: '/services', meta: { role: 'user' } },
  { path: '/plus', redirect: '/services', meta: { role: 'user' } },
  { path: '/console/orders', redirect: '/services', meta: { role: 'user' } },
  {
    path: '/admin/login',
    component: AdminLogin,
    meta: { title: '管理员登录', role: 'public' }
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { title: '管理员后台', role: 'admin' },
    children: [
      adminChild('', 'dashboard', '运营总览'),
      adminChild('users', 'users', '用户与分组'),
      adminChild('channels', 'channels', '渠道治理'),
      adminChild('models', 'models', '模型与定价'),
      { path: 'mappings', redirect: '/admin/models' },
      adminChild('tokens', 'tokens', 'Token 与权限'),
      adminChild('audit-logs', 'audit', '调用审计'),
      adminChild('finance', 'finance', '钱包财务'),
      { path: 'plus-products', redirect: { path: '/admin/other-services' } },
      { path: 'other-services', component: AdminOtherServices, meta: { title: '服务与订单', role: 'admin' } },
      { path: 'payment-link', component: PaymentLinkService, meta: { title: '支付链接生成', role: 'admin' } },
      { path: 'vmcard-test', component: AdminVmCardTest, meta: { title: 'VMCard 接口测试', role: 'admin' } },
      adminChild('security', 'security', '安全策略'),
      adminChild('settings', 'settings', '系统配置与报表'),
      { path: 'reports', redirect: '/admin/settings' },
      { path: 'oauth', redirect: '/admin/settings' },
      { path: 'integrations', redirect: '/admin/settings' }
    ]
  },
  {
    path: '/login',
    component: FlowScreen,
    props: { screenKey: 'auth' },
    meta: { title: '登录 / 注册 / OAuth', role: 'public' }
  },
  {
    path: '/register',
    component: FlowScreen,
    props: { screenKey: 'auth' },
    meta: { title: '注册账号', role: 'public' }
  },
  { path: '/signup', redirect: '/register' },
  {
    path: '/oauth/callback/:provider',
    component: FlowScreen,
    props: { screenKey: 'auth' },
    meta: { title: '登录 / 注册 / OAuth', role: 'public' }
  },
  { path: '/admin/plus-orders', redirect: { path: '/admin/other-services', query: { tab: 'orders' } } },
  { path: '/admin/finished-products', redirect: { path: '/admin/other-services' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    const user = getUser()
    if (!getToken() || user?.role !== 'ADMIN') {
      return {
        path: '/admin/login',
        query: { redirect: to.fullPath }
      }
    }
  }

  const role = to.meta.role
  if (role === 'user' && !getToken()) {
    return {
      path: '/login',
      query: { redirect: to.fullPath }
    }
  }

  if (to.path === '/admin/login' && getToken() && getUser()?.role === 'ADMIN') {
    return '/admin'
  }
})

router.afterEach(to => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : 'API Transit Station'
  document.title = `${title} - API Transit Station`
})

export default router
