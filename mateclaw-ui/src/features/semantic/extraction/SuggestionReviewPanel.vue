<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { extractionApi, type ExtractionSuggestion, type SuggestionEdit } from '../api/extractionApi'
import type { Definition, SemanticEntity } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'
import { evidenceSelection } from '../review/evidenceSelection'
import { evidenceParts } from './extractionEvidence'
import { semanticError } from '../api/semanticErrors'
const props = defineProps<{ graphId: string; suggestion: ExtractionSuggestion; sourceText: string; entities: SemanticEntity[]; definition: Definition }>()
const emit = defineEmits<{ saved: []; submitted: [id: string]; register: [] }>()
const { t, te } = useI18n(), { begin, workspace } = useSemanticScope()
const form = ref<ExtractionSuggestion>(JSON.parse(JSON.stringify(props.suggestion))), baseline = ref(''), busy = ref(false), error = ref(''), selecting = ref(false)
const operation = ref({ payload: '', id: '' })
const dirty = computed(() => JSON.stringify(form.value) !== baseline.value)
const readOnly = computed(() => form.value.status !== 'OPEN')
const owners = computed(() => props.entities.filter(e => e.typeKey === form.value.subjectTypeKey))
const targets = computed(() => props.entities.filter(e => e.typeKey === form.value.targetTypeKey))
const terms = computed(() => [
  ...props.definition.properties.filter(p => p.ownerTypeKey === form.value.subjectTypeKey).map(p => ({ key: `PROPERTY:${p.key}`, label: p.label, kind: 'PROPERTY', predicateKey: p.key, valueType: p.valueType, unit: p.fixedUnit, target: null })),
  ...props.definition.relations.filter(r => r.sourceTypeKey === form.value.subjectTypeKey).map(r => ({ key: `RELATION:${r.key}`, label: r.label, kind: 'RELATION', predicateKey: r.key, valueType: null, unit: null, target: r.targetTypeKey })),
])
const predicate = computed({ get: () => `${form.value.predicateKind}:${form.value.predicateKey}`, set: key => {
  const term = terms.value.find(p => p.key === key); if (!term) return
  Object.assign(form.value, { predicateKind: term.kind, predicateKey: term.predicateKey, valueType: term.valueType, unit: term.unit, targetTypeKey: term.target, targetEntityId: null, value: null })
} })
const parts = computed(() => form.value.quotes.map(q => evidenceParts(props.sourceText, q)))
const canSubmit = computed(() => !dirty.value && !readOnly.value && !!form.value.subjectId && (form.value.predicateKind !== 'RELATION' || !!form.value.targetEntityId) && form.value.diagnostics.length === 0 && parts.value.length > 0 && parts.value.every(Boolean))
watch(() => props.suggestion, value => { form.value = JSON.parse(JSON.stringify(value)); baseline.value = JSON.stringify(form.value); error.value = ''; selecting.value = false }, { immediate: true })
async function confirmLeave() {
  if (!dirty.value && !busy.value) return true
  try { await ElMessageBox.confirm(t('semantic.leaveWarning'), t('semantic.unsaved'), { confirmButtonText: t('semantic.leave'), cancelButtonText: t('semantic.stay'), type: 'warning' }); return true } catch { return false }
}
onBeforeRouteLeave(confirmLeave); onBeforeRouteUpdate(confirmLeave)
const unregister = workspace.registerBeforeSwitch(confirmLeave)
const unload = (event: BeforeUnloadEvent) => { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { unregister(); window.removeEventListener('beforeunload', unload) })
defineExpose({ confirmLeave })
function selectQuote(event: Event) {
  const text = event.target as HTMLTextAreaElement
  try { form.value.quotes = [evidenceSelection(props.sourceText, text.selectionStart, text.selectionEnd)]; error.value = '' } catch { /* Keep existing quote on an empty selection. */ }
}
function handleError(e: unknown) {
  const failure = semanticError(e); error.value = failure.message
  if ([401, 403, 404].includes(failure.status) || ['SOURCE_UNAVAILABLE', 'SNAPSHOT_EXCLUDED', 'BINDING_CHANGED'].includes(failure.code)) emit('saved')
}
function operationId(payload: string) { if (operation.value.payload !== payload) operation.value = { payload, id: crypto.randomUUID() }; return operation.value.id }
async function save(status = 'OPEN') {
  if (busy.value) return
  const run = begin(); busy.value = true; error.value = ''
  const { id, version, diagnostics: _diagnostics, statementId: _statement, pendingOperationId: _pendingOperation, ...content } = form.value
  const data: SuggestionEdit = { ...content, status, expectedVersion: version, operationId: operationId(JSON.stringify({ ...content, status, version })) }
  try { const result = await extractionApi.edit(run.id, props.graphId, id, data, run.signal); if (run.current()) { form.value = result; baseline.value = JSON.stringify(result); emit('saved') } }
  catch (e) { if (run.current()) handleError(e) }
  finally { if (run.current()) busy.value = false }
}
async function submit() {
  if (busy.value || !canSubmit.value) return
  const run = begin(); busy.value = true; error.value = ''
  try {
    const result = await extractionApi.submit(run.id, props.graphId, form.value.id, { expectedVersion: form.value.version, operationId: form.value.pendingOperationId ?? `m5-submit:${form.value.id}:${form.value.version}` }, run.signal)
    if (run.current()) { baseline.value = JSON.stringify(form.value); emit('submitted', result.statementId); emit('saved') }
  } catch (e) { if (run.current()) handleError(e) }
  finally { if (run.current()) busy.value = false }
}
</script>
<template>
  <div class="review-columns">
    <section class="source-pane">
      <h3>{{ t('semantic.w.snapshotText') }}</h3>
      <p>{{ t('semantic.extraction.evidenceHelp') }}</p>
      <template v-for="(part, index) in parts" :key="index">
        <pre v-if="part" class="evidence-text"><span>{{ part.before }}</span><mark>{{ part.quote }}</mark><span>{{ part.after }}</span></pre>
        <el-alert v-else :title="t('semantic.extraction.invalidQuote')" type="error" :closable="false" />
      </template>
      <el-button v-if="!readOnly" @click="selecting = !selecting">{{ t('semantic.extraction.reselect') }}</el-button>
      <textarea v-if="selecting" :value="sourceText" readonly :aria-label="t('semantic.w.snapshotText')" @select="selectQuote" />
    </section>
    <el-form label-position="top" :disabled="busy || readOnly" class="suggestion-form" @submit.prevent="save()">
      <h3>{{ t('semantic.extraction.reviewSuggestion') }}</h3>
      <p>{{ t('semantic.extraction.matchHelp', { name: form.subjectName }) }}</p>
      <el-form-item :label="t('semantic.w.type')"><el-select v-model="form.subjectTypeKey" @change="form.subjectId = null; form.predicateKey = ''; form.targetEntityId = null"><el-option v-for="type in definition.types" :key="type.key" :value="type.key" :label="type.label" /></el-select></el-form-item>
      <el-form-item :label="t('semantic.w.subject')"><el-select v-model="form.subjectId" clearable filterable><el-option v-for="entity in owners" :key="entity.id" :value="entity.id" :label="`${entity.displayName} · ${entity.id}`" /></el-select></el-form-item>
      <el-button text @click="$emit('register')">{{ t('semantic.extraction.registerHelp') }}</el-button>
      <el-form-item :label="t('semantic.w.predicate')"><el-select v-model="predicate"><el-option v-for="term in terms" :key="term.key" :value="term.key" :label="term.label" /></el-select></el-form-item>
      <el-form-item v-if="form.predicateKind === 'RELATION'" :label="t('semantic.w.target')"><p class="recognized-target">{{ form.targetName }}</p><el-select v-model="form.targetEntityId" clearable filterable><el-option v-for="entity in targets" :key="entity.id" :value="entity.id" :label="`${entity.displayName} · ${entity.id}`" /></el-select></el-form-item>
      <template v-else><el-form-item :label="t('semantic.w.value')"><el-input v-model="form.value" /></el-form-item><el-form-item :label="t('semantic.fixedUnit')"><el-input :model-value="form.unit ?? ''" readonly /></el-form-item></template>
      <el-form-item :label="t('semantic.w.validity')"><el-select v-model="form.validityKind" @change="form.validFrom = null; form.validTo = null"><el-option value="UNKNOWN" :label="t('semantic.w.unknown')" /><el-option value="INTERVAL" :label="t('semantic.w.interval')" /></el-select></el-form-item>
      <template v-if="form.validityKind === 'INTERVAL'"><el-form-item :label="t('semantic.w.validFrom')"><el-date-picker v-model="form.validFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item><el-form-item :label="t('semantic.w.validTo')"><el-date-picker v-model="form.validTo" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></template>
      <el-alert v-for="(diagnostic, index) in form.diagnostics" :key="index" :title="te(`semantic.extraction.errors.${diagnostic}`) ? t(`semantic.extraction.errors.${diagnostic}`) : diagnostic" type="warning" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="dirty">{{ t('semantic.extraction.saveBeforeSubmit') }}</p>
      <div v-if="!readOnly" class="actions"><el-button native-type="submit" :loading="busy">{{ t('semantic.extraction.saveReview') }}</el-button><el-button type="primary" :disabled="!canSubmit" @click="submit">{{ t('semantic.extraction.submit') }}</el-button><el-button @click="save('IGNORED')">{{ t('semantic.extraction.ignore') }}</el-button></div>
      <p v-else>{{ t(`semantic.extraction.states.${form.status}`) }}</p>
    </el-form>
  </div>
</template>
<style scoped>
.review-columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 24px; margin-top: 20px; }
.source-pane, .suggestion-form { min-width: 0; }
.recognized-target { width: 100%; margin: 0 0 6px; }
h3 { font-size: 16px; margin: 0 0 12px; } p { color: var(--mc-text-secondary); overflow-wrap: anywhere; }
.evidence-text, textarea { white-space: pre-wrap; overflow-wrap: anywhere; width: 100%; box-sizing: border-box; padding: 14px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); color: var(--mc-text-primary); max-height: 460px; overflow: auto; font: inherit; line-height: 1.8; }
textarea { min-height: 280px; margin-top: 12px; } mark { background: var(--el-color-warning-light-7); color: #3d2b0f; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; } .actions :deep(.el-button) { margin: 0; }
@media(max-width: 900px) { .review-columns { grid-template-columns: minmax(0, 1fr); } }
</style>
