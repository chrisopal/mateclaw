<template>
  <el-dialog v-model="open" :title="l('接收售前版本','Receive presales version')" width="min(760px,94vw)" :close-on-click-modal="false" destroy-on-close>
    <el-form label-position="top">
      <el-form-item :label="l('售前项目','Presales project')" required>
        <el-select v-model="presalesProjectId" filterable clearable :loading="projectsLoading" :disabled="busy" :placeholder="l('选择有权访问的售前项目','Choose an accessible presales project')" @change="loadOptions">
          <el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="l('发布版本','Published version')" required>
        <el-select v-model="releaseId" filterable :loading="optionsLoading" :disabled="!presalesProjectId || busy" :placeholder="l('明确选择一个已发布版本','Choose an exact published version')" @change="loadPreview">
          <el-option v-for="item in options" :key="item.releaseId" :label="releaseLabel(item)" :value="item.releaseId" :disabled="item.available===false || !item.digest" />
        </el-select>
        <p v-if="selectedOption?.available===false" class="field-help">{{ selectedOption.unavailableReason || l('该历史版本没有可验证的冻结快照。','This historical version has no verifiable frozen snapshot.') }}</p>
        <p v-if="presalesProjectId && !optionsLoading && !options.length" class="field-help">{{ l('没有可接收的已发布版本。','No published versions are available.') }}</p>
        <ul v-if="options.some(item=>item.available===false || !item.digest)" class="unavailable-releases"><li v-for="item in options.filter(row=>row.available===false || !row.digest)" :key="item.releaseId">{{ releaseLabel(item) }} · {{ item.unavailableReason || l('没有可验证的冻结快照','No verifiable frozen snapshot') }}</li></ul>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section v-if="preview" class="handoff-preview" aria-label="Version preview">
      <header><div><h3>{{ preview.solution?.title || l('方案摘要','Solution summary') }}</h3><span>{{ l('发布于','Published') }} {{ releaseLabel(selectedOption) }}</span></div><el-tag effect="plain">{{ l('客户确认状态：未确认','Customer confirmation: unconfirmed') }}</el-tag></header>
      <section><h4>{{ l('基线','Baseline') }}</h4><p>{{ baselineSummary }}</p></section>
      <section><h4>{{ l('方案','Solution') }}</h4><ul><li v-for="(item,index) in preview.solution?.sections || []" :key="index"><strong>{{ item.title }}</strong><span>{{ item.text }}</span></li></ul></section>
      <section><h4>{{ l('风险与未知项','Risks and unknowns') }}</h4><ul v-if="preview.risksAndUnknowns?.length"><li v-for="(item,index) in preview.risksAndUnknowns" :key="index">{{ summaryText(item) }}</li></ul><p v-else>—</p></section>
      <section><h4>{{ l('澄清摘要','Clarification summary') }}</h4><p v-if="preview.historicalClarificationsAvailable === false">{{ l('该历史版本没有可验证的澄清快照。','Clarifications are unavailable for this historical version.') }}</p><p v-else>{{ clarificationSummary }}</p></section>
      <details><summary>{{ l('接收摘要','Receipt summary') }}</summary><p>{{ l('接收后固定此发布内容；源项目后续更新不会替换已接收版本。','Receipt fixes this release. Later source updates will not replace it.') }}</p><p>{{ l('校验摘要','Verified digest') }} · {{ previewDigestLabel }}</p></details>
      <el-form-item :label="l('接收时补充事项','Notes at receipt')"><el-input v-model="notes" type="textarea" :rows="2" maxlength="2000" :disabled="busy" /></el-form-item>
    </section>
    <template #footer><el-button @click="open=false">{{ l('取消','Cancel') }}</el-button><el-button type="primary" :loading="busy" :disabled="!canReceive" @click="receive">{{ l('接收此版本','Receive this version') }}</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { presalesApi } from '@/features/presales/api/presalesApi'
