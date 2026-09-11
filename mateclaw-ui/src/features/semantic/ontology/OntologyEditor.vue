<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { ontologyApi } from '../api/ontologyApi'
import type { AxiomDescriptor, OntologyDocumentSyntax } from '../api/types'
import { useOntologyProjection } from './useOntologyProjection'
import { useOntologyDraft } from './useOntologyDraft'
import ModelingTaskPanel from './components/ModelingTaskPanel.vue'
import ModelingTaskCreate from './components/ModelingTaskCreate.vue'
import ValidationPanel from './components/ValidationPanel.vue'
import LogicalConsistencyPanel from './components/LogicalConsistencyPanel.vue'
import PublishDialog from './components/PublishDialog.vue'
import OntologySourcePanel from './components/OntologySourcePanel.vue'
import OntologyModelWorkbench from './components/OntologyModelWorkbench.vue'
import OntologyLabelEditor from './components/OntologyLabelEditor.vue'
import BusinessPolicyPanel from './components/BusinessPolicyPanel.vue'
import './semantic.css'

const { t, locale } = useI18n(); const tr=(zh:string,en:string)=>locale.value.startsWith('zh')?zh:en; const route = useRoute(); const router = useRouter(); const workspace = useWorkspaceStore()
const ontologyId = () => String(route.params.id); const workspaceId = () => workspace.currentWorkspaceId ?? ''
const draft = useOntologyDraft(ontologyId, workspaceId, undefined, () => workspace.can('manage:ontology'))
const { document, axioms, name, description, id, dirty, busy, version, draftVersion, validationReport, saveError, canPublish, publicationPending, editPending } = draft
const { data: projectionData, loading: projectionLoading, error: projectionError, reload: reloadProjection } = useOntologyProjection(()=>({workspaceId:workspaceId(),ontologyId:ontologyId(),revisionId:id.value || '',draftVersion:draftVersion.value}))
const modelingOpen=ref(false)
function selectTask(taskId:string){area.value='suggestions';void router.replace({query:{...route.query,taskId}})}
async function taskChanged(decision: 'ACCEPT' | 'REJECT'){await draft.load();await reloadProjection();if(decision==='ACCEPT')area.value='model'}
const editable = computed(() => workspace.can('manage:ontology') && !publicationPending.value)
const selectedAxiom = ref<AxiomDescriptor | null>(null); const newAxiom = ref(''); const tab = ref<'model' | 'editor' | 'axioms'>('model'); const publishOpen = ref(false)
const focusedAxiomId = ref('')
const taskEntry = () => typeof route.query.taskId === 'string' && !!route.query.taskId
const area=ref<'model'|'suggestions'|'policy'|'sources'|'publish'>(taskEntry()?'suggestions':'model');
const modelOpened=ref(!taskEntry())
watch(area,value=>{if(value==='model')modelOpened.value=true})
watch(()=>route.query.taskId,()=>{if(taskEntry())area.value='suggestions'})
const metadataOpen=ref(false);const toolsOpen=ref(false);const importInput=ref<HTMLInputElement>();const importing=ref(false)
const businessPolicyPending = ref(false)
const businessPolicyDirty = ref(false)
async function businessPolicySaved() { await draft.load(); await reloadProjection() }
function navigateArea(next: 'model'|'suggestions'|'policy'|'sources'|'publish') { if (businessPolicyPending.value || businessPolicyDirty.value) { ElMessage.warning(tr('请先保存业务规则，再离开当前编辑。','Save business rules before leaving this edit.')); return } area.value = next }
async function checkModel(){area.value='publish';await draft.validate()}
async function mainAction(){if(dirty.value)await draft.save();else if(canPublish.value&&workspace.can('publish:ontology'))publishOpen.value=true;else await checkModel()}
async function importFile(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file||!document.value)return;importing.value=true;try{const text=await file.text();if(!text.trim())throw Error(tr('文件内容为空','Empty document'));document.value={...document.value,syntax:/^\s*(?:Prefix|Ontology)\s*\(/.test(text)?'FUNCTIONAL':'RDF_XML',documentText:text};toolsOpen.value=false;tab.value='model';area.value='model';ElMessage.success(tr('已载入文件，请保存并检查模型。','Document loaded. Save and check the model.'))}catch(e){ElMessage.error((e as Error).message)}finally{importing.value=false;(event.target as HTMLInputElement).value=''}}
function more(command:string){if(command==='history')void router.push({name:'OntologyVersions',params:{id:ontologyId()}});else if(command==='discard')void discard();else if(command==='metadata')metadataOpen.value=true;else toolsOpen.value=true}
const workbenchKey = computed(() => `${workspaceId()}:${ontologyId()}:${id.value || 'draft'}`)
const unsaved = computed(() => dirty.value || businessPolicyDirty.value)
async function confirmLeave() {
  if (editPending.value) { ElMessage.warning('模型保存结果尚未确定，请先恢复本次保存。'); return false }
  if (businessPolicyPending.value) { ElMessage.warning(tr('业务规则保存结果尚未确定，请先恢复本次保存。','Recover the business rule save before leaving.')); return false }
  if (publicationPending.value) { ElMessage.warning(t('semantic.pendingPublication')); return false }
  if (!unsaved.value) return true
  try { await ElMessageBox.confirm(t('semantic.leaveWarning'), t('semantic.unsaved'), { confirmButtonText: t('semantic.leave'), cancelButtonText: t('semantic.stay'), type: 'warning' }); return true } catch { return false }
}
const unregister = workspace.registerBeforeSwitch(confirmLeave); onBeforeRouteLeave(confirmLeave); onBeforeRouteUpdate(confirmLeave)
function unload(event: BeforeUnloadEvent) { if (unsaved.value || publicationPending.value || editPending.value || businessPolicyPending.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload); onBeforeUnmount(() => { unregister(); window.removeEventListener('beforeunload', unload) })
async function removeAxiom(axiom: AxiomDescriptor) { if (!editable.value || dirty.value || busy.value) return; try { await ElMessageBox.confirm(t('semantic.deleteWarning', { key: axiom.axiomId }), t('semantic.delete'), { confirmButtonText: t('semantic.delete'), cancelButtonText: t('semantic.cancel'), type: 'warning' }); await draft.edit([{ kind: 'REMOVE', axiomId: axiom.axiomId }]); selectedAxiom.value = null } catch { /* cancelled */ } }
async function addAxiom() { if (!newAxiom.value.trim() || dirty.value || busy.value || !editable.value) return; if (await draft.edit([{ kind: 'ADD', functionalSyntax: newAxiom.value.trim() }])) newAxiom.value = '' }
async function exportRevision(syntax: OntologyDocumentSyntax) {
  if (!id.value || dirty.value || busy.value) return; try { const bytes = await ontologyApi.exportDraftDocument(workspaceId(), ontologyId(), draftVersion.value, syntax); const blob = new Blob([bytes], { type: syntax === 'RDF_XML' ? 'application/rdf+xml' : 'text/plain;charset=utf-8' }); const url = URL.createObjectURL(blob); const link = globalThis.document.createElement('a'); link.href = url; link.download = `ontology.${syntax === 'RDF_XML' ? 'rdf' : 'ofn'}`; link.click(); URL.revokeObjectURL(url) } catch (error) { ElMessage.error((error as Error).message) }
}
async function publish(note: string) { const result = await draft.publish(note); if (result) { publishOpen.value = false; await router.push({ name: 'OntologyVersions', params: { id: ontologyId() }, query: { revision: result.id } }) } }
async function discard() { try { await ElMessageBox.confirm(t('semantic.discardWarning'), t('semantic.discard'), { confirmButtonText: t('semantic.discard'), cancelButtonText: t('semantic.cancel'), type: 'warning' }); if (await draft.discard()) await router.push({ name: 'OntologyVersions', params: { id: ontologyId() } }) } catch { /* cancelled */ } }
function sourceChanged(nextVersion?: number) { if (nextVersion !== undefined) draftVersion.value = nextVersion; validationReport.value = null }
function inspectAxiom(axiomId: string) {
  focusedAxiomId.value = axiomId
  area.value = 'sources'
  void nextTick(() => {
    const panel = globalThis.document.querySelector<HTMLElement>('[data-ontology-source-panel]')
    if (panel) {
      panel.focus({ preventScroll: true })
      if (typeof panel.scrollIntoView === 'function') panel.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  })
}
function clearAxiomFocus() { focusedAxiomId.value = '' }
watch(() => [workspace.currentWorkspaceId, ontologyId(), id.value], () => { businessPolicyDirty.value=false;businessPolicyPending.value=false;focusedAxiomId.value = '';area.value=taskEntry()?'suggestions':'model';tab.value='model';metadataOpen.value=false;toolsOpen.value=false }, { flush: 'sync' })
onMounted(draft.load)
</script>
<template>
 <section class="semantic-page ontology-business-editor">
  <header class="editor-header">
  <div class="editor-heading"><el-button link :disabled="businessPolicyPending||businessPolicyDirty" @click="router.push({name:'OntologyList'})">← {{tr('本体管理','Ontologies')}}</el-button><h1>{{name||tr('业务模型','Business model')}}</h1><span class="semantic-muted">v{{version}} · {{tr(unsaved?'未保存':'已保存',unsaved?'Unsaved':'Saved')}}</span></div>
   <div class="editor-actions"><el-button v-if="editable" :disabled="busy||dirty||editPending||businessPolicyPending||businessPolicyDirty" @click="modelingOpen=true">开始建模</el-button>
    <el-dropdown trigger="click" :disabled="businessPolicyPending||businessPolicyDirty" @command="more"><el-button :disabled="businessPolicyPending||businessPolicyDirty">{{tr('更多','More')}} ▾</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="metadata">{{tr('基本信息','Basic information')}}</el-dropdown-item><el-dropdown-item command="history">{{t('semantic.history')}}</el-dropdown-item><el-dropdown-item command="exchange">{{tr('OWL 导入与导出','OWL import and export')}}</el-dropdown-item><el-dropdown-item v-if="editable&&id" command="discard" divided :disabled="busy||editPending||businessPolicyPending||businessPolicyDirty">{{t('semantic.discard')}}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
    <el-button v-if="editable&&id" type="primary" :loading="busy" :disabled="editPending||businessPolicyPending||businessPolicyDirty" @click="mainAction">{{dirty?t('semantic.save'):canPublish&&workspace.can('publish:ontology')?t('semantic.publish'):tr('结构检查','Structural checks')}}</el-button>
   </div>
  </header>
  <el-alert v-if="editPending&&!busy" type="warning" :title="tr('上次保存结果待确认，请先恢复保存。','Recover the previous save before continuing.')" :closable="false"/><el-button v-if="editPending" :loading="busy" @click="draft.retryEdit">{{tr('恢复保存','Recover save')}}</el-button>
  <el-alert v-if="publicationPending" type="warning" :title="t('semantic.pendingPublication')" :closable="false"/><el-button v-if="publicationPending" :loading="busy" @click="publish('')">{{t('semantic.recoverPublication')}}</el-button>
  <el-alert v-if="!editable&&!publicationPending" type="info" :title="t('semantic.readonly')" :closable="false"/>
  <el-alert v-if="saveError" type="error" :title="saveError.message||t(`semantic.errors.${saveError.code}`,t('semantic.requestFailed'))" :closable="false"/>
  <nav class="editor-navigation" :aria-label="tr('模型工作区','Model workspace')"><button v-for="item in ([['model',tr('业务模型','Business model')],['suggestions',tr('建模建议','Modeling suggestions')],['policy',tr('业务规则','Business rules')],['sources',tr('参考资料','References')],['publish',tr('检查发布','Check and publish')]] as const)" :key="item[0]" type="button" :disabled="businessPolicyPending" :aria-current="area===item[0]?'page':undefined" :class="{active:area===item[0]}" @click="navigateArea(item[0])">{{item[1]}}</button></nav>
  <div v-if="document" v-loading="busy" class="editor-workspace">
   <ModelingTaskCreate v-model="modelingOpen" :ontology-id="ontologyId()" @created="selectTask"/>
   <section v-show="area==='suggestions'" class="editor-suggestions">
    <ModelingTaskPanel :active="area==='suggestions'" :projection="projectionData?.projection" :ontology-id="ontologyId()" :task-id="typeof route.query.taskId==='string'?route.query.taskId:undefined" :can-manage="editable" :disabled="busy||dirty||editPending||businessPolicyDirty||businessPolicyPending" @selected="selectTask" @changed="taskChanged"/>
   </section>
   <section v-if="modelOpened" v-show="area==='model'" class="editor-design">
    <div v-if="tab!=='model'" class="semantic-toolbar"><el-button @click="tab='model'">← {{tr('返回业务模型','Back to business model')}}</el-button><el-radio-group v-model="tab"><el-radio-button value="editor">OWL</el-radio-button><el-radio-button value="axioms">{{tr('底层规则','Underlying rules')}}</el-radio-button></el-radio-group></div>
    <OntologyModelWorkbench v-if="tab==='model'" :key="workbenchKey" :projection="projectionData?.projection" :projection-loading="projectionLoading" :projection-error="projectionError" @reload-projection="reloadProjection" :axioms="axioms" :editable="editable&&!!id" :disabled="busy||dirty||editPending||businessPolicyDirty||businessPolicyPending" :edit-pending="editPending" :retry-edit="draft.retryEdit" :reload-draft="draft.load" :failure-message="saveError?.message" :apply="draft.edit" :apply-model="draft.modelEdit" @advanced="toolsOpen=true" @inspect-axiom="inspectAxiom"/>
    <el-alert v-if="dirty" type="info" :closable="false" :title="tr('请先保存修改，再继续编辑模型。','Save your changes before editing the model.')"/>
    <template v-if="tab === 'editor'"><el-form-item :label="t('semantic.documentText', 'OWL Functional Syntax document')"><el-input v-model="document.documentText" :disabled="!editable || busy || editPending || businessPolicyPending" type="textarea" :rows="24" spellcheck="false" input-style="font-family: ui-monospace, monospace; line-height: 1.5" /></el-form-item><p class="semantic-muted">{{ document.documentText.length }} characters · {{ document.imports.length }} pinned imports · {{ document.policy.rules.length }} policy rules</p></template><template v-else-if="tab === 'axioms'"><div class="semantic-axiom-list"><el-table :data="axioms" :empty-text="t('semantic.emptyAxioms', 'No axioms')" @row-click="selectedAxiom = $event"><el-table-column prop="axiomType" :label="t('semantic.axiomType', 'Type')" width="190" /><el-table-column prop="rendering" :label="t('semantic.rendering', 'Functional rendering')" min-width="520" show-overflow-tooltip /><el-table-column :label="t('semantic.signature', 'Signature')" min-width="220"><template #default="{ row }">{{ row.signatureIris.join(', ') }}</template></el-table-column><el-table-column v-if="editable" width="100"><template #default="{ row }"><el-button link type="danger" @click.stop="removeAxiom(row)">{{ t('semantic.delete') }}</el-button></template></el-table-column></el-table><el-card v-if="selectedAxiom" class="semantic-axiom-detail"><code>{{ selectedAxiom.rendering }}</code><p>{{ selectedAxiom.annotations.length }} annotations · {{ selectedAxiom.signatureIris.length }} entities</p></el-card><OntologyLabelEditor v-if="editable" :axioms="axioms" :disabled="busy || dirty" @apply="draft.edit" /><el-form v-if="editable" label-position="top" @submit.prevent="addAxiom"><el-form-item :label="t('semantic.addAxiom', 'Add one Functional Syntax axiom')"><el-input v-model="newAxiom" type="textarea" :rows="4" spellcheck="false" /><el-button native-type="submit" type="primary" :disabled="!newAxiom.trim() || dirty || busy">{{ t('semantic.add') }}</el-button></el-form-item></el-form></div></template>

   </section>
   <section v-show="area==='policy'" class="editor-policy">
    <BusinessPolicyPanel :key="workbenchKey" :ontology-id="ontologyId()" :draft-version="draftVersion" :policy="document.policy" :projection="projectionData?.projection" :editable="editable&&!!id" :disabled="busy||dirty||editPending" @saved="businessPolicySaved" @pending-change="businessPolicyPending=$event" @dirty-change="businessPolicyDirty=$event" />
   </section>
   <section v-show="area==='sources'" class="editor-references"><OntologySourcePanel data-ontology-source-panel tabindex="-1" :ontology-id="ontologyId()" :revision-id="id||undefined" :projection="projectionData?.projection" :focused-axiom-id="focusedAxiomId" @changed="sourceChanged" @clear-focus="clearAxiomFocus" :draft-version="id?draftVersion:undefined" :axioms="axioms" :can-manage="editable&&!!id&&!dirty&&!busy&&!editPending" :can-review="workspace.can('publish:ontology')&&!editPending"/></section>
   <section v-show="area==='publish'" class="editor-release">
    <h2>{{tr('发布前检查','Before publishing')}}</h2>
    <div class="editor-check-row"><div><strong>{{tr('保存修改','Save changes')}}</strong><p>{{tr(dirty?'还有未保存的修改':'当前修改已保存',dirty?'Unsaved changes remain':'Changes are saved')}}</p></div><el-button v-if="dirty" :disabled="busy||!editable" @click="draft.save">{{t('semantic.save')}}</el-button></div>
    <div class="editor-check-row"><div><strong>{{tr('结构检查','Structural checks')}}</strong><p>{{validationReport?.valid&&!dirty?tr('结构检查通过','Structural checks passed'):tr('检查模型结构','Check model structure')}}</p></div><el-button :disabled="dirty||busy||editPending||!editable" @click="draft.validate">{{tr('重新检查','Run checks')}}</el-button></div>
    <ValidationPanel v-if="validationReport" :report="validationReport" :dirty="dirty" @locate="area='model';tab='axioms'"/>
    <LogicalConsistencyPanel :ontology-id="ontologyId()" :draft-version="draftVersion" :dirty="dirty" :projection="projectionData?.projection" :editable="editable&&!!id" :disabled="busy||editPending||businessPolicyPending||businessPolicyDirty" />
    <div class="editor-check-row"><div><strong>{{tr('发布版本','Publish version')}}</strong><p>{{tr('通过检查后填写发布说明。','Add release notes after checks pass.')}}</p></div><el-button v-if="workspace.can('publish:ontology')" :disabled="!canPublish" @click="publishOpen=true">{{t('semantic.publish')}}</el-button></div>
   </section>
  </div>
  <el-dialog v-model="metadataOpen" :title="tr('基本信息','Basic information')" width="min(560px,94vw)"><el-form label-position="top" :disabled="!editable||busy||editPending"><el-form-item :label="t('semantic.name')"><el-input id="ontology-name" v-model="name" maxlength="128"/></el-form-item><el-form-item :label="t('semantic.description')"><el-input id="ontology-description" v-model="description" type="textarea" :rows="3"/></el-form-item></el-form><template #footer><el-button @click="metadataOpen=false">{{tr('完成','Done')}}</el-button></template></el-dialog>
  <el-dialog v-model="toolsOpen" :title="tr('OWL 导入与导出','OWL import and export')" width="min(560px,94vw)"><div class="editor-exchange"><label>{{tr('导入模型文件','Import model file')}}<input ref="importInput" type="file" accept=".ofn,.fss,.owl,.rdf,.xml" :disabled="!editable||busy||editPending||importing" @change="importFile"/></label><div><strong>{{tr('导出当前模型','Export current model')}}</strong><div class="editor-exchange-actions"><el-button :disabled="!id||dirty||busy" @click="exportRevision('FUNCTIONAL')">OWL Functional</el-button><el-button :disabled="!id||dirty||busy" @click="exportRevision('RDF_XML')">OWL RDF/XML</el-button></div></div><details><summary>{{tr('高级维护','Advanced maintenance')}}</summary><el-button link @click="area='model';tab='editor';toolsOpen=false">{{tr('编辑 OWL 文档','Edit OWL document')}}</el-button><el-button link @click="area='model';tab='axioms';toolsOpen=false">{{tr('查看底层规则','Inspect underlying rules')}}</el-button></details></div></el-dialog>
  <PublishDialog v-if="publishOpen" v-model="publishOpen" :busy="busy" :diff="null" @publish="publish"/>
 </section>
</template>
<style scoped>
.editor-header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:12px;padding:0 36px}.editor-heading{min-width:0;display:flex;align-items:center;gap:12px;flex-wrap:wrap}.editor-heading h1{font-size:20px;font-weight:600;margin:0;overflow-wrap:anywhere}.editor-heading>.el-button{font-size:12px}.editor-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;flex-shrink:0}.editor-navigation{display:flex;gap:24px;flex-wrap:wrap;padding:0 20px;border-bottom:1px solid var(--mc-border);margin-bottom:0}.editor-navigation>button{border:0;border-bottom:2px solid transparent;background:transparent;color:var(--mc-text-secondary);padding:12px 0;font-size:14px;cursor:pointer}.editor-navigation>button.active{color:var(--mc-action-text,var(--el-color-primary));border-bottom-color:var(--el-color-primary);font-weight:600}.editor-navigation>button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.editor-workspace{background:var(--mc-bg-elevated);padding:16px 20px;min-width:0}.editor-suggestions{min-width:0}.editor-release{max-width:860px;padding:0 16px}.editor-release h2{font-size:18px;margin:12px 0 24px}.editor-check-row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 0;border-bottom:1px solid var(--mc-border)}.editor-check-row strong{font-size:14px}.editor-check-row p{font-size:13px;color:var(--mc-text-secondary);margin:8px 0 0}.editor-exchange{display:grid;gap:24px}.editor-exchange label{display:grid;gap:12px}.editor-exchange-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.editor-exchange details{border-top:1px solid var(--mc-border);padding-top:16px;font-size:13px}.editor-exchange summary{cursor:pointer;margin-bottom:12px}.editor-design{min-width:0}.editor-references{min-width:0}@media(max-width:650px){.editor-header{align-items:flex-start;gap:12px;flex-wrap:wrap;padding:0 12px}.editor-heading h1{font-size:20px}.editor-actions{margin-left:auto}.editor-navigation{gap:12px;padding:0 12px;flex-wrap:nowrap;overflow-x:auto}.editor-navigation>button{flex-shrink:0;font-size:13px}.editor-references,.editor-release,.editor-suggestions{padding:0}.editor-check-row{align-items:flex-start;flex-wrap:wrap}.editor-workspace{padding:12px}}
.editor-design :deep(.model-bar){padding:4px 16px 12px}.editor-design :deep(.model-note){padding:0 16px}.editor-actions .el-button+.el-button{margin-left:0}@media(max-width:650px){.editor-design :deep(.model-bar){padding:0 0 12px}.editor-design :deep(.model-note){padding:0}}
.editor-model-tabs{padding:0 16px 12px;margin:0}.editor-model-tabs .el-radio-group{max-width:100%}
</style>
