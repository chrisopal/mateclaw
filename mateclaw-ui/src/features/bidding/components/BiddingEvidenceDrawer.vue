<template>
  <el-drawer v-model="open" :title="l('原文证据','Source evidence')" size="min(560px, 94vw)" @closed="clear">
    <el-skeleton v-if="loading" :rows="5" animated />
    <el-alert v-else-if="error" :title="error" type="warning" :closable="false" />
    <article v-else-if="evidence"><div class="locator">{{ evidence.locator }}<span v-if="evidence.pdfPage"> · {{ l('第','p.') }}{{ evidence.pdfPage }}</span></div><blockquote>{{ evidence.text }}</blockquote><small>V{{ sourceVersion }}</small></article>
  </el-drawer>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { biddingApi } from '../api/biddingApi'
import type { Evidence } from '../api/types'
const props=defineProps<{ modelValue:boolean; workspaceId:string; projectId:string; sourceRef?:Record<string,unknown> }>()
const emit=defineEmits<{ 'update:modelValue':[boolean] }>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const open=ref(false),loading=ref(false),error=ref(''),evidence=ref<Evidence>(),sourceVersion=ref(0),controller=ref<AbortController>()
watch(()=>[props.modelValue,props.sourceRef,props.workspaceId,props.projectId] as const,async ([visible,refValue,ws,id])=>{
  open.value=visible; controller.value?.abort(); evidence.value=undefined; error.value=''; if(!visible||!ws||!id||!refValue) return
  const sourceId=String(refValue.sourceId||''),version=Number(refValue.version),blockId=String(refValue.blockId||'')
  if(!sourceId||!version||!blockId){error.value=l('证据引用不完整。','Evidence reference is incomplete.');return}
  sourceVersion.value=version
  const current=new AbortController(); controller.value=current; loading.value=true
  try { evidence.value=await biddingApi.evidence(ws,id,{sourceId,version,blockId},current.signal) }
  catch { if(!current.signal.aborted) error.value=l('此来源已不可访问或已撤权。','This source is unavailable or access was withdrawn.') }
  finally { if(!current.signal.aborted) loading.value=false }
})
watch(open,value=>emit('update:modelValue',value))
function clear(){controller.value?.abort();evidence.value=undefined}
</script>
<style scoped>.locator,small{color:var(--mc-text-secondary);font-size:12px}blockquote{margin:12px 0;padding:14px 16px;border-left:3px solid var(--mc-primary);background:var(--mc-bg-muted);line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}</style>
