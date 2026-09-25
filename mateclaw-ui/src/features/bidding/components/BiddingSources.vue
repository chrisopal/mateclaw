<template>
  <section class="source-panel">
    <header><h2>{{ l('招标文件', 'Tender sources') }}</h2><div class="actions">
      <el-select v-model="sourceKind" :disabled="!canWrite" size="small"><el-option label="招标文件" value="TENDER" /><el-option label="附件" value="ATTACHMENT" /><el-option label="补遗" value="ADDENDUM" /></el-select>
      <input ref="fileInput" class="file-input" type="file" accept=".pdf,.docx" :disabled="!canWrite || uploading" @change="chooseFile" />
      <el-button type="primary" :loading="uploading" :disabled="!canWrite || uploading" @click="fileInput?.click()">{{ l('上传文件', 'Upload file') }}</el-button>
      <el-button type="primary" plain :disabled="!canApprove || !latestSources.length || !!confirmationBlocked || confirming" :loading="confirming" @click="confirmSet">{{ l('确认来源', 'Confirm sources') }}</el-button>
      <el-button type="primary" plain :disabled="!canWrite || !sourceSet?.ref || !analystReady || activeAnalysis" :loading="dispatching" @click="dispatch">{{ l('开始解析', 'Start analysis') }}</el-button>
      <el-button type="primary" plain size="small" @click="$emit('tasks')">{{ l('任务记录', 'Task records') }}</el-button>
    </div></header>
    <el-alert v-if="notice" :title="notice" :type="noticeType" :closable="false" />
    <div class="table-scroll"><el-table :data="sources" row-key="sourceId" v-loading="loading" empty-text="">
      <el-table-column :label="l('文件 / 版本', 'File / version')" min-width="220"><template #default="{ row }"><strong>{{ row.filename || row.sourceId }}</strong><small>{{ row.sourceId }} · V{{ row.version }}</small></template></el-table-column>
      <el-table-column :label="l('类型', 'Type')" width="130"><template #default="{ row }">{{ kindLabel(row.kind) }}</template></el-table-column>
      <el-table-column :label="l('读取状态', 'Read status')" width="155"><template #default="{ row }"><el-tag :type="statusType(row.readStatus)" effect="plain">{{ statusLabel(row.readStatus) }}</el-tag></template></el-table-column>
      <el-table-column :label="l('读取问题', 'Read issues')" min-width="220"><template #default="{ row }"><span>{{ (row.problems || []).join(', ') || '—' }}</span><div v-for="block in emptyPages(row)" :key="block.id" class="exclusion"><el-checkbox v-model="exclusionState(row, block).selected">{{ l('排除空白页', 'Exclude blank page') }} · {{ block.locator }}</el-checkbox><el-input v-if="exclusionState(row, block).selected" v-model="exclusionState(row, block).reason" size="small" maxlength="500" :placeholder="l('填写排除原因', 'Reason for exclusion')" /></div></template></el-table-column>
      <el-table-column :label="l('操作', 'Actions')" width="185" fixed="right"><template #default="{ row }"><div class="actions"><el-button type="primary" plain size="small" :disabled="row.readStatus === 'FAILED' || !row.blocks?.length" @click="$emit('preview', row)">{{ l('查看原文', 'View text') }}</el-button><el-button v-if="['FAILED','NEEDS_REVIEW'].includes(row.readStatus)" type="primary" plain size="small" :disabled="!canWrite" @click="$emit('retry-read', row)">{{ l('重试读取', 'Retry read') }}</el-button></div></template></el-table-column>
      <template #empty><el-empty :description="l('尚未上传文件', 'No files uploaded')" /></template>
    </el-table></div>
    <div v-if="sourceSet?.ref" class="source-set">{{ l('当前确认来源版本', 'Current confirmed source set') }} · V{{ sourceSet.ref.version }}</div>
    <p v-if="confirmationBlocked" class="state-note">{{ confirmationBlocked }}</p>
    <p v-if="!canApprove" class="state-note">{{ l('确认来源需要工作区管理员。', 'Source confirmation requires a workspace administrator.') }}</p>
    <p v-if="!analystReady" class="state-note">{{ l('解析暂不可用：请配置招标分析员、所需技能与模型。', 'Analysis unavailable: configure an analysis employee, skills and model.') }}</p>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Project, Source } from '../api/types'
