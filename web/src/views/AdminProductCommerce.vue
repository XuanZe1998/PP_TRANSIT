<template>
  <section class="commerce-admin">
    <div class="panel-title"><h3>商品与履约配置</h3><el-select v-model="selectedId" placeholder="选择成品服务" style="width:320px" @change="selectService"><el-option v-for="item in services" :key="item.id" :label="item.name" :value="item.id" /></el-select></div>
    <el-form v-if="form" label-position="top" class="panel">
      <el-form-item label="发货形式"><el-radio-group v-model="form.fulfillmentMode" :disabled="form.productType === 'CARD_KEY'"><el-radio value="AUTOMATIC_DELIVERY">自动发货</el-radio><el-radio value="MANUAL_PROCESSING">人工处理</el-radio></el-radio-group><span v-if="form.productType === 'CARD_KEY'" class="security-hint">卡密服务强制使用自动发货。</span></el-form-item>
      <el-form-item label="单次限购"><el-input-number v-model="form.maxPurchaseQuantity" :min="1" :max="1000" /></el-form-item>
      <el-form-item v-if="form.fulfillmentMode === 'MANUAL_PROCESSING'" label="人工库存（留空为不限库存）"><el-input-number v-model="form.manualStock" :min="0" clearable /></el-form-item>
      <el-form-item label="购买提示"><el-input v-model="form.purchasePrompt" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
      <el-form-item label="阶梯单价"><el-input v-model="form.wholesaleTiersJson" type="textarea" :rows="3" placeholder='[{"minQuantity":5,"unitPriceCents":900}]' /></el-form-item>
      <el-form-item label="自定义下单字段"><el-input v-model="form.inputSchemaJson" type="textarea" :rows="3" placeholder='[{"key":"account","label":"账号","required":true,"maxLength":200}]' /></el-form-item>
      <el-button type="primary" :loading="saving" @click="saveConfig">保存商品配置</el-button>
    </el-form>
    <section v-if="form?.fulfillmentMode === 'AUTOMATIC_DELIVERY'" class="panel inventory-panel">
      <h3>自动发货库存</h3><el-alert :closable="false" type="success" :title="`可用 ${stats.AVAILABLE || 0} / 预留 ${stats.RESERVED || 0} / 已交付 ${stats.DELIVERED || 0}；库存明文不会在列表中返回`" />
      <el-input v-model="inventoryText" type="textarea" :rows="6" placeholder="粘贴多个卡密，可用逗号、中文逗号、顿号、换行或空格分隔" />
      <div class="inventory-import-actions">
        <span>已识别 {{ recognizedInventoryItems.length }} 条，重复内容将自动去重</span>
        <el-button type="success" :loading="inventoryImporting" :disabled="recognizedInventoryItems.length === 0" @click="importInventory">批量导入</el-button>
      </div>
      <el-table :data="inventory"><el-table-column prop="id" label="ID" width="90" /><el-table-column prop="status" label="状态" /><el-table-column prop="reservedOrderId" label="订单 ID" /><el-table-column prop="createdAt" label="导入时间" /><el-table-column label="操作"><template #default="{ row }"><el-button v-if="row.status === 'AVAILABLE'" link type="danger" @click="deleteInventory(row.id)">删除未售</el-button></template></el-table-column></el-table>
    </section>
    <section class="panel"><div class="panel-title"><h3>优惠码</h3><el-button type="primary" @click="openCoupon()">新增</el-button></div>
      <el-table :data="coupons"><el-table-column prop="code" label="代码" /><el-table-column label="固定优惠"><template #default="{ row }">{{ (row.discountCents / 100).toFixed(2) }}</template></el-table-column><el-table-column prop="remainingUses" label="剩余次数" /><el-table-column label="状态"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作"><template #default="{ row }"><el-button link @click="openCoupon(row)">编辑</el-button><el-button link type="danger" @click="disableCoupon(row.id)">停用</el-button></template></el-table-column></el-table>
    </section>
    <el-dialog v-model="couponVisible" title="优惠码" width="560px"><el-form label-position="top"><el-form-item label="代码"><el-input v-model="couponForm.code" /></el-form-item><el-form-item label="固定优惠金额"><el-input-number v-model="couponForm.discount" :min="0" :precision="2" /></el-form-item><el-form-item label="剩余次数"><el-input-number v-model="couponForm.remainingUses" :min="0" /></el-form-item><el-form-item label="适用服务"><el-select v-model="couponForm.serviceIds" multiple style="width:100%"><el-option v-for="item in services" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="启用"><el-switch v-model="couponForm.enabled" /></el-form-item></el-form><template #footer><el-button @click="couponVisible=false">取消</el-button><el-button type="primary" @click="saveCoupon">保存</el-button></template></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
