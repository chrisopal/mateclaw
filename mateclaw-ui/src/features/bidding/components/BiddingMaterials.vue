<template>
  <section class="materials-panel">
    <header><div><h2>{{ l('授权材料','Authorized materials') }}</h2><span>{{ l('仅使用明确选定且当前有效的知识页','Only explicitly selected, currently valid knowledge pages are used') }}</span></div><el-button type="primary" plain :disabled="!canWrite" @click="openPicker">{{ l('选择材料','Select material') }}</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="items" size="small" v-loading="loading" empty-text="">
      <el-table-column prop="title" :label="l('材料','Material')" min-width="180" />
      <el-table-column :label="l('来源','Source')" width="150"><template #default="{row}"><el-tag effect="plain">{{ sourceLabel(row.source) }}</el-tag><span v-if="row.source==='PRESALES_RELEASE' && row.solutionVersion" class="version-meta"> V{{ row.solutionVersion }}</span></template></el-table-column>
      <el-table-column prop="applicability" :label="l('适用范围','Applicability')" min-width="220" show-overflow-tooltip />
      <el-table-column :label="l('有效性','Validity')" width="130"><template #default="{row}"><el-tag :type="row.validity==='VALID'?'success':'warning'" effect="plain">{{ validityLabel(row.validity) }}</el-tag></template></el-table-column>
      <el-table-column :label="l('版本与时间','Version and time')" min-width="190"><template #default="{row}"><span>V{{ row.source==='PRESALES_RELEASE'?(row.solutionVersion||row.version):row.version }}</span><small v-if="row.source==='PRESALES_RELEASE' && row.publishedAt" class="version-meta"> · {{ row.publishedAt }}</small><small v-if="row.source==='PRESALES_RELEASE' && row.receivedAt" class="version-meta"> · {{ l('接收','Received') }} {{ row.receivedAt }}</small><small v-if="row.source==='WIKI_PAGE' && row.selectedAt" class="version-meta"> · {{ row.selectedAt }}</small></template></el-table-column>
      <template #empty><el-empty :description="l('尚未选择材料','No materials selected')" /></template>
    </el-table>
    <el-dialog v-model="pickerOpen" :title="l('选择授权知识页','Select an authorized knowledge page')" width="min(660px,94vw)" destroy-on-close :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item :label="l('知识库','Knowledge base')" required><el-select v-model="knowledgeBaseId" filterable :loading="basesLoading" :disabled="busy"><el-option v-for="item in knowledgeBases" :key="String(item.id)" :value="String(item.id)" :label="item.name" /></el-select></el-form-item>
        <el-form-item :label="l('知识页','Knowledge page')" required><el-select v-model="slug" filterable :loading="pagesLoading || pageLoading" :disabled="!knowledgeBaseId || busy"><el-option v-for="item in pages" :key="item.slug" :value="item.slug" :label="item.title" /></el-select></el-form-item>
        <template v-if="selectedPage">
          <div class="page-preview"><strong>{{ selectedPage.title }}</strong><p>{{ selectedPage.content }}</p></div>
          <el-form-item :label="l('适用范围','Applicability')" required><el-input v-model="applicability" maxlength="500" :disabled="busy" :placeholder="l('说明该材料适用于本项目的哪些部分','Describe where this material applies to the project')" /></el-form-item>
        </template>
      </el-form>
      <el-alert v-if="pickerError" :title="pickerError" type="error" :closable="false" />
      <template #footer><el-button @click="pickerOpen=false">{{ l('取消','Cancel') }}</el-button><el-button type="primary" :loading="busy" :disabled="!canBind" @click="bind">{{ l('绑定材料','Bind material') }}</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { biddingApi } from '../api/biddingApi'
