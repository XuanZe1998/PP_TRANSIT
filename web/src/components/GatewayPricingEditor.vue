<template>
 <el-dialog v-model="open" title="销售加价规则" width="min(900px,95vw)">
  <el-form label-position="top" v-if="!preview">
   <p v-if="effectiveSource">当前生效：{{effectiveSource}}</p><el-form-item label="规则范围"><el-select v-model="scope" @change="loadRule"><el-option label="全局默认" value="GLOBAL"/><el-option v-if="siteId" label="当前上游" value="SITE"/><el-option v-if="groupId" label="当前分组" value="GROUP"/><el-option v-if="modelId" label="当前模型" value="MODEL"/></el-select></el-form-item>
   <p>加价10%表示采购价 × 1.10，不在旧售价上累加。优先级：模型 → 分组 → 上游 → 全局。手工固定售价默认保留；修改当前模型规则或恢复继承将解除该模型的手工覆盖。</p>
   <el-form-item label="加价方式"><el-radio-group v-model="mode"><el-radio value="PERCENT">百分比加价</el-radio><el-radio value="FIXED">固定金额</el-radio><el-radio v-if="scope !== 'GLOBAL'" value="INHERIT">恢复继承</el-radio></el-radio-group></el-form-item>
   <el-form-item v-if="mode === 'PERCENT'" label="采购价加价百分比"><el-input-number v-model="amount" :min="0" :max="1000000" :precision="4"/> %</el-form-item>
   <template v-if="mode === 'FIXED'"><el-form-item v-for="unit in units" :key="unit.code" :label="unit.label"><el-input-number v-model="fixed[unit.code]" :min="0" :precision="8" placeholder="未配置"/></el-form-item></template>
  </el-form>
  <template v-else><p>本次实际变价 {{preview.changes.filter((row:any)=>row.changed).length}} 个模型。采购价、手工覆盖或规则发生变化后，本次预览将失效。有效期30分钟。</p>
   <PagedTable :data="preview.changes" list-id="GatewayPricingEditor-1"><el-table-column type="expand"><template #default="{row}"><PagedTable :data="row.tiers" :list-id="'pricing-tiers-'+row.id"><el-table-column prop="name" label="阶梯"/><el-table-column prop="maxContextTokens" label="上下文上限"/><el-table-column label="销售维度旧价 → 新价"><template #default="{row:tier}"><div v-for="(value,key) in tier.before" :key="key">{{key}}：{{value??'未知'}} → {{tier.after[key]??'未知'}}</div></template></el-table-column></PagedTable></template></el-table-column><el-table-column prop="model" label="模型" min-width="160"/><el-table-column prop="unit" label="单位" width="90"/><el-table-column label="旧价 → 新价" min-width="230"><template #default="{row}"><div v-for="key in priceKeys" :key="key">{{ labels[key] }}（采购 {{row.cost?.[key]??'未知'}}）：{{ row.before[key] ?? '未知' }} → {{ row.after[key] ?? '未知' }}（差额 {{row.delta[key]??'未知'}}）</div></template></el-table-column><el-table-column prop="reason" label="处理结果" min-width="160"/></PagedTable>
  </template>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template #footer><el-button v-if="preview" @click="preview=null">修改规则</el-button><el-button v-if="!preview" type="primary" :loading="busy" @click="calculate">预览影响</el-button><el-button v-else type="primary" :loading="busy" @click="apply">应用本次预览</el-button></template>
 </el-dialog>
</template>
<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue'
import {ref,reactive} from 'vue'
import http,{getHttpErrorMessage} from '../utils/http'
import {ElMessage} from 'element-plus'
const props=defineProps<{siteId?:number;groupId?:number}>()
const emit=defineEmits<{changed:[]}>()
const open=ref(false),busy=ref(false),error=ref(''),scope=ref('GLOBAL'),mode=ref('PERCENT'),amount=ref(20),modelId=ref<number>(),preview=ref<any>(null)
const effectiveSource=ref('')
const fixed=reactive<Record<string,number|undefined>>({})
const units=[{code:'TOKEN',label:'USD / 百万 Token（各输入、输出、缓存维度）'},{code:'TASK',label:'USD / 次'},{code:'IMAGE',label:'USD / 张'},{code:'SECOND',label:'USD / 秒'},{code:'MINUTE',label:'USD / 分钟'},{code:'CHARACTER',label:'USD / 字符'}]
const labels:Record<string,string>={input_price_per_million:'输入',output_price_per_million:'输出',cached_price_per_million:'缓存',sale_unit_price:'按单位'}
const priceKeys=Object.keys(labels)
function scopeId(){return scope.value==='MODEL'?modelId.value:scope.value==='GROUP'?props.groupId:scope.value==='SITE'?props.siteId:0}
async function loadRule(){try{const {data}=await http.get('/api/admin/api/gateway/pricing/rules',{params:{scope:scope.value,scopeId:scopeId()}});mode.value=data.mode;amount.value=Number(data.amount);for(const key of Object.keys(fixed))delete fixed[key];Object.assign(fixed,data.fixedAmounts||{})}catch(e){error.value=getHttpErrorMessage(e,'读取规则失败')}}
async function show(id?:number){modelId.value=id;scope.value=id?'MODEL':props.groupId?'GROUP':props.siteId?'SITE':'GLOBAL';preview.value=null;error.value='';effectiveSource.value='';open.value=true;await loadRule();if(id){const{data}=await http.get(`/api/admin/api/gateway/pricing/${id}`);effectiveSource.value=data.manual?'手工固定售价（修改当前模型规则后解除保护）':`${data.rule.scope} · ${data.rule.mode==='PERCENT'?`加价 ${data.rule.amount}%`:'固定金额加价'}`}}

async function calculate(){busy.value=true;error.value='';try{preview.value=(await http.post('/api/admin/api/gateway/pricing/preview',{scope:scope.value,scopeId:scope.value==='MODEL'?modelId.value:scope.value==='GROUP'?props.groupId:scope.value==='SITE'?props.siteId:0,mode:mode.value,amount:amount.value,fixedAmounts:fixed})).data}catch(e){error.value=getHttpErrorMessage(e,'预览失败')}finally{busy.value=false}}
async function apply(){busy.value=true;error.value='';try{await http.post('/api/admin/api/gateway/pricing/apply',{previewId:preview.value.id});ElMessage.success('销售规则已应用');open.value=false;emit('changed')}catch(e){error.value=getHttpErrorMessage(e,'应用失败')}finally{busy.value=false}}
defineExpose({show})
</script>
