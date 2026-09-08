<template><ListPagination :total="total" :page="currentPage" :size="pageSize===200?20:pageSize" :all="pageSize===200" @update:page="setPage" @update:size="setSize" @update:all="setAll" @change="emit('change',nextPage,nextSize)"/></template>
<script setup lang="ts">
import {onMounted} from 'vue'
import ListPagination from './ListPagination.vue'
const props=withDefaults(defineProps<{total?:number;currentPage?:number;pageSize?:number;listId:string}>(),{total:0,currentPage:1,pageSize:20})
const emit=defineEmits<{'update:currentPage':[number];'update:pageSize':[number];currentChange:[number];sizeChange:[number];change:[number,number]}>()
let nextPage=props.currentPage,nextSize=props.pageSize
function setPage(value:number){nextPage=value;emit('update:currentPage',value);emit('currentChange',value)}
function setSize(value:number){nextSize=value;emit('update:pageSize',value);emit('sizeChange',value);try{localStorage.setItem('page-size:'+props.listId,String(value))}catch{/* storage unavailable */}}
function setAll(value:boolean){if(value)setSize(200);else if(props.pageSize===200)setSize(20)}
onMounted(()=>{try{const value=Number(localStorage.getItem('page-size:'+props.listId));if([10,20,50,100].includes(value)&&value!==props.pageSize)setSize(value)}catch{/* defaults */}})
</script>
