<template>
  <div class="billing-checkout-fields">
    <el-form-item label="账单姓名"><el-input :model-value="modelValue.billingName" @update:model-value="set('billingName',$event)" /></el-form-item>
    <el-form-item label="收件邮箱"><el-input :model-value="modelValue.contactEmail" @update:model-value="set('contactEmail',$event)" /></el-form-item>
    <el-form-item label="详细地址"><el-input :model-value="modelValue.billingAddressLine1" placeholder="街道、门牌、小区/楼栋" @update:model-value="set('billingAddressLine1',$event)" /></el-form-item>
    <el-row :gutter="12"><el-col :span="12"><el-form-item label="区/县"><el-input :model-value="modelValue.billingDistrict" @update:model-value="set('billingDistrict',$event)" /></el-form-item></el-col><el-col :span="12"><el-form-item label="城市"><el-input :model-value="modelValue.billingCity" @update:model-value="set('billingCity',$event)" /></el-form-item></el-col></el-row>
    <el-row :gutter="12"><el-col :span="12"><el-form-item label="省份"><el-input :model-value="modelValue.billingProvince" @update:model-value="set('billingProvince',$event)" /></el-form-item></el-col><el-col :span="12"><el-form-item label="邮编"><el-input :model-value="modelValue.billingPostalCode" @update:model-value="set('billingPostalCode',$event)" /></el-form-item></el-col></el-row>
    <el-form-item label="国家/地区"><el-input :model-value="modelValue.billingCountry" @update:model-value="set('billingCountry',$event)" /></el-form-item>
    <el-form-item label="支付方式"><el-radio-group :model-value="modelValue.paymentMethod" @update:model-value="set('paymentMethod',String($event))"><el-radio value="alipay">支付宝</el-radio><el-radio value="wxpay">微信支付</el-radio></el-radio-group></el-form-item>
  </div>
</template>

<script setup lang="ts">
export type BillingCheckout = { billingName:string; contactEmail:string; billingAddressLine1:string; billingDistrict:string; billingCity:string; billingProvince:string; billingPostalCode:string; billingCountry:string; paymentMethod:string }
const props=defineProps<{modelValue:BillingCheckout}>()
const emit=defineEmits<{(event:'update:modelValue',value:BillingCheckout):void}>()
function set(key:keyof BillingCheckout,value:string){emit('update:modelValue',{...props.modelValue,[key]:value})}
</script>
