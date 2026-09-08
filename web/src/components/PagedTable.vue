<template>
 <div class="paged-table">
  <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"/>
  <el-table ref="table" v-bind="$attrs" :data="visibleRows"><template v-for="(_,name) in $slots" #[name]="slotProps"><slot :name="name" v-bind="slotProps || {}"/></template></el-table>
  <ListPagination v-if="pagination !== 'external'" v-model:page="paging.page" v-model:size="paging.size" v-model:all="paging.all" :total="remoteTotal ?? data.length" @change="loadRemote"/>
 </div>
</template>
<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import http,{getHttpErrorMessage} from '../utils/http'
import {listOrigin} from '../utils/listOrigin'
import {ElTable} from 'element-plus'
import ListPagination from './ListPagination.vue'
import {useListPage,pageSlice,boundedPage} from '../utils/listPage'
defineOptions({inheritAttrs:false})
const props=withDefaults(defineProps<{data?:any[];listId:string;pagination?:'local'|'external'}>(),{data:()=>[],pagination:'local'})
const table=ref<InstanceType<typeof ElTable>>()
const paging=useListPage(props.listId)
const remoteRows=ref<any[]|null>(null),remoteTotal=ref<number|null>(null),loadError=ref('')
let generation=0
async function loadRemote(){
 const origin=listOrigin(props.data);if(props.pagination==='external'||!origin)return
 const version=++generation;loadError.value=''
 try{const{data}=await http.get(origin.url,{params:{...origin.params,listPage:true,listPath:origin.path,listCurrent:paging.page,listSize:paging.size,listAll:paging.all}})
  if(version!==generation)return
  if(!Array.isArray(data.items))return
  const identity=(row:any)=>row&&typeof row==='object'?(row.id!=null?'id:'+row.id:JSON.stringify(row)):JSON.stringify(row);const original=new Map(props.data.map(row=>[identity(row),row]));remoteRows.value=data.items.map((row:any)=>original.get(identity(row))??row);remoteTotal.value=data.total;paging.page=data.page
 }catch(e){if(version===generation){remoteRows.value=[];loadError.value=getHttpErrorMessage(e,'读取列表失败');if(paging.all){paging.all=false;await loadRemote()}}}
}
const visibleRows=computed(()=>props.pagination==='external'?props.data:remoteRows.value??pageSlice(props.data,paging.page,paging.size,paging.all))
watch(()=>props.data,()=>{paging.page=boundedPage(props.data.length,paging.page,paging.size);if(props.data.length>200)paging.all=false;remoteRows.value=null;remoteTotal.value=null;loadRemote()},{immediate:true})
const clearSelection=()=>table.value?.clearSelection()
const toggleRowSelection=(...args:Parameters<NonNullable<typeof table.value>['toggleRowSelection']>)=>table.value?.toggleRowSelection(...args)
const doLayout=()=>table.value?.doLayout()
defineExpose({clearSelection,toggleRowSelection,doLayout,table})
</script>
