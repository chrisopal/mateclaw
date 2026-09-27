<template>
  <el-dialog :model-value="modelValue" :title="l('审定候选文件','Approve candidate file')" width="min(600px,94vw)" :close-on-click-modal="false" @close="$emit('update:modelValue',false)">
    <template v-if="context"><div class="file-summary"><strong>{{ context.filename || l('技术标文件.docx','Technical proposal.docx') }}</strong><span>{{ l('正文版本','Manuscript') }} V{{ context.manuscriptRef.version }} · {{ l('模板版本','Template') }} V{{ context.templateRef.version }}</span></div><el-alert v-if="context.status!=='READY'" :title="gateLabel(context)" type="warning" :closable="false"/><el-alert v-if="conflict" :title="l('版本已变化；草稿已保留。请刷新门禁并重新检查文件。','The version changed. Your draft is retained; refresh the gate and inspect the file again.')" type="warning" :closable="false"/><el-checkbox v-model="opened" :disabled="busy || context.status!=='READY'">{{ l('我已打开并检查该文件','I opened and inspected this file') }}</el-checkbox><el-checkbox v-model="layoutChecked" :disabled="busy || context.status!=='READY'">{{ l('我已检查页面布局','I checked the page layout') }}</el-checkbox><el-form label-position="top"><el-form-item :label="l('检查说明','Inspection note')"><el-input v-model="reason" type="textarea" :rows="3" maxlength="2000" :disabled="busy || context.status!=='READY'" :placeholder="l('记录人工检查结论','Record the inspection outcome')"/></el-form-item></el-form><details class="technical-details"><summary>{{ l('版本核验详情','Version verification details') }}</summary><dl><dt>{{ l('文件摘要','File digest') }}</dt><dd>{{ context.digest }}</dd><dt>{{ l('审核版本','Review version') }}</dt><dd>V{{ context.reviewRef?.version ?? '—' }}</dd><dt>{{ l('格式版本','Format version') }}</dt><dd>V{{ context.formatRef.version }}</dd></dl></details></template>
    <template #footer><el-button :disabled="busy" @click="$emit('update:modelValue',false)">{{ l('取消','Cancel') }}</el-button><el-button type="primary" :loading="busy" :disabled="!ready" @click="submit">{{ l('确认审定此文件','Approve this file') }}</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ArtifactApprovalContext } from '../api/types'
const props=defineProps<{modelValue:boolean;context?:ArtifactApprovalContext;busy:boolean;conflict:boolean}>()
const emit=defineEmits<{ 'update:modelValue':[boolean]; approve:[{artifactId:string;digest:string;manuscriptRef:ArtifactApprovalContext['manuscriptRef'];templateRef:ArtifactApprovalContext['templateRef'];formatRef:ArtifactApprovalContext['formatRef'];reviewRef:NonNullable<ArtifactApprovalContext['reviewRef']>;inspection:{opened:true;layoutChecked:true;reason:string}}] }>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const opened=ref(false),layoutChecked=ref(false),reason=ref('')
watch(()=>[props.modelValue,props.context?.artifactRef?.digest,props.context?.reviewRef?.digest] as const,([visible])=>{if(visible){opened.value=false;layoutChecked.value=false}else{opened.value=false;layoutChecked.value=false;reason.value=''}})
const ready=computed(()=>!!props.context&&props.context.status==='READY'&&!!props.context.reviewRef&&opened.value&&layoutChecked.value&&!!reason.value.trim()&&!props.busy)
function gateLabel(context:ArtifactApprovalContext){return context.status==='BLOCKED'?l('审核或待办尚未满足审定条件','Review or business-item requirements are not satisfied'):context.status==='STALE'?l('文件依赖版本已变化，请重新生成候选。','File dependencies changed. Generate a new candidate.'):l('预览文件不能审定','Preview files cannot be approved')}
function submit(){const context=props.context;if(!ready.value||!context?.reviewRef)return;emit('approve',{artifactId:context.artifactId,digest:context.digest||'',manuscriptRef:context.manuscriptRef,templateRef:context.templateRef,formatRef:context.formatRef,reviewRef:context.reviewRef,inspection:{opened:true,layoutChecked:true,reason:reason.value.trim()}})}
</script>
<style scoped>
.file-summary{display:grid;gap:6px;margin-bottom:16px}.file-summary span{color:var(--mc-text-secondary);font-size:13px}.technical-details{margin-top:12px;padding-top:12px;border-top:1px solid var(--mc-border-light)}.technical-details summary{cursor:pointer;color:var(--mc-primary)}dl{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:8px 14px;font-size:12px}dt{color:var(--mc-text-secondary)}dd{margin:0;overflow-wrap:anywhere}
</style>
