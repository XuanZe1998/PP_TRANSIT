import { describe,it,expect } from 'vitest'
import { readFileSync,readdirSync } from 'node:fs'
import { resolve,join } from 'node:path'
import { pageSlice,boundedPage } from '../src/utils/listPage'
import { rememberLists,listOrigin } from '../src/utils/listOrigin'
describe('business list pagination',()=>{
 it('pages without dropping boundary rows and caps all mode',()=>{const rows=Array.from({length:205},(_,i)=>i);expect(pageSlice(rows,2,20)).toEqual(rows.slice(20,40));expect(pageSlice(rows,1,20,true)).toHaveLength(20);expect(pageSlice(rows.slice(0,200),1,20,true)).toHaveLength(200);expect(boundedPage(0,99,20)).toBe(1);expect(boundedPage(21,3,20)).toBe(2)})
 it('preserves the original source only for a complete unmodified dataset',()=>{const rows=[{id:1},{id:2}];rememberLists({wallet:{plans:rows}},{url:'/wallet',params:{owner:'current'}});expect(listOrigin(rows)?.path).toBe('wallet.plans');expect(listOrigin(rows.filter(x=>x.id===1))).toBeUndefined();expect(listOrigin(rows.map(x=>({...x})))).toBeUndefined()})
 it('requires the shared components for every new data table and pager',()=>{const root=resolve('src');function walk(dir:string):string[]{return readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?walk(join(dir,entry.name)):entry.name.endsWith('.vue')?[join(dir,entry.name)]:[])}for(const file of walk(root)){const content=readFileSync(file,'utf8');if(!file.endsWith('PagedTable.vue'))expect(content,`${file}: use PagedTable with a stable list-id`).not.toMatch(/<el-table(?=[\s>])/);if(!file.endsWith('ListPagination.vue'))expect(content,`${file}: use shared pagination`).not.toMatch(/<el-pagination(?=[\s>])/);}})
})