import { biddingApi } from '../api/biddingApi'
import type { HandoffOption, HandoffSnapshot, Project } from '../api/types'
const props=defineProps<{modelValue:boolean;workspaceId:string;project:Project;busy?:boolean}>()
const emit=defineEmits<{ 'update:modelValue':[boolean]; receive:[payload:{presalesProjectId:string;releaseId:string;expectedDigest:string;receivedNotes:string[]}] }>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const open=computed({get:()=>props.modelValue,set:(value:boolean)=>emit('update:modelValue',value)})
const projects=ref<{id:string;name:string}[]>([]),options=ref<HandoffOption[]>([]),preview=ref<HandoffSnapshot>(),presalesProjectId=ref(''),releaseId=ref(''),notes=ref(''),error=ref(''),projectsLoading=ref(false),optionsLoading=ref(false),previewLoading=ref(false)
const selectedOption=computed(()=>options.value.find(item=>item.releaseId===releaseId.value))
const canReceive=computed(()=>!!presalesProjectId.value&&!!selectedOption.value?.digest&&!!preview.value&&!props.busy&&!previewLoading.value)
const previewDigestLabel=computed(()=>selectedOption.value?.digest ? `${selectedOption.value.digest.slice(0,12)}…` : '—')
const baselineSummary=computed(()=>summaryText(preview.value?.baseline || {}))
const clarificationSummary=computed(()=>{const rows=preview.value?.clarifications||[];return rows.length?rows.map(summaryText).join('；'):l('暂无澄清摘要','No clarification summary')})
function releaseLabel(item?:HandoffOption){if(!item)return '';const parts=[item.title,item.solutionVersion?`V${item.solutionVersion}`:'',item.publishedAt||''].filter(Boolean);return parts.join(' · ')||l('已发布版本','Published version')}
function summaryText(value:Record<string,unknown>){return [value.title,value.text,value.summary,value.description,value.status].filter(item=>typeof item==='string'&&item.trim()).join(' · ')||'—'}
let projectEpoch=0,optionsEpoch=0,previewEpoch=0,projectController:AbortController|undefined,optionsController:AbortController|undefined,previewController:AbortController|undefined
function abortReads(){projectController?.abort();optionsController?.abort();previewController?.abort();projectEpoch++;optionsEpoch++;previewEpoch++}
function sameScope(ws:string,projectId:string){return open.value&&ws===props.workspaceId&&projectId===props.project.id}
async function loadProjects(){if(!open.value||!props.workspaceId)return;projectController?.abort();projectController=new AbortController();const epoch=++projectEpoch,ws=props.workspaceId,projectId=props.project.id,signal=projectController.signal;projectsLoading.value=true;error.value='';try{const page=await presalesApi.list(ws,{page:1,pageSize:100},signal);if(signal.aborted||epoch!==projectEpoch||!sameScope(ws,projectId))return;projects.value=(page.items||[]).map(item=>({id:item.id,name:item.name}));if(projects.value.length===1&&!presalesProjectId.value){presalesProjectId.value=projects.value[0]!.id;await loadOptions()}}catch{if(!signal.aborted&&epoch===projectEpoch&&sameScope(ws,projectId)){projects.value=[];error.value=l('售前项目读取失败。','Could not load presales projects.')}}finally{if(epoch===projectEpoch)projectsLoading.value=false}}
async function loadOptions(){releaseId.value='';preview.value=undefined;options.value=[];error.value='';optionsController?.abort();previewController?.abort();const selectedProject=presalesProjectId.value;if(!selectedProject)return;optionsController=new AbortController();const epoch=++optionsEpoch,ws=props.workspaceId,projectId=props.project.id,signal=optionsController.signal;optionsLoading.value=true;try{const rows=await biddingApi.handoffOptions(ws,selectedProject,signal);if(signal.aborted||epoch!==optionsEpoch||!sameScope(ws,projectId)||selectedProject!==presalesProjectId.value)return;options.value=rows}catch{if(!signal.aborted&&epoch===optionsEpoch&&sameScope(ws,projectId)&&selectedProject===presalesProjectId.value)error.value=l('发布版本读取失败。','Could not load published versions.')}finally{if(epoch===optionsEpoch)optionsLoading.value=false}}
async function loadPreview(){preview.value=undefined;error.value='';previewController?.abort();const selected=selectedOption.value,selectedProject=presalesProjectId.value,selectedRelease=releaseId.value;if(!selected||selected.available===false||!selected.digest||!selectedProject||!selectedRelease)return;previewController=new AbortController();const epoch=++previewEpoch,ws=props.workspaceId,projectId=props.project.id,signal=previewController.signal;previewLoading.value=true;try{const snapshot=await biddingApi.handoffSnapshot(ws,selectedProject,selectedRelease,signal);if(signal.aborted||epoch!==previewEpoch||!sameScope(ws,projectId)||selectedProject!==presalesProjectId.value||selectedRelease!==releaseId.value)return;preview.value=snapshot}catch(cause){if(signal.aborted||epoch!==previewEpoch||!sameScope(ws,projectId)||selectedProject!==presalesProjectId.value||selectedRelease!==releaseId.value)return;const status=(cause as {response?:{status?:number}})?.response?.status;error.value=status===404||status===403?l('当前版本不可访问，已清除预览内容。','This version is unavailable. Preview data was cleared.'):l('版本预览失败，请重试。','Could not preview this version. Retry.')}finally{if(epoch===previewEpoch)previewLoading.value=false}}
function receive(){const option=selectedOption.value;if(!option||!canReceive.value)return;emit('receive',{presalesProjectId:presalesProjectId.value,releaseId:option.releaseId,expectedDigest:option.digest,receivedNotes:notes.value.split(/\r?\n/).map(value=>value.trim()).filter(Boolean)})}
watch(()=>[props.workspaceId,props.project.id,props.modelValue] as const,([ws,id,visible])=>{abortReads();if(!visible)return;projects.value=[];options.value=[];preview.value=undefined;presalesProjectId.value='';releaseId.value='';if(ws&&id)void loadProjects()},{flush:'sync',immediate:true})
</script>
<style scoped>
.handoff-preview{display:grid;gap:12px;margin-top:16px;border-top:1px solid var(--mc-border-light);padding-top:16px}.handoff-preview>header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.handoff-preview h3{margin:0;font-size:16px}.handoff-preview header span,.handoff-preview p,.field-help,.unavailable-releases{color:var(--mc-text-secondary);font-size:13px}.handoff-preview header span{display:block;margin-top:4px}.handoff-preview h4{margin:0 0 6px;font-size:13px}.handoff-preview p{margin:0;line-height:1.6}.handoff-preview ul{margin:0;padding-left:20px;max-height:150px;overflow:auto}.handoff-preview li{margin:4px 0;line-height:1.5}.handoff-preview li span{display:block;color:var(--mc-text-secondary)}.handoff-preview details{font-size:13px;color:var(--mc-text-secondary)}.handoff-preview summary{cursor:pointer;color:var(--mc-action-text,var(--mc-primary))}.field-help{margin:6px 0 0}.unavailable-releases{margin:6px 0 0;padding-left:20px}.el-button:focus-visible{outline:2px solid var(--mc-primary);outline-offset:2px}
</style>