import type { BiddingMaterial, Project } from '../api/types'
const props=defineProps<{workspaceId:string;project:Project;items:BiddingMaterial[];loading:boolean;canWrite:boolean;busy?:boolean;error?:string}>()
const emit=defineEmits<{bind:[payload:{kind:'WIKI_PAGE';knowledgeBaseId:string;pageId:string;expectedDigest:string;applicability:string}];changed:[]}>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const pickerOpen=ref(false),basesLoading=ref(false),pagesLoading=ref(false),pageLoading=ref(false),knowledgeBases=ref<{id:string|number;name:string}[]>([]),pages=ref<{id:string;slug:string;title:string;archived?:boolean}[]>([]),knowledgeBaseId=ref(''),slug=ref(''),selectedPage=ref<{id:string;slug:string;title:string;content?:string;archived?:boolean}>(),digest=ref(''),applicability=ref(''),pickerError=ref(''),busy=computed(()=>!!props.busy)
const canBind=computed(()=>!!selectedPage.value&&!selectedPage.value.archived&&!!digest.value&&!!applicability.value.trim()&&props.canWrite&&!busy.value)
const error=computed(()=>props.error||'')
function validityLabel(value:string){return value==='VALID'?l('有效','Valid'):l('不可用','Unavailable')}
function sourceLabel(value:string){return value==='PRESALES_RELEASE'?l('售前发布版本','Presales release'):value==='WIKI_PAGE'?l('知识库页面','Knowledge page'):l('项目材料','Project material')}
let basesEpoch=0,pagesEpoch=0,pageEpoch=0,basesController:AbortController|undefined,pagesController:AbortController|undefined,pageController:AbortController|undefined
function abortBases(){basesController?.abort();basesController=undefined;basesEpoch++}
function abortPages(){pagesController?.abort();pagesController=undefined;pagesEpoch++}
function abortPage(){pageController?.abort();pageController=undefined;pageEpoch++}
function abortReads(){abortBases();abortPages();abortPage()}
function clearPicker(){knowledgeBases.value=[];pages.value=[];knowledgeBaseId.value='';slug.value='';selectedPage.value=undefined;digest.value='';applicability.value='';pickerError.value='';basesLoading.value=false;pagesLoading.value=false;pageLoading.value=false}
function sameScope(ws:string,projectId:string){return pickerOpen.value&&props.workspaceId===ws&&props.project.workspaceId===ws&&props.project.id===projectId}
function current(signal:AbortSignal,epoch:number,expected:number,ws:string,projectId:string){return !signal.aborted&&epoch===expected&&sameScope(ws,projectId)}
async function openPicker(){abortReads();clearPicker();pickerOpen.value=true;const ws=props.workspaceId,projectId=props.project.id;const controller=new AbortController();basesController=controller;const epoch=++basesEpoch;basesLoading.value=true;try{const rows=await biddingApi.knowledgeBases(ws,controller.signal);if(!current(controller.signal,epoch,basesEpoch,ws,projectId))return;knowledgeBases.value=rows}catch(cause){if(!current(controller.signal,epoch,basesEpoch,ws,projectId))return;knowledgeBases.value=[];pickerError.value=denied(cause)?l('知识库授权已变化，材料内容已清除。','Knowledge access changed. Material content was cleared.'):l('知识库读取失败。','Could not load knowledge bases.')}finally{if(epoch===basesEpoch)basesLoading.value=false}}
async function loadPages(){abortPages();abortPage();pages.value=[];slug.value='';selectedPage.value=undefined;digest.value='';applicability.value='';pickerError.value='';const kbId=knowledgeBaseId.value;if(!kbId)return;const ws=props.workspaceId,projectId=props.project.id,selectedSlug=slug.value,controller=new AbortController();pagesController=controller;const epoch=++pagesEpoch;pagesLoading.value=true;try{const rows=await biddingApi.knowledgePages(ws,kbId,controller.signal);if(!current(controller.signal,epoch,pagesEpoch,ws,projectId)||knowledgeBaseId.value!==kbId||slug.value!==selectedSlug)return;pages.value=rows.filter(item=>!item.archived)}catch(cause){if(!current(controller.signal,epoch,pagesEpoch,ws,projectId)||knowledgeBaseId.value!==kbId||slug.value!==selectedSlug)return;pages.value=[];pickerError.value=denied(cause)?l('知识库授权已变化，材料内容已清除。','Knowledge access changed. Material content was cleared.'):l('知识页读取失败。','Could not load knowledge pages.')}finally{if(epoch===pagesEpoch)pagesLoading.value=false}}
async function loadPage(){abortPage();selectedPage.value=undefined;digest.value='';applicability.value='';pickerError.value='';const kbId=knowledgeBaseId.value,selectedSlug=slug.value;if(!kbId||!selectedSlug)return;const ws=props.workspaceId,projectId=props.project.id,controller=new AbortController();pageController=controller;const epoch=++pageEpoch;pageLoading.value=true;try{const page=await biddingApi.knowledgePage(ws,kbId,selectedSlug,controller.signal);if(!current(controller.signal,epoch,pageEpoch,ws,projectId)||knowledgeBaseId.value!==kbId||slug.value!==selectedSlug)return;if(page.archived)throw new Error('Page is archived');const pageDigest=await sha256(page.content||'');if(!current(controller.signal,epoch,pageEpoch,ws,projectId)||knowledgeBaseId.value!==kbId||slug.value!==selectedSlug)return;selectedPage.value=page;digest.value=pageDigest}catch(cause){if(!current(controller.signal,epoch,pageEpoch,ws,projectId)||knowledgeBaseId.value!==kbId||slug.value!==selectedSlug)return;selectedPage.value=undefined;digest.value='';pickerError.value=denied(cause)?l('知识页授权已变化，内容已清除。','Knowledge page access changed. Content was cleared.'):l('知识页不可用，内容已清除。','Knowledge page is unavailable. Content was cleared.')}finally{if(epoch===pageEpoch)pageLoading.value=false}}
async function sha256(value:string){const digestBytes=await globalThis.crypto.subtle.digest('SHA-256',new TextEncoder().encode(value));return [...new Uint8Array(digestBytes)].map(byte=>byte.toString(16).padStart(2,'0')).join('')}
function denied(cause:unknown){const status=(cause as {response?:{status?:number}})?.response?.status;return status===401||status===403||status===404||status===410}
function bind(){if(!canBind.value||!selectedPage.value)return;emit('bind',{kind:'WIKI_PAGE',knowledgeBaseId:knowledgeBaseId.value,pageId:selectedPage.value.id,expectedDigest:digest.value,applicability:applicability.value.trim()})}
watch(knowledgeBaseId,()=>{void loadPages()},{flush:'sync'})
watch(slug,()=>{void loadPage()},{flush:'sync'})
watch(pickerOpen,open=>{if(!open){abortReads();clearPicker()}},{flush:'sync'})
watch(()=>[props.workspaceId,props.project.id] as const,()=>{abortReads();pickerOpen.value=false;clearPicker()},{flush:'sync'})
</script>
<style scoped>
.materials-panel{display:grid;gap:12px;margin-top:24px}.materials-panel>header{display:flex;align-items:center;justify-content:space-between;gap:12px}.materials-panel h2{margin:0;font-size:16px}.materials-panel header span{display:block;margin-top:4px;color:var(--mc-text-secondary);font-size:13px}.materials-panel .el-select{width:100%}.version-meta{color:var(--mc-text-secondary);font-size:12px}.page-preview{max-height:200px;overflow:auto;border:1px solid var(--mc-border);border-radius:4px;padding:10px 12px;margin-bottom:14px;background:var(--mc-bg-muted)}.page-preview p{margin:6px 0 0;white-space:pre-wrap;overflow-wrap:anywhere;color:var(--mc-text-secondary);font-size:13px;line-height:1.6}.el-button:focus-visible{outline:2px solid var(--mc-primary);outline-offset:2px}
</style>
