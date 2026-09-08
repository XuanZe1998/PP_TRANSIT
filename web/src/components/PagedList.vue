<template><div class="paged-list"><slot :items="visible"/><ListPagination v-model:page="paging.page" v-model:size="paging.size" v-model:all="paging.all" :total="remoteTotal ?? data.length" @change="loadRemote"/></div></template>
<script setup lang="ts">
import {computed,watch,ref} from 'vue'
import http from '../utils/http'
import {listOrigin} from '../utils/listOrigin'
import ListPagination from './ListPagination.vue'
import {useListPage,pageSlice,boundedPage} from '../utils/listPage'
const props=defineProps<{data:any[];listId:string}>()
const paging=useListPage(props.listId)
const remoteRows=ref<any[]|null>(null),remoteTotal=ref<number|null>(null)
let generation=0
async function loadRemote(){const origin=listOrigin(props.data);if(!origin)return;const version=++generation;try{const {data}=await http.get(origin.url,{params:{...origin.params,listPage:true,listPath:origin.path,listCurrent:paging.page,listSize:paging.size,listAll:paging.all}});if(version!==generation)return;if(Array.isArray(data.items)){const identity=(row:any)=>row&&typeof row==='object'?(row.id!=null?'id:'+row.id:JSON.stringify(row)):JSON.stringify(row);const original=new Map(props.data.map(row=>[identity(row),row]));remoteRows.value=data.items.map((row:any)=>original.get(identity(row))??row);remoteTotal.value=data.total;paging.page=data.page}}catch{if(paging.all){paging.all=false;await loadRemote()}}}
const visible=computed(()=>remoteRows.value??pageSlice(props.data,paging.page,paging.size,paging.all))
watch(()=>props.data,()=>{paging.page=boundedPage(props.data.length,paging.page,paging.size);if(props.data.length>200)paging.all=false;remoteRows.value=null;remoteTotal.value=null;loadRemote()},{immediate:true})
</script>
<style scoped>.paged-list{display:contents}.paged-list :deep(.list-pagination){grid-column:1 / -1;width:100%}</style>
