import { expect, test, type Page } from '@playwright/test'
async function login(page: Page, admin=false) {
  await page.addInitScript(admin=>{
    localStorage.setItem('linknux.locale','zh-CN')
    localStorage.setItem('user_access_token','billing-test');localStorage.setItem('user_info',JSON.stringify({id:1,role:'USER'}));localStorage.setItem('user_last_active_at',String(Date.now()))
    if(admin){sessionStorage.setItem('admin_access_token','admin-test');sessionStorage.setItem('admin_info',JSON.stringify({id:1,role:'ADMIN'}))}
  },admin)
}
test('administrator saves all payment content and checkout displays it as plain text',async({page})=>{
  await login(page,true);let copy: Record<string,string>={title:'完成支付',qrNotice:'支付提示'};let saved=false
  await page.route(url=>url.pathname.startsWith('/api/')||url.origin==='https://api.linknux.com',async route=>{
    const p=new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/,'')
    if(p==='/public/payment-content')return route.fulfill({json:copy})
    if(p==='/admin/api/payment-content'){copy=route.request().postDataJSON();saved=true;return route.fulfill({json:copy})}
    if(p==='/public/legal')return route.fulfill({json:{checkout_ready:true,checkout_disclosure_id:'v1',terms_version:'v1',privacy_version:'v1'}})
    if(p==='/service-orders')return route.fulfill({json:{total:1,page:1,size:10,items:[{id:7,orderNo:'TEST-7',status:'PENDING',paymentMethod:'wxpay',amountCents:100,currency:'CNY'}]}})
    if(p==='/service-orders/7/payment')return route.fulfill({json:{action:{type:'QRCODE',url:'weixin://test-only'}}})
    return route.fulfill({json:{total:0,page:1,size:10,items:[]}})
  })
  await page.goto('/admin/other-services?tab=payment-content')
  await page.getByRole('textbox',{name:'扫码支付提示',exact:true}).fill('自定义提示 <b>请勿重复支付</b>')
  await page.getByRole('textbox',{name:'支付弹窗标题',exact:true}).fill('扫码付款')
  await page.getByRole('textbox',{name:'应付金额标题',exact:true}).fill('实际应付')
  await page.getByRole('button',{name:'保存支付文案'}).click();await expect.poll(()=>saved).toBe(true)
  await page.goto('/services');await page.getByRole('button',{name:'去支付',exact:true}).click()
  const checkout=page.getByRole('dialog',{name:'确认支付',exact:true})
  await checkout.getByText('我已阅读交易条件和退款政策',{exact:true}).click();await checkout.getByRole('button',{name:'确认支付',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'扫码付款',exact:true});await expect(dialog).toBeVisible()
  await expect(dialog.locator('.payment-amount')).toContainText('实际应付')
  await expect(dialog).toContainText('自定义提示 <b>请勿重复支付</b>');await expect(dialog.locator('b')).toHaveCount(0)
})
for(const type of ['service','recharge'])test(`download page opens the exact ${type} order independently of list pagination`,async({page})=>{
  await login(page)
  await page.route(url=>url.pathname.startsWith('/api/')||url.origin==='https://api.linknux.com',async route=>{
    const p=new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/,'')
    if(p.endsWith('/77/receipt'))return route.fulfill({contentType:'application/pdf',body:'%PDF-1.4\n%%EOF'})
    if(p.endsWith('/77'))return route.fulfill({json:{id:77,orderNo:'BILLING-77',status:'PAID',invoiceRequested:false}})
    return route.fulfill({json:{agreementRequired:false}})
  })
  await page.goto(`/billing/${type}/77`);await expect(page.getByText('BILLING-77',{exact:true})).toBeVisible()
  if(type==='recharge')await expect(page.getByRole('button',{name:'下载账单'})).toHaveCount(0)
  else await expect(page.getByRole('button',{name:'下载账单'})).toBeVisible()
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'下载收据'}).click()
  expect((await downloaded).suggestedFilename()).toBe('receipt-BILLING-77.pdf')
})
test('download link requires sign in and preserves its destination',async({page})=>{
  await page.route('**/api/**',route=>route.fulfill({json:{}}))
  await page.goto('/billing/service/77');await expect(page).toHaveURL(/auth=login.*redirect=/)
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/billing/service/77')
})
