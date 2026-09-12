<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelingTaskApi } from '../api/modelingTaskApi'
import { sourceApi } from '../api/sourceApi'
import { statementApi } from '../api/statementApi'
import type { SemanticEntity, DocumentView } from '../api/types'
import type { Snapshot } from '../api/workbenchTypes'
import { useSemanticScope } from '../shared/useSemanticScope'
import { projectOntology } from '../ontology/ontologyProjection'
import { exactQuoteRange } from '../api/sourceSelectionApi'
const props = defineProps<{ graphId: string; ontologyId: string; entities: SemanticEntity[]; snapshots: Snapshot[]; document: DocumentView }>()
const emit = defineEmits<{ submitted: [] }>()
const { locale } = useI18n(), { begin } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const samples = ref<{ key: string; content: string; label: string; summary: string }[]>([]), selected = ref(''), subject = ref(''), snapshot = ref(''), text = ref(''), quote = ref(''), error = ref(''), busy = ref(false)
const kind = ref<'TYPE' | 'RELATION' | 'ATTRIBUTE' | 'SAME' | 'DIFFERENT'>('TYPE'), target = ref(''), type = ref('')
const predicate = ref(''), literal = ref('')
const terms = computed(() => props.document.axioms.filter(a => a.axiomType === 'Declaration').filter(a => a.rendering.includes(kind.value === 'TYPE' ? 'Class(' : kind.value === 'ATTRIBUTE' ? 'DataProperty(' : 'ObjectProperty(')).flatMap(a => a.signatureIris))
const termLabels = computed(() => new Map(projectOntology(props.document.axioms, locale.value).nodes.map(node => [node.iri, node.label])))
const termLabel = (iri: string) => termLabels.value.get(iri) || iri.split(/[#/:]/).at(-1) || iri
const entity = computed(() => props.entities.find(e => e.id === subject.value))
const other = computed(() => props.entities.find(e => e.id === target.value))
const ambiguousQuote = computed(() => {
  const value = quote.value.trim()
  const first = value ? text.value.indexOf(value) : -1
  return first >= 0 && text.value.indexOf(value, first + 1) >= 0
})
const range = computed(() => ambiguousQuote.value ? null : exactQuoteRange(text.value, quote.value))
const sample = computed(() => samples.value.find(s => s.key === selected.value))
const ready = computed(() => !!selected.value && !!entity.value && !!snapshot.value && !!range.value && (kind.value === 'TYPE' ? terms.value.includes(type.value) : kind.value === 'ATTRIBUTE' ? terms.value.includes(predicate.value) && !!literal.value.trim() : !!other.value && (kind.value === 'RELATION' ? terms.value.includes(predicate.value) : other.value.id !== entity.value.id)))
let operation = crypto.randomUUID()
let proofId = ''
watch(() => [props.graphId, props.ontologyId], async () => {
  const run = begin(); samples.value = []; selected.value = ''; error.value = ''; busy.value = true
  try { const tasks = await modelingTaskApi.list(run.id, props.ontologyId, run.signal); if (run.current()) samples.value = tasks.flatMap(task => task.proposals.filter(p => p.status === 'ACCEPTED').flatMap(p => p.input.samples.map((sample, i) => ({ key: `${task.id}/${p.id}/${i}`, content: typeof sample === 'string' ? sample : JSON.stringify(sample, null, 2), label: typeof sample === 'string' ? sample : String((sample as Record<string, unknown>)?.name ?? tr('样例', 'Sample')), summary: typeof sample === 'string' ? sample : String((sample as Record<string, unknown>)?.description ?? '') })))) }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}, { immediate: true })
watch(snapshot, async id => {
  const run = begin(); text.value = ''; quote.value = ''; if (!id) return
  busy.value = true
  try { const result = await sourceApi.text(run.id, props.graphId, id, run.signal); if (run.current()) text.value = result.text }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
})
watch([selected, subject, snapshot, quote, kind, target, type, predicate, literal], () => { proofId = ''; operation = `sample:${selected.value.split('/').slice(1).join(':')}:${crypto.randomUUID()}` })
watch(subject, () => { type.value = ''; target.value = '' })
async function submit() {
  if (!ready.value || busy.value) return
  const run = begin(); busy.value = true; error.value = ''
  try {
    if (!proofId) { const proof = await sourceApi.evidence(run.id, props.graphId, snapshot.value, range.value!, run.signal); if (!run.current()) return; proofId = proof.id }
    if (!run.current()) return
    const assertionText = kind.value === 'TYPE' ? `ClassAssertion(<${type.value}> <${entity.value!.iri}>)` : kind.value === 'ATTRIBUTE' ? `DataPropertyAssertion(<${predicate.value}> <${entity.value!.iri}> ${JSON.stringify(literal.value)}^^<http://www.w3.org/2001/XMLSchema#string>)` : kind.value === 'RELATION' ? `ObjectPropertyAssertion(<${predicate.value}> <${entity.value!.iri}> <${other.value!.iri}>)` : `${kind.value === 'SAME' ? 'SameIndividual' : 'DifferentIndividuals'}(<${entity.value!.iri}> <${other.value!.iri}>)`
    await statementApi.propose(run.id, props.graphId, { operationId: operation, subjectId: subject.value, assertionText, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: [proofId] }, run.signal)
    if (run.current()) { selected.value = ''; emit('submitted') }
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><section class="sample-review"><h3>{{ tr('建模样例核对', 'Review modeling samples') }}</h3><el-alert v-if="error" :title="error" type="error" :closable="false" /><p v-if="!busy && !samples.length">{{ tr('暂无已接受建模建议中的样例。', 'No samples from accepted modeling proposals.') }}</p><el-form v-else :disabled="busy" label-position="top" @submit.prevent="submit"><el-form-item :label="tr('建模样例', 'Modeling sample')"><el-select v-model="selected"><el-option v-for="(sample, index) in samples" :key="sample.key" :value="sample.key" :label="`${index + 1} · ${sample.label.slice(0, 60)}`" /></el-select></el-form-item><template v-if="selected"><p>{{ sample?.summary }}</p><details><summary>{{ tr('查看原始样例', 'Original sample') }}</summary><pre>{{ sample?.content }}</pre></details><p>{{ tr('样例只作参考，请按已发布模型核对对象及原文。', 'Samples are references. Verify objects and source against the published model.') }}</p><el-form-item :label="tr('业务对象', 'Business object')"><el-select v-model="subject" filterable><el-option v-for="e in entities" :key="e.id" :value="e.id" :label="`${e.displayName} · ${e.id}`" /></el-select></el-form-item><el-form-item :label="tr('核对内容', 'Claim')"><el-select v-model="kind"><el-option value="TYPE" :label="tr('对象类型', 'Object type')" /><el-option value="RELATION" :label="tr('对象关系', 'Object relation')" /><el-option value="ATTRIBUTE" :label="tr('文本属性', 'Text attribute')" /><el-option value="SAME" :label="tr('是同一对象', 'Same object')" /><el-option value="DIFFERENT" :label="tr('是不同对象', 'Different objects')" /></el-select></el-form-item><el-form-item v-if="kind === 'TYPE'" :label="tr('类型', 'Type')"><el-select v-model="type"><el-option v-for="iri in terms" :key="iri" :value="iri" :label="termLabel(iri)" /></el-select></el-form-item><el-form-item v-if="kind !== 'TYPE' && kind !== 'ATTRIBUTE'" :label="tr('另一对象', 'Other object')"><el-select v-model="target" filterable><el-option v-for="e in entities.filter(e => e.id !== subject)" :key="e.id" :value="e.id" :label="`${e.displayName} · ${e.id}`" /></el-select></el-form-item><template v-if="kind === 'RELATION' || kind === 'ATTRIBUTE'"><el-form-item :label="tr('关系或属性', 'Relation or attribute')"><el-select v-model="predicate"><el-option v-for="iri in terms" :key="iri" :value="iri" :label="termLabel(iri)" /></el-select></el-form-item><el-form-item v-if="kind === 'ATTRIBUTE'" :label="tr('属性值', 'Value')"><el-input v-model="literal" /></el-form-item></template><el-form-item :label="tr('来源快照', 'Source snapshot')"><el-select v-model="snapshot"><el-option v-for="s in snapshots" :key="s.id" :value="s.id" :label="`${s.sourceTitle} · ${s.captureVersion}`" /></el-select></el-form-item><pre v-if="text">{{ text }}</pre><el-form-item :label="tr('支持这条结论的原文', 'Exact supporting quote')"><el-input v-model="quote" type="textarea" /><p v-if="ambiguousQuote" role="alert">{{ tr('这段原文出现多次，请补充前后文以定位具体出处。', 'This quote occurs more than once. Include surrounding text to identify its location.') }}</p></el-form-item><p>{{ tr('未明确的时间保留为未知；审核通过前不会用于问答。', 'Unspecified time stays unknown. Review is required before use.') }}</p><el-button native-type="submit" type="primary" :disabled="!ready" :loading="busy">{{ tr('提交审核', 'Submit for review') }}</el-button></template></el-form></section></template>
<style scoped>.sample-review{max-width:900px;min-width:0}.sample-review pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:220px;overflow:auto;padding:12px;background:var(--mc-bg-elevated);border:1px solid var(--mc-border)}.sample-review p{color:var(--mc-text-secondary);font-size:13px}.el-select{width:100%}</style>