import { biddingApi } from '../api/biddingApi'
import { operationId } from '../shared/state'
const props = defineProps<{ sources: Source[]; project: Project; sourceSet: { ref?: Project['ref'] } | null; loading: boolean; canWrite: boolean; canApprove: boolean; analystReady: boolean; activeAnalysis: boolean; dispatching: boolean }>()
const emit = defineEmits<{ preview:[Source]; tasks:[]; dispatch:[]; 'retry-read':[Source]; changed:[] }>()
const { locale } = useI18n(), l = (zh:string,en:string) => String(locale.value).startsWith('zh') ? zh : en
const sourceKind = ref('TENDER'), fileInput = ref<HTMLInputElement>(), uploading = ref(false), confirming = ref(false), notice = ref(''), noticeType = ref<'success'|'warning'|'error'>('success')
const latestSources = computed(() => {
  const latest = new Map<string, Source>()
  for (const source of props.sources) {
    const current = latest.get(source.sourceId)
    if (!current || source.version > current.version) latest.set(source.sourceId, source)
  }
  return [...latest.values()]
})
const exclusionChoices = reactive<Record<string,{ selected:boolean; reason:string }>>({})
const sourceExclusions = computed(() => latestSources.value.flatMap(source => emptyPages(source).filter(block => exclusionState(source,block).selected && exclusionState(source,block).reason.trim()).map(block => ({ sourceRef:{ kind:'source',id:source.sourceId,version:source.version,digest:source.digest },blockId:block.id,reason:exclusionState(source,block).reason.trim() }))))
const confirmationBlocked = computed(() => {
  const blocked = latestSources.value.filter(source => source.readStatus !== 'READY' && source.readStatus !== 'NEEDS_REVIEW' || source.readStatus === 'NEEDS_REVIEW' && (!reviewSupported(source) || emptyPages(source).some(block => !exclusionState(source,block).selected || !exclusionState(source,block).reason.trim())))
  return blocked.length ? l('存在未就绪或未核实文件。仅可在负责人明确排除每个空白页并填写原因后确认；不可排除的读取问题须先修复。', 'Some files are not ready or need review. Confirm only after the owner explicitly excludes every blank page with a reason; other read issues must be fixed first.') : ''
})
function kindLabel(kind:string) { return ({ TENDER:l('招标文件','Tender'), ATTACHMENT:l('附件','Attachment'), ADDENDUM:l('补遗','Addendum') } as Record<string,string>)[kind] || kind }
function statusLabel(status:string) { return ({ READY:l('可读取','Ready'), NEEDS_REVIEW:l('待核实','Review needed'), PENDING:l('等待读取','Queued'), READING:l('读取中','Reading'), FAILED:l('读取失败','Read failed') } as Record<string,string>)[status] || status }
function statusType(status:string) { return status === 'READY' ? 'success' : ['FAILED','NEEDS_REVIEW'].includes(status) ? 'warning' : 'info' }
function chooseFile(event: Event) { const input=event.target as HTMLInputElement; const file=input.files?.[0]; if(file) void emitUpload(file); input.value='' }
async function emitUpload(file: File) { uploading.value=true; notice.value=''; try { emit('changed'); await biddingApi.upload(props.project.workspaceId, props.project.id, file, sourceKind.value, operationId()); emit('changed') } catch { noticeType.value='error'; notice.value=l('文件上传失败。', 'File upload failed.') } finally { uploading.value=false } }
function emptyPages(source:Source) { return (source.blocks || []).filter(block => block.kind === 'EMPTY_PAGE') }
function reviewSupported(source:Source) { return emptyPages(source).length > 0 && (source.problems || []).every(problem => problem.startsWith('EMPTY_PDF_PAGE:')) && (source.blocks || []).every(block => ['TEXT','EMPTY_PAGE'].includes(block.kind)) }
function exclusionState(source:Source,block:{ id:string }) { const key=`${source.sourceId}:${source.version}:${block.id}`; return exclusionChoices[key] ||= { selected:false,reason:'' } }
async function confirmSet() { if (confirmationBlocked.value || !latestSources.value.length) return; confirming.value=true; notice.value=''; try { await biddingApi.command(props.project.workspaceId, props.project.id, { operationId: operationId(), expected: props.project.ref, action:'CONFIRM_SOURCE_SET', payload:{ expectedSourceSetRef: props.sourceSet?.ref || null, sourceRefs: latestSources.value.map(source => ({ kind:'source', id:source.sourceId, version:source.version, digest:source.digest })), exclusions:sourceExclusions.value } }); emit('changed') } catch { noticeType.value='error'; notice.value=l('来源未确认。请检查读取状态和排除原因。', 'Sources were not confirmed. Check read status and exclusion reasons.') } finally { confirming.value=false } }
function dispatch() { emit('dispatch') }
</script>
<style scoped>
.source-panel { display:grid; gap:14px; min-width:0; }.source-panel>header { display:flex; justify-content:space-between; align-items:center; gap:16px; flex-wrap:wrap; }h2 { margin:0; font-size:16px; }.actions { display:flex; align-items:center; gap:8px; flex-wrap:nowrap; }.actions .el-select { width:120px; }.file-input { position:absolute; width:1px; height:1px; opacity:0; pointer-events:none; }.table-scroll { overflow:auto; border:1px solid var(--mc-border); border-radius:6px; }.el-table small { display:block; margin-top:3px; color:var(--mc-text-secondary); font-size:12px; }.source-set,.state-note { color:var(--mc-text-secondary); font-size:13px; margin:0; }.source-set { padding:10px 12px; background:var(--mc-bg-muted); border-radius:4px; }.exclusion { display:grid; gap:6px; margin-top:8px; }.el-button:focus-visible { outline:2px solid var(--mc-primary); outline-offset:2px; }@media(max-width:768px) { .source-panel>header { align-items:flex-start; }.actions { flex-wrap:wrap; justify-content:flex-start; }.actions>* { max-width:100%; } }
</style>
