import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { rememberConsent, rememberedConsent } from '../src/utils/checkoutConsent'
const order={id:41,amountCents:125000,paymentAmountCents:125001,paymentCurrency:'CNY',currency:'CNY',paymentMethod:'wxpay'}
beforeEach(()=>{const data=new Map<string,string>();vi.stubGlobal('sessionStorage',{getItem:(key:string)=>data.get(key),setItem:(key:string,value:string)=>data.set(key,value)})})
afterEach(()=>vi.unstubAllGlobals())
it('remembers only the same account, order and payment snapshot',()=>{
 rememberConsent('alice',order,'v1');expect(rememberedConsent('alice',order)).toBe('v1')
 expect(rememberedConsent('bob',order)).toBeUndefined();expect(rememberedConsent('alice',{...order,id:42})).toBeUndefined()
 expect(rememberedConsent('alice',{...order,paymentAmountCents:125002})).toBeUndefined()
 expect(rememberedConsent('alice',{...order,paymentMethod:'alipay'})).toBeUndefined()
 rememberConsent('alice',order);expect(rememberedConsent('alice',order)).toBeUndefined()
})
it('fails safely when storage is unavailable',()=>{vi.stubGlobal('sessionStorage',{getItem:()=>{throw Error()},setItem:()=>{throw Error()}});expect(()=>rememberConsent('alice',order,'v1')).not.toThrow();expect(rememberedConsent('alice',order)).toBeUndefined()})
