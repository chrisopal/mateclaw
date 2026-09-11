<script setup lang="ts">
import { vLoading } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { sourceIncrementalApi, type FactRevisionPreview } from '../api/sourceIncrementalApi'
import type { DocumentView, SemanticEntity } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'
import { exactQuoteRange } from '../api/sourceSelectionApi'
import { statementLabel } from './statementLabel'

const props = defineProps<{ graphId: string; itemId: string; entities: SemanticEntity[]; document: DocumentView }>()
const emit = defineEmits<{ submitted: [] }>()
const { locale } = useI18n(), { begin, workspace } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const preview = ref<FactRevisionPreview | null>(null), busy = ref(false), error = ref('')
const value = ref(''), quote = ref(''), validity = ref('UNKNOWN'), from = ref<string | null>(null), to = ref<string | null>(null)
const assertion = computed(() => preview.value?.statement.assertion)
const dataProperty = computed(() => assertion.value?.kind.endsWith('DATA_PROPERTY'))
const namedClass = computed(() => assertion.value?.kind === 'CLASS_ASSERTION' && /^<[^<>\s]+>$/.test(assertion.value.classExpressionFunctionalSyntax ?? ''))
const classOptions = computed(() => props.document.axioms.filter(a => a.axiomType === 'Declaration' && a.rendering.includes('Class(')).flatMap(a => a.signatureIris))
const duplicateQuote = computed(() => {
  const text = preview.value?.observed.text ?? '', q = quote.value.trim(), first = q ? text.indexOf(q) : -1
  return first >= 0 && text.indexOf(q, first + 1) >= 0
})
const range = computed(() => duplicateQuote.value ? null : exactQuoteRange(preview.value?.observed.text ?? '', quote.value))
const newAssertion = computed(() => {
  const a = assertion.value
  if (!a?.subjectIri) return ''
  if (dataProperty.value && a.literal) {
    const suffix = a.literal.languageTag ? `@${a.literal.languageTag}` : `^^<${a.literal.datatypeIri}>`
    return `${a.kind.startsWith('NEGATIVE') ? 'Negative' : ''}DataPropertyAssertion(<${a.predicateIri}> <${a.subjectIri}> ${JSON.stringify(value.value)}${suffix})`
  }
  if (namedClass.value) return classOptions.value.includes(value.value) ? `ClassAssertion(<${value.value}> <${a.subjectIri}>)` : ''
  if (a.kind === 'CLASS_ASSERTION') return ''
  const target = props.entities.find(e => e.id === value.value)
  if (!target) return ''
  if (a.kind.endsWith('OBJECT_PROPERTY')) return `${a.kind.startsWith('NEGATIVE') ? 'Negative' : ''}ObjectPropertyAssertion(<${a.predicateIri}> <${a.subjectIri}> <${target.iri}>)`
  return `${a.kind === 'SAME_INDIVIDUAL' ? 'SameIndividual' : 'DifferentIndividuals'}(<${a.subjectIri}> <${target.iri}>)`
})
const ready = computed(() => !!range.value && !!newAssertion.value && !busy.value)
watch(() => [props.graphId, props.itemId, workspace.currentWorkspaceId], async () => {
  const run = begin(); preview.value = null; value.value = ''; quote.value = ''; error.value = ''; busy.value = true
  try {
    const result = await sourceIncrementalApi.factPreview(run.id, props.graphId, props.itemId, run.signal)
    if (!run.current()) return
    preview.value = result
    const a = result.statement.assertion
    value.value = a.literal?.lexicalValue ?? (a.classExpressionFunctionalSyntax?.match(/^<([^<>\s]+)>$/)?.[1]) ?? props.entities.find(e => e.iri === (a.objectIri ?? a.relatedIndividualIri))?.id ?? ''
    validity.value = result.statement.validityKind; from.value = result.statement.validFrom; to.value = result.statement.validTo
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}, { immediate: true })
async function submit() {
  if (!ready.value || !preview.value) return
  const run = begin(); busy.value = true; error.value = ''
  try {
    await sourceIncrementalApi.reviseFact(run.id, props.graphId, props.itemId, {
      expectedObservedDigest: preview.value.item.newDigest, expectedRevision: preview.value.statement.revision,
      assertionText: newAssertion.value, validityKind: validity.value,
      validFrom: validity.value === 'UNKNOWN' ? null : from.value, validTo: validity.value === 'UNKNOWN' ? null : to.value,
      ...range.value!,
    }, run.signal)
    if (run.current()) emit('submitted')
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>

<template>
  <section class="fact-revision" v-loading="busy">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="preview">
      <h4>{{ tr('原有结论', 'Original fact') }}</h4>
      <p>{{ statementLabel(preview.statement, entities, locale) }}</p>
      <div class="source-comparison">
        <details><summary>{{ tr('查看原资料', 'Original source') }}</summary><pre>{{ preview.original.text }}</pre></details>
        <section><h4>{{ tr('更新后的资料', 'Updated source') }}</h4><pre>{{ preview.observed.text }}</pre></section>
      </div>
      <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
        <el-form-item v-if="dataProperty" :label="tr('修订后的属性值', 'Revised value')"><el-input v-model="value" /></el-form-item>
        <el-form-item v-else-if="namedClass" :label="tr('修订后的类型', 'Revised type')"><el-select v-model="value"><el-option v-for="iri in classOptions" :key="iri" :value="iri" :label="iri.split(/[#/:]/).at(-1) || iri" /></el-select></el-form-item>
        <el-form-item v-else-if="assertion?.kind !== 'CLASS_ASSERTION'" :label="tr('修订后的关联对象', 'Revised related object')"><el-select v-model="value" filterable><el-option v-for="e in entities" :key="e.id" :value="e.id" :label="`${e.displayName} · ${e.id}`" /></el-select></el-form-item>
        <p v-else>{{ tr('此结论包含复合类型，请通过高级编辑修订。', 'Use the advanced editor for this complex type.') }}</p>
        <el-form-item :label="tr('支持修订的原文', 'Supporting excerpt')"><el-input v-model="quote" type="textarea" :rows="3" /><p v-if="duplicateQuote" role="alert">{{ tr('原文出现多次，请补充前后文。', 'Include context to identify a unique occurrence.') }}</p></el-form-item>
        <el-form-item :label="tr('适用时间', 'Validity')"><el-select v-model="validity"><el-option value="UNKNOWN" :label="tr('尚不明确', 'Unknown')" /><el-option value="INTERVAL" :label="tr('指定时间范围', 'Time interval')" /></el-select></el-form-item>
        <template v-if="validity === 'INTERVAL'"><el-form-item :label="tr('开始时间', 'From')"><el-date-picker v-model="from" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item><el-form-item :label="tr('结束时间', 'Until')"><el-date-picker v-model="to" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></template>
        <p>{{ tr('提交后由负责人审核，旧结论与历史记录会保留。', 'A reviewer must approve this change. History remains available.') }}</p>
        <el-button type="primary" native-type="submit" :disabled="!ready">{{ tr('提交修订申请', 'Submit revision') }}</el-button>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.fact-revision { min-width: 0; }.fact-revision h4 { margin: 12px 0 8px; font-weight: 600; }.fact-revision p { margin: 8px 0; line-height: 1.6; }.fact-revision pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow: auto; border: 1px solid var(--mc-border); padding: 12px; }.source-comparison { margin-bottom: 16px; }.fact-revision :deep(.el-select), .fact-revision :deep(.el-date-editor) { width: 100%; }
</style>
