import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken, getUser } from '@/utils/auth'

const FlowScreen=()=>import('@/views/FlowScreen.vue'),UserConsole=()=>import('@/views/UserConsole.vue')
const AdminLayout=()=>import('@/components/AdminLayout.vue'),AdminConsole=()=>import('@/views/AdminConsole.vue'),AdminLogin=()=>import('@/views/AdminLogin.vue')
const AdminOtherServices=()=>import('@/views/AdminOtherServices.vue'),AdminVmCardTest=()=>import('@/views/AdminVmCardTest.vue')
const ModelGateway=()=>import('@/views/ModelGateway.vue'),AdminCreativeConfig=()=>import('@/views/AdminCreativeConfig.vue')
const LegalPage=()=>import('@/views/LegalPage.vue')
const PublicLayout=()=>import('@/layouts/PublicLayout.vue')
const HomePage=()=>import('@/views/HomePage.vue')
const ModelMarket=()=>import('@/views/ModelMarket.vue')
const OtherServices=()=>import('@/views/OtherServices.vue')
const PricingPage=()=>import('@/views/PricingPage.vue')
const DocsPage=()=>import('@/views/DocsPage.vue')

export const shopGptEnabled = import.meta.env.VITE_ENABLE_SHOPGPT === 'true'
const ProductItem = () => import('@/views/ProductItem.vue')
const CreativeStudio = () => import('@/views/CreativeStudio.vue')
const OrganizationConsole = () => import('@/views/OrganizationConsole.vue')
const CardRedemptionRedirect = () => import('@/views/CardRedemptionRedirect.vue')

const adminChild = (path: string, module: string, title: string): RouteRecordRaw => ({
  path,
  component: AdminConsole,
  props: { module },
  meta: { title, role: 'admin' }
})

const routes: RouteRecordRaw[] = [
  ...(shopGptEnabled
    ? [
        { path: '/item', component: ProductItem, meta: { title: 'GPT RT Plus 成品号', role: 'user' } },
        { path: '/item/68', redirect: '/item' }
      ] as RouteRecordRaw[]
    : [
        { path: '/item', redirect: '/pricing' },
        { path: '/item/68', redirect: '/pricing' }
      ] as RouteRecordRaw[]),
  {
    path: '/',
    component: PublicLayout,
    children: [
      { path: '', component: HomePage, meta: { title: '首页', role: 'public' } },
      { path: 'market', component: ModelMarket, meta: { title: '模型市场', role: 'public' } },
      { path: 'services', component: OtherServices, meta: { title: '成品服务', role: 'public' } },
      { path: 'pricing', component: PricingPage, meta: { title: '套餐价格', role: 'public' } },
      { path: 'docs', component: DocsPage, meta: { title: '开发文档', role: 'public' } }
    ]
  },
  { path: '/studio', component: CreativeStudio, meta: { title: 'AI 创作工作台', role: 'public' } },
  { path: '/services/:id/redeem', component: CardRedemptionRedirect, meta: { title: '卡密兑换', role: 'public' } },
  { path: '/terms', component: LegalPage, meta: { title: '用户协议', role: 'public', legalKind: 'terms' } },
  { path: '/privacy', component: LegalPage, meta: { title: '隐私政策', role: 'public', legalKind: 'privacy' } },
  { path: '/console', component: UserConsole, meta: { title: '用户总览', role: 'user' } },
  { path: '/console/keys', component: UserConsole, meta: { title: 'API Key 管理', role: 'user' } },
  { path: '/console/playground', component: UserConsole, meta: { title: '在线调试', role: 'user' } },
  { path: '/console/logs', component: UserConsole, meta: { title: '用量日志', role: 'user' } },
  { path: '/console/wallet', component: UserConsole, meta: { title: '钱包充值', role: 'user' } },
  { path: '/console/profile', component: UserConsole, meta: { title: '个人中心', role: 'user' } },
  { path: '/console/docs', component: UserConsole, meta: { title: '开发文档', role: 'user' } },
  { path: '/console/organization', component: OrganizationConsole, meta: { title: '企业账户', role: 'user' } },
  { path: '/console/orders', redirect: '/services', meta: { role: 'user' } },
  { path: '/admin/login', component: AdminLogin, meta: { title: '管理员登录', role: 'public' } },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { title: '管理后台', role: 'admin' },
    children: [
      adminChild('', 'dashboard', '运营总览'),
      adminChild('users', 'users', '用户与分组'),
      { path: 'model-gateway', component: ModelGateway, meta: { title: '模型网关', role: 'admin' } },
      { path: 'creative-config', component: AdminCreativeConfig, meta: { title: 'AI 创作配置', role: 'admin' } },
      { path: 'channels', redirect: { path: '/admin/model-gateway', query: { tab: 'channels' } } },
      { path: 'models', redirect: { path: '/admin/model-gateway', query: { tab: 'models' } } },
      { path: 'mappings', redirect: { path: '/admin/model-gateway', query: { tab: 'models' } } },
      adminChild('tokens', 'tokens', 'Token 与权限'),
      adminChild('audit-logs', 'audit', '调用审计'),
      adminChild('finance', 'finance', '钱包财务'),
      { path: 'other-services', component: AdminOtherServices, meta: { title: '服务与订单', role: 'admin' } },
      { path: 'vmcard-test', component: AdminVmCardTest, meta: { title: 'VMCard 接口测试', role: 'admin' } },
      adminChild('security', 'security', '安全策略'),
      adminChild('settings', 'settings', '系统配置与报表'),
      { path: 'reports', redirect: '/admin/settings' },
      { path: 'oauth', redirect: '/admin/settings' },
      { path: 'integrations', redirect: '/admin/settings' }
    ]
  },
  { path: '/login', redirect: to => ({ path: '/', query: { ...to.query, auth: 'login' } }) },
  { path: '/register', redirect: to => ({ path: '/', query: { ...to.query, auth: 'register' } }) },
  { path: '/signup', redirect: '/register' },
  {
    path: '/oauth/callback/:provider',
    component: FlowScreen,
    props: { screenKey: 'auth' },
    meta: { title: 'OAuth 登录', role: 'public' }
  },
  { path: '/admin/finished-products', redirect: '/admin/other-services' },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    const user = getUser('admin')
    if (!getToken('admin') || user?.role !== 'ADMIN') {
      return { path: '/admin/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.role === 'user' && !getToken('user')) {
    return { path: '/', query: { auth: 'login', redirect: to.fullPath } }
  }
  return true
})

router.afterEach(to => {
  document.title = `${String(to.meta.title || 'API Transit')} - API Transit`
})

export default router
