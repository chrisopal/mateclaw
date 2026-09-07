<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import type { Definition, SemanticEntity } from '../api/types'
import type { Snapshot, SnapshotText, Statement } from '../api/workbenchTypes'
import { sourceApi } from '../api/sourceApi'
import { submitEvidenceCandidate } from './submitEvidenceCandidate'
import { useSemanticScope } from '../shared/useSemanticScope'
import { candidateContent } from './candidateContent'
import { evidenceSelection } from './evidenceSelection'
const props = defineProps<{ graphId: string; definition: Definition; entities: SemanticEntity[]; snapshots: Snapshot[]; target?: Statement | null }>()
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n(), { begin, workspace } = useSemanticScope()
const subject = ref(''), predicate = ref(''), value = ref(''), targetEntity = ref(''), snapshotId = ref(''), snapshot = ref<SnapshotText | null>(null)
const range = ref<ReturnType<typeof evidenceSelection> | null>(null), busy = ref(false), error = ref(''), validFrom = ref(''), validTo = ref(''), validityKind = ref('UNKNOWN')
const baseline = ref('')
const formSnapshot = () => JSON.stringify([subject.value, predicate.value, value.value, targetEntity.value, snapshotId.value, range.value, validityKind.value, validFrom.value, validTo.value])
const dirty = computed(() => formSnapshot() !== baseline.value)
async function confirmLeave() {
  if (!dirty.value && !busy.value) return true
  try { await ElMessageBox.confirm(t('semantic.leaveWarning'), t('semantic.unsaved'), { confirmButtonText: t('semantic.leave'), cancelButtonText: t('semantic.stay'), type: 'warning' }); return true }
  catch { return false }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(confirmLeave)
const unregister = workspace.registerBeforeSwitch(confirmLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { unregister(); window.removeEventListener('beforeunload', beforeUnload) })
const ownerType = computed(() => props.entities.find(e => e.id === subject.value)?.typeKey)
const predicates = computed(() => [...props.definition.properties.filter(p => p.ownerTypeKey === ownerType.value).map(p => ({ ...p, kind: 'PROPERTY', targetType: '' })), ...props.definition.relations.filter(p => p.sourceTypeKey === ownerType.value).map(p => ({ ...p, kind: 'RELATION', valueType: '', fixedUnit: null, targetType: p.targetTypeKey }))])
const selected = computed(() => predicates.value.find(p => `${p.kind}:${p.key}` === predicate.value))
const targets = computed(() => props.entities.filter(e => e.typeKey === selected.value?.targetType))
watch(predicate, () => { value.value = ''; targetEntity.value = '' }, { flush: 'sync' })
watch(subject, () => { predicate.value = ''; targetEntity.value = '' }, { flush: 'sync' })
function localDateTime(value: string | null | undefined) {
  if (!value) return ''
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
}
watch(() => props.target, target => { subject.value = target?.subjectId ?? ''; predicate.value = target ? `${target.predicateKind}:${target.predicateKey}` : ''; validityKind.value = target?.validityKind ?? 'UNKNOWN'; value.value = target?.valueType === 'INSTANT' ? localDateTime(target.value) : target?.value ?? ''; targetEntity.value = target?.targetEntityId ?? ''; validFrom.value = localDateTime(target?.validFrom); validTo.value = localDateTime(target?.validTo); snapshotId.value = ''; range.value = null; baseline.value = formSnapshot() }, { immediate: true })
watch(snapshotId, async id => {
  const run = begin(); snapshot.value = null; range.value = null; error.value = ''; busy.value = false
  if (!id) return
  busy.value = true
  try { const result = await sourceApi.text(run.id, props.graphId, id, run.signal); if (run.current()) snapshot.value = result }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
})
function select(event: Event) {
  const el = event.target as HTMLTextAreaElement
  range.value = null
  try { range.value = evidenceSelection(snapshot.value!.text, el.selectionStart, el.selectionEnd) } catch { /* Empty selections are not evidence. */ }
}
async function submit() {
  if (busy.value || !selected.value || !range.value || !snapshot.value || (selected.value.kind === 'RELATION' ? !targetEntity.value : !value.value.trim())) return
  const run = begin(), p = selected.value; busy.value = true; error.value = ''
  try {
    const content = candidateContent({ subjectId: subject.value, predicate: p, value: value.value, targetEntityId: targetEntity.value, validityKind: validityKind.value, validFrom: validFrom.value, validTo: validTo.value })
    const saved = await submitEvidenceCandidate(run, props.graphId, snapshot.value.id, range.value, content, props.target)
    if (saved) { range.value = null; baseline.value = formSnapshot(); emit('saved') }
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><el-form label-position="top" :disabled="busy" @submit.prevent="submit">
<el-alert v-if="target" :title="t('semantic.w.changeBase', { revision: target.revision, id: target.id })" :closable="false" />
<el-form-item :label="t('semantic.w.subject')"><el-select v-model="subject" filterable><el-option v-for="entity in entities" :key="entity.id" :value="entity.id" :label="entity.displayName" /></el-select></el-form-item>
<el-form-item :label="t('semantic.w.predicate')"><el-select v-model="predicate"><el-option v-for="p in predicates" :key="p.kind + p.key" :value="`${p.kind}:${p.key}`" :label="`${p.label} · ${p.multiplicity}`" /></el-select></el-form-item>
<el-form-item v-if="selected?.kind === 'RELATION'" :label="t('semantic.w.target')"><el-select v-model="targetEntity"><el-option v-for="entity in targets" :key="entity.id" :value="entity.id" :label="entity.displayName" /></el-select></el-form-item>
<el-form-item v-else-if="selected" :label="`${t('semantic.w.value')} · ${selected.valueType} ${selected.fixedUnit ?? ''}`"><el-select v-if="selected.valueType === 'BOOLEAN'" v-model="value"><el-option value="true" label="true" /><el-option value="false" label="false" /></el-select><el-input v-else v-model="value" :type="selected.valueType === 'DATE' ? 'date' : selected.valueType === 'INSTANT' ? 'datetime-local' : 'text'" /></el-form-item>
<el-form-item :label="t('semantic.w.validity')"><el-select v-model="validityKind"><el-option value="INTERVAL" :label="t('semantic.w.interval')" /><el-option value="UNKNOWN" :label="t('semantic.w.unknown')" /></el-select></el-form-item>
<el-form-item v-if="validityKind === 'INTERVAL'" :label="t('semantic.w.validFrom')"><el-input v-model="validFrom" type="datetime-local" /><el-input v-model="validTo" type="datetime-local" :aria-label="t('semantic.w.validTo')" /></el-form-item>
<el-form-item :label="t('semantic.w.snapshot')"><el-select v-model="snapshotId"><el-option v-for="s in snapshots" :key="s.id" :value="s.id" :label="`${s.sourceTitle} · v${s.captureVersion}`" /></el-select></el-form-item>
<template v-if="snapshot"><p>{{ t('semantic.w.selectHelp') }}</p><textarea class="snapshot-text" readonly :value="snapshot.text" :aria-label="t('semantic.w.snapshotText')" @select="select" @mouseup="select" @keyup="select" /><p v-if="range">[{{ range.startCodePoint }}, {{ range.endCodePoint }}) {{ range.exactQuote }}</p><small>{{ snapshot.textDigest }}</small></template>
<el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button native-type="submit" type="primary" :loading="busy" :disabled="!range || !selected || (selected.kind === 'RELATION' ? !targetEntity : !value.trim())">{{ t(target ? 'semantic.w.proposeChange' : 'semantic.w.propose') }}</el-button>
</el-form></template>
<style scoped>.snapshot-text { box-sizing: border-box; width: 100%; min-height: 240px; padding: 12px; color: var(--mc-text-primary); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); white-space: pre-wrap; } small { overflow-wrap: anywhere; display: block; margin-bottom: 12px; }</style>