type ProductService = Record<string, any> & { id:number; name:string }
type Coupon = { id?:number; code:string; discountCents:number; remainingUses:number; enabled:boolean; serviceIds:number[] }
const services=ref<ProductService[]>([]), form=ref<ProductService|null>(null), inventory=ref<any[]>([]), coupons=ref<Coupon[]>([])
const selectedId=ref<number>(), saving=ref(false), inventoryText=ref(''), stats=ref<Record<string,number>>({}), couponVisible=ref(false)
const inventoryImporting=ref(false)
const recognizedInventoryItems=computed(()=>Array.from(new Set(inventoryText.value.split(/[\s,，、]+/u).map(item=>item.trim()).filter(Boolean))))
const couponForm=reactive({id:undefined as number|undefined,code:'',discount:0,remainingUses:1,enabled:true,serviceIds:[] as number[]})
async function load(){const [s,c]=await Promise.all([http.get<ProductService[]>('/api/admin/api/other-services'),http.get<Coupon[]>('/api/service-orders/admin/coupons')]);services.value=s.data||[];coupons.value=c.data||[]}
async function selectService(){const source=services.value.find(x=>x.id===selectedId.value);form.value=source?{...source,fulfillmentMode:source.fulfillmentMode||'MANUAL_PROCESSING',maxPurchaseQuantity:source.maxPurchaseQuantity||1,wholesaleTiersJson:source.wholesaleTiersJson||'[]',inputSchemaJson:source.inputSchemaJson||'[]'}:null;if(form.value?.fulfillmentMode==='AUTOMATIC_DELIVERY')await loadInventory()}
async function saveConfig(){if(!form.value)return;saving.value=true;try{await http.put(`/api/admin/api/other-services/${form.value.id}`,form.value);ElMessage.success('商品配置已保存');await load();await selectService()}catch(e){ElMessage.error(getHttpErrorMessage(e,'保存失败'))}finally{saving.value=false}}
async function loadInventory(){if(!selectedId.value)return;const [i,s]=await Promise.all([http.get(`/api/admin/api/other-services/${selectedId.value}/inventory`),http.get(`/api/admin/api/other-services/${selectedId.value}/inventory/stats`)]);inventory.value=i.data||[];stats.value=s.data||{}}
async function importInventory(){if(!selectedId.value||recognizedInventoryItems.value.length===0)return;inventoryImporting.value=true;try{const recognized=recognizedInventoryItems.value.length;const r=await http.post(`/api/admin/api/other-services/${selectedId.value}/inventory/import`,{content:inventoryText.value});const imported=Number(r.data.imported||0);ElMessage.success(`识别 ${recognized} 条，成功导入 ${imported} 条${imported<recognized?'，已忽略库中重复卡密':''}`);inventoryText.value='';await loadInventory()}catch(e){ElMessage.error(getHttpErrorMessage(e,'卡密导入失败'))}finally{inventoryImporting.value=false}}
async function deleteInventory(id:number){await http.delete(`/api/admin/api/other-services/${selectedId.value}/inventory/${id}`);await loadInventory()}
function openCoupon(c?:Coupon){Object.assign(couponForm,c?{...c,discount:c.discountCents/100}:{id:undefined,code:'',discount:0,remainingUses:1,enabled:true,serviceIds:[]});couponVisible.value=true}
async function saveCoupon(){const p={code:couponForm.code,discountCents:Math.round(couponForm.discount*100),remainingUses:couponForm.remainingUses,enabled:couponForm.enabled,serviceIds:couponForm.serviceIds};if(couponForm.id)await http.put(`/api/service-orders/admin/coupons/${couponForm.id}`,p);else await http.post('/api/service-orders/admin/coupons',p);couponVisible.value=false;await load()}
async function disableCoupon(id:number){await http.delete(`/api/service-orders/admin/coupons/${id}`);await load()}
onMounted(()=>load().catch(e=>ElMessage.error(getHttpErrorMessage(e,'商品能力加载失败'))))
</script>
<style scoped>.commerce-admin{display:grid;gap:18px;margin-top:24px}.panel{padding:18px;border:1px solid #e2e8f0;border-radius:10px}.inventory-panel{display:grid;gap:12px}.panel-title,.inventory-import-actions{display:flex;align-items:center;justify-content:space-between;gap:16px}.inventory-import-actions span,.security-hint{color:#64748b;font-size:12px}.security-hint{display:block;margin-top:6px}</style>
