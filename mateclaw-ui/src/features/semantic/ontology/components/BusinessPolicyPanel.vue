<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { ontologyApi } from '../../api/ontologyApi'
import type { BusinessPolicyCheck, BusinessPolicyRule, BusinessPolicySet, Draft } from '../../api/types'
import type { DisplayProjection } from '../standardProjection'
import { displayModel } from '../standardProjection'
import { useSemanticScope } from '../../shared/useSemanticScope'

const props = defineProps<{
  ontologyId: string
  draftVersion: number
  policy: BusinessPolicySet
  projection?: DisplayProjection | null
  editable: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ saved: [draft: Draft]; 'pending-change': [pending: boolean]; 'dirty-change': [dirty: boolean] }>()

const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const { begin } = useSemanticScope()
const sampleScope = useSemanticScope()
const rules = ref<BusinessPolicyRule[]>([])
const rulesBaseline = ref('')
const rulesDirty = ref(false)
const policyError = ref('')
const saving = ref(false)
const pendingOperation = ref<{ body: { expectedDraftVersion: number; operationId: string; rules: BusinessPolicyRule[] } } | null>(null)
const ruleDialogOpen = ref(false)
const editingIndex = ref<number | null>(null)
const ruleForm = reactive({ predicateIri: '', required: false, singleValue: false, unit: '', allowedValues: '' })
const selectedClassIri = ref('')
const sampleClassIri = ref('')
const sampleValues = reactive<Record<string, string>>({})
const sampleUnits = reactive<Record<string, string>>({})
const sampleMode = ref<'complete' | 'partial'>('complete')
const sampleBusy = ref(false)
const sampleError = ref('')
const sampleResult = ref<BusinessPolicyCheck | null>(null)
const sampleGeneration = ref(0)

function cloneRules(value: BusinessPolicyRule[]) {
  return value.map(rule => ({ ...rule, allowedLexicalValues: [...(rule.allowedLexicalValues ?? [])], unit: rule.unit ?? null, singleValue: !!rule.singleValue }))
}
function resetRules(value: BusinessPolicySet) {
  const next = cloneRules(value.rules)
  rules.value = next
  rulesBaseline.value = JSON.stringify(next)
  rulesDirty.value = false
  emit('dirty-change', false)
  if (!selectedClassIri.value || !classOptions.value.some(item => item.iri === selectedClassIri.value)) selectedClassIri.value = classOptions.value[0]?.iri ?? ''
}

const model = computed(() => displayModel(props.projection, locale.value))
const classOptions = computed(() => model.value.nodes.filter(node => node.kind === 'class'))
const nodeByIri = computed(() => new Map(model.value.nodes.map(node => [node.iri, node])))
const rangeByProperty = computed(() => {
  const result = new Map<string, string>()
  for (const edge of model.value.edges.filter(item => item.kind === 'range')) {
    const property = nodeByIri.value.get(edge.source.replace(/^dataProperty:/, '')) ?? model.value.nodes.find(node => node.id === edge.source)
    const range = nodeByIri.value.get(edge.target.replace(/^datatype:/, '')) ?? model.value.nodes.find(node => node.id === edge.target)
    if (property?.kind === 'dataProperty' && range) result.set(property.iri, range.iri)
  }
  return result
})
const domainProperties = computed(() => {
  const result = new Map<string, string[]>()
  for (const edge of model.value.edges.filter(item => item.kind === 'domain')) {
    const domain = model.value.nodes.find(node => node.id === edge.source)
    const property = model.value.nodes.find(node => node.id === edge.target)
    if (domain?.kind !== 'class' || property?.kind !== 'dataProperty') continue
    result.set(domain.iri, [...(result.get(domain.iri) ?? []), property.iri])
  }
  return result
})
function label(iri: string) { return nodeByIri.value.get(iri)?.label ?? iri.split(/[#/:]/).pop() ?? iri }
function datatypeLabel(iri: string | undefined) {
  if (!iri) return tr('未声明', 'Not declared')
  const short = iri.startsWith('http://www.w3.org/2001/XMLSchema#') ? iri.slice(iri.indexOf('#') + 1) : ''
  const names: Record<string, [string, string]> = { string: ['文本', 'Text'], decimal: ['小数', 'Decimal'], integer: ['整数', 'Integer'], boolean: ['是／否', 'Yes / no'], dateTime: ['日期时间', 'Date and time'], date: ['日期', 'Date'], time: ['时间', 'Time'] }
  return short && names[short] ? tr(...names[short]) : label(iri)
}
function ruleForClass() { return rules.value.filter(rule => rule.classIri === selectedClassIri.value) }
const visibleRules = computed(() => ruleForClass())
const propertyOptions = computed(() => {
  const values = [...(domainProperties.value.get(selectedClassIri.value) ?? [])]
  for (const rule of ruleForClass()) if (!values.includes(rule.predicateIri)) values.push(rule.predicateIri)
  return values.map(iri => ({ iri, label: label(iri), datatype: rangeByProperty.value.get(iri) }))
})
const samplePropertyOptions = computed(() => {
  const values = new Set(domainProperties.value.get(sampleClassIri.value) ?? [])
  for (const rule of rules.value) if (rule.classIri === sampleClassIri.value) values.add(rule.predicateIri)
  return [...values].map(iri => ({ iri, label: label(iri), datatype: rangeByProperty.value.get(iri) }))
})
const selectedClassLabel = computed(() => label(selectedClassIri.value))

watch(() => props.policy, value => resetRules(value), { immediate: true, deep: true })
watch(classOptions, value => {
  if (!selectedClassIri.value || !value.some(item => item.iri === selectedClassIri.value)) selectedClassIri.value = value[0]?.iri ?? ''
}, { immediate: true })
watch(() => [props.ontologyId, props.draftVersion], () => {
  invalidateSample()
  clearSampleFields()
})

watch(selectedClassIri, value => {
  if (sampleClassIri.value !== value) sampleClassIri.value = value
  invalidateSample()
}, { immediate: true })
watch(sampleClassIri, () => { invalidateSample(); clearSampleFields() })
watch(sampleMode, invalidateSample)
watch(sampleValues, invalidateSample, { deep: true })
watch(sampleUnits, invalidateSample, { deep: true })
watch(rules, value => {
  const dirty = JSON.stringify(value) !== rulesBaseline.value
  rulesDirty.value = dirty
  invalidateSample()
  emit('dirty-change', dirty || ruleDialogOpen.value)
}, { deep: true })

watch(ruleDialogOpen, open => emit('dirty-change', rulesDirty.value || open))
function discardRules() { resetRules(props.policy); policyError.value = ''; invalidateSample() }

function clearSampleFields() {
  for (const key of Object.keys(sampleValues)) delete sampleValues[key]
  for (const key of Object.keys(sampleUnits)) delete sampleUnits[key]
}
function invalidateSample() { sampleGeneration.value++; sampleResult.value = null; sampleError.value = '' }
function resetRuleForm() {
  ruleForm.predicateIri = ''
  ruleForm.required = false
  ruleForm.singleValue = false
  ruleForm.unit = ''
  ruleForm.allowedValues = ''
}
function openRule(index: number | null = null) {
  if (!props.editable || props.disabled || saving.value || pendingOperation.value) return
  editingIndex.value = index
  resetRuleForm()
  if (index !== null) {
    const rule = rules.value[index]
    if (rule) {
      ruleForm.predicateIri = rule.predicateIri
      ruleForm.required = rule.required
      ruleForm.singleValue = !!rule.singleValue
      ruleForm.unit = rule.unit ?? ''
      ruleForm.allowedValues = rule.allowedLexicalValues.join('\n')
    }
  }
  ruleDialogOpen.value = true
}
function closeRule() { if (!saving.value) ruleDialogOpen.value = false }
function submitRule() {
  const predicateIri = ruleForm.predicateIri.trim()
  if (!predicateIri || !selectedClassIri.value) { policyError.value = tr('请选择业务对象和数据属性。', 'Choose a business class and data property.'); return }
  if (!rangeByProperty.value.get(predicateIri)) { policyError.value = tr('该属性没有可用的数据类型和值域。', 'This property has no usable datatype range.'); return }
  const duplicate = rules.value.findIndex((rule, index) => index !== editingIndex.value && rule.classIri === selectedClassIri.value && rule.predicateIri === predicateIri)
  if (duplicate >= 0) { policyError.value = tr('同一业务对象不能重复配置这个属性。', 'This data property already has a rule for the class.'); return }
  const allowedLexicalValues = [...new Set(ruleForm.allowedValues.split(/\r?\n/).map(value => value.trim()).filter(Boolean))]
  const next: BusinessPolicyRule = { classIri: selectedClassIri.value, predicateIri, required: ruleForm.required, singleValue: ruleForm.singleValue, unit: ruleForm.unit.trim() || null, allowedLexicalValues }
  if (editingIndex.value === null) rules.value.push(next); else rules.value.splice(editingIndex.value, 1, next)
  policyError.value = ''
  ruleDialogOpen.value = false
}
async function removeRule(index: number) {
  if (!props.editable || props.disabled || saving.value || pendingOperation.value) return
  const rule = rules.value[index]
  if (!rule) return
  try {
    await ElMessageBox.confirm(tr(`删除“${label(rule.predicateIri)}”的业务规则？`, `Delete the business rule for “${label(rule.predicateIri)}”?`), tr('删除业务规则', 'Delete business rule'), { type: 'warning' })
    rules.value.splice(index, 1)
  } catch { /* cancelled */ }
}
function ambiguous(error: unknown) {
  const value = error as { status?: number; code?: string }
  return value.status === 0 || value.status === 408 || (typeof value.status === 'number' && value.status >= 500) || value.code === 'REQUEST_FAILED'
}
async function savePolicy() {
  if (!props.editable || props.disabled || saving.value || !props.ontologyId || props.draftVersion < 1) return
  const scope = begin()
  const body = pendingOperation.value?.body ?? { expectedDraftVersion: props.draftVersion, operationId: crypto.randomUUID(), rules: cloneRules(rules.value) }
  pendingOperation.value = { body }
  saving.value = true; policyError.value = ''; emit('pending-change', true)
  try {
    const result = await ontologyApi.saveBusinessPolicy(scope.id, props.ontologyId, body, scope.signal)
    if (scope.current()) {
      pendingOperation.value = null
      rulesBaseline.value = JSON.stringify(rules.value)
      rulesDirty.value = false
      emit('dirty-change', false)
      emit('saved', result)
    }
  } catch (error) {
    if (scope.current()) {
      if (!ambiguous(error)) pendingOperation.value = null
      policyError.value = (error as { message?: string }).message || tr('业务规则保存失败，请检查后重试。', 'Business rules could not be saved. Check the error and retry.')
    }
  } finally {
    if (scope.current()) { saving.value = false; emit('pending-change', !!pendingOperation.value) }
  }
}
async function retrySave() {
  if (!pendingOperation.value || saving.value) return
  await savePolicy()
}
function valuesFor(predicateIri: string) {
  return (sampleValues[predicateIri] ?? '').split(/\r?\n/).map(value => value.trim()).filter(Boolean).map(lexicalValue => ({ lexicalValue, datatypeIri: rangeByProperty.value.get(predicateIri) ?? '', unit: sampleUnits[predicateIri]?.trim() || null }))
}
async function checkSample() {
  if (!props.editable || props.disabled || rulesDirty.value || pendingOperation.value || sampleBusy.value || !sampleClassIri.value || props.draftVersion < 1) return
  const scope = sampleScope.begin(); const generation = sampleGeneration.value; sampleBusy.value = true; sampleError.value = ''; sampleResult.value = null
  const properties: Record<string, ReturnType<typeof valuesFor>> = {}
  for (const property of samplePropertyOptions.value) {
    const values = valuesFor(property.iri)
    if (values.length) properties[property.iri] = values
  }
  try {
    const result = await ontologyApi.checkBusinessPolicySample(scope.id, props.ontologyId, { expectedDraftVersion: props.draftVersion, classIri: sampleClassIri.value, completeSubmission: sampleMode.value === 'complete', properties }, scope.signal)
    if (scope.current() && generation === sampleGeneration.value && result.draftVersion === props.draftVersion) sampleResult.value = result
  } catch (error) { if (scope.current()) sampleError.value = (error as { message?: string }).message || tr('样例检查失败，请重试。', 'Sample check failed. Try again.') } finally { if (scope.current()) sampleBusy.value = false }
}
function violationPath(path: string) {
  for (const property of samplePropertyOptions.value) if (path.includes(property.iri)) return path.replaceAll(property.iri, label(property.iri))
  return path
}
function violationMessage(violation: BusinessPolicyCheck['violations'][number]) {
  const field = violationPath(violation.path)
  const messages: Record<string, [string, string]> = {
    BUSINESS_REQUIRED: ['缺少必填属性', 'A required property is missing'],
    BUSINESS_UNIT_MISMATCH: ['单位不匹配', 'The unit does not match the rule'],
    BUSINESS_VALUE_NOT_ALLOWED: ['不在允许值范围内', 'The value is not allowed'],
    BUSINESS_SINGLE_VALUE: ['只允许一个不同值', 'Only one distinct value is allowed'],
  }
  const [zh, en] = messages[violation.code] ?? [violation.message, violation.message]
  return `${field}：${tr(zh, en)}`
}
</script>

<template>
  <section class="business-policy-panel" aria-labelledby="business-policy-title">
    <header class="business-policy-heading">
      <div><h2 id="business-policy-title">{{ tr('业务规则', 'Business rules') }}</h2><p class="semantic-muted">{{ tr('为属性设置填写要求，并检查样例。', 'Set property requirements and check a sample.') }}</p></div>
    </header>
    <el-alert v-if="pendingOperation" type="warning" :closable="false" :title="tr('保存结果尚未确认，其他编辑和导航已锁定。请恢复本次保存。', 'Save result is not confirmed. Other edits and navigation are locked until this save is recovered.')"><el-button size="small" :loading="saving" @click="retrySave">{{ tr('恢复保存', 'Recover save') }}</el-button></el-alert>
    <el-alert v-if="policyError" type="error" :closable="false" :title="policyError" />
    <div class="business-policy-layout">
      <div class="business-policy-rules">
        <div class="policy-section-heading"><div><h3>{{ tr('属性规则', 'Property rules') }}</h3></div><el-button type="primary" :disabled="!editable || disabled || saving || !!pendingOperation || !selectedClassIri" @click="openRule()">＋ {{ tr('添加规则', 'Add rule') }}</el-button></div>
        <el-form label-position="top"><el-form-item :label="tr('业务对象', 'Business class')"><el-select v-model="selectedClassIri" filterable :disabled="disabled || saving || !!pendingOperation" :placeholder="tr('选择业务对象', 'Choose a business class')"><el-option v-for="item in classOptions" :key="item.iri" :value="item.iri" :label="item.label" /></el-select></el-form-item></el-form>
        <div v-if="!classOptions.length" class="policy-empty">{{ tr('当前模型没有可配置的业务对象。', 'The current model has no configurable business classes.') }}</div>
        <div v-else-if="!visibleRules.length" class="policy-empty">{{ tr(`“${selectedClassLabel}”还没有业务规则。`, `“${selectedClassLabel}” has no business rules yet.`) }}</div>
        <div v-else class="policy-rule-list">
          <div v-for="rule in visibleRules" :key="`${rule.classIri}:${rule.predicateIri}`" class="policy-rule-row">
            <div class="policy-rule-main"><strong>{{ label(rule.predicateIri) }}</strong><span class="policy-rule-meta">{{ datatypeLabel(rangeByProperty.get(rule.predicateIri)) }}<template v-if="rule.unit"> · {{ tr('单位', 'Unit') }} {{ rule.unit }}</template><template v-if="rule.allowedLexicalValues.length"> · {{ tr('可选值', 'Allowed values') }} {{ rule.allowedLexicalValues.length }}</template></span></div>
            <div class="policy-rule-flags"><span v-if="rule.required">{{ tr('必填', 'Required') }}</span><span v-if="rule.singleValue">{{ tr('单值', 'Single value') }}</span></div>
            <div class="policy-row-actions"><el-button link :disabled="disabled || saving || !!pendingOperation" @click="openRule(rules.findIndex(candidate => candidate === rule))">{{ tr('编辑', 'Edit') }}</el-button><el-button link type="danger" :disabled="disabled || saving || !!pendingOperation" @click="removeRule(rules.findIndex(candidate => candidate === rule))">{{ tr('删除', 'Delete') }}</el-button></div>
          </div>
        </div>
        <div class="policy-save-actions"><el-button v-if="rulesDirty" :disabled="saving || !!pendingOperation" @click="discardRules">{{ tr('放弃修改', 'Discard changes') }}</el-button><el-button type="primary" :loading="saving" :disabled="!editable || disabled || !!pendingOperation || !props.ontologyId" @click="savePolicy">{{ tr('保存业务规则', 'Save business rules') }}</el-button></div>
      </div>
      <div class="business-policy-sample">
        <div class="policy-section-heading"><div><h3>{{ tr('样例检查', 'Sample check') }}</h3></div></div>
        <el-form label-position="top"><el-form-item :label="tr('业务对象', 'Business class')"><el-select v-model="sampleClassIri" filterable :disabled="disabled || sampleBusy || !!pendingOperation" :placeholder="tr('选择业务对象', 'Choose a business class')"><el-option v-for="item in classOptions" :key="item.iri" :value="item.iri" :label="item.label" /></el-select></el-form-item><el-form-item :label="tr('提交模式', 'Submission mode')"><el-radio-group v-model="sampleMode" :disabled="disabled || sampleBusy || !!pendingOperation"><el-radio-button value="complete">{{ tr('完整提交', 'Complete') }}</el-radio-button><el-radio-button value="partial">{{ tr('部分提交', 'Partial') }}</el-radio-button></el-radio-group></el-form-item>
          <div v-for="property in samplePropertyOptions" :key="property.iri" class="sample-property-field"><label :for="`sample-${property.iri}`"><strong>{{ property.label }}</strong><span>{{ datatypeLabel(property.datatype) }}</span></label><el-input :id="`sample-${property.iri}`" v-model="sampleValues[property.iri]" type="textarea" :rows="2" :disabled="disabled || sampleBusy || !!pendingOperation" :placeholder="tr('每行一个值，可留空', 'One value per line; optional')"/><el-input v-model="sampleUnits[property.iri]" :disabled="disabled || sampleBusy || !!pendingOperation" :placeholder="tr('单位（可选）', 'Unit (optional)')"/></div>
        </el-form>
        <p v-if="rulesDirty" class="semantic-muted">{{ tr('保存规则后再检查样例。', 'Save rules before checking a sample.') }}</p>
        <el-button :loading="sampleBusy" :disabled="!editable || disabled || rulesDirty || !!pendingOperation || sampleBusy || !sampleClassIri" @click="checkSample">{{ tr('检查样例', 'Check sample') }}</el-button>
        <el-alert v-if="sampleError" class="sample-result" type="error" :closable="false" :title="sampleError" />
        <div v-if="sampleResult" class="sample-result" :class="sampleResult.valid ? 'is-valid' : 'is-invalid'" role="status"><strong>{{ sampleResult.valid ? tr('符合业务规则', 'Business rules pass') : tr('发现业务规则问题', 'Business rule issues found') }}</strong><ul v-if="sampleResult.violations.length"><li v-for="violation in sampleResult.violations" :key="`${violation.code}:${violation.path}:${violation.message}`">{{ violationMessage(violation) }}</li></ul></div>
      </div>
    </div>
    <el-dialog v-model="ruleDialogOpen" :title="editingIndex === null ? tr('添加业务规则', 'Add business rule') : tr('编辑业务规则', 'Edit business rule')" width="min(520px,94vw)" :close-on-click-modal="false" @close="closeRule">
      <el-form label-position="top" @submit.prevent="submitRule"><el-form-item :label="tr('数据属性', 'Data property')" required><el-select v-model="ruleForm.predicateIri" filterable :disabled="editingIndex !== null"><el-option v-for="item in propertyOptions" :key="item.iri" :value="item.iri" :label="`${item.label} · ${datatypeLabel(item.datatype)}`" /></el-select></el-form-item><el-form-item :label="tr('约束选项', 'Constraints')"><el-checkbox v-model="ruleForm.required">{{ tr('必填', 'Required') }}</el-checkbox><el-checkbox v-model="ruleForm.singleValue">{{ tr('只允许一个不同值', 'Only one distinct value') }}</el-checkbox></el-form-item><el-form-item :label="tr('单位', 'Unit')"><el-input v-model="ruleForm.unit" :placeholder="tr('例如：kW、mm（可选）', 'For example: kW, mm (optional)')" /></el-form-item><el-form-item :label="tr('允许值（每行一个）', 'Allowed values (one per line)')"><el-input v-model="ruleForm.allowedValues" type="textarea" :rows="4" :placeholder="tr('留空表示不限', 'Leave empty for no limit')" /></el-form-item><p v-if="ruleForm.predicateIri" class="semantic-muted">{{ tr('数据类型', 'Datatype') }}：{{ datatypeLabel(rangeByProperty.get(ruleForm.predicateIri)) }}</p><div v-if="policyError" class="semantic-field-error">{{ policyError }}</div></el-form>
      <template #footer><el-button @click="closeRule">{{ tr('取消', 'Cancel') }}</el-button><el-button type="primary" @click="submitRule">{{ tr('应用规则', 'Apply rule') }}</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.business-policy-panel{min-width:0}.business-policy-heading,.policy-section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.business-policy-heading{padding:4px 0 20px;border-bottom:1px solid var(--mc-border)}.business-policy-heading h2,.policy-section-heading h3{margin:0;font-size:18px;font-weight:600}.business-policy-heading p,.policy-section-heading p{margin:8px 0 0;max-width:720px}.policy-version{color:var(--mc-text-secondary);font-size:12px;white-space:nowrap}.business-policy-layout{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(320px,.85fr);gap:28px;padding-top:20px}.business-policy-rules,.business-policy-sample{min-width:0}.policy-section-heading{margin-bottom:16px}.policy-section-heading h3{font-size:16px}.business-policy-panel .el-select{width:100%}.policy-rule-list{border-top:1px solid var(--mc-border)}.policy-rule-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--mc-border)}.policy-rule-main{display:grid;gap:3px;min-width:0}.policy-rule-main strong{overflow-wrap:anywhere}.policy-rule-iri{font:12px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--mc-text-secondary);overflow-wrap:anywhere}.policy-rule-meta{font-size:12px;color:var(--mc-text-secondary)}.policy-rule-flags{display:flex;gap:6px;flex-wrap:wrap;font-size:12px;color:var(--mc-action-text,var(--el-color-primary))}.policy-rule-flags span{padding:2px 6px;border:1px solid var(--mc-border);border-radius:3px}.policy-row-actions{display:flex;gap:4px}.policy-empty{padding:20px 0;color:var(--mc-text-secondary);font-size:13px}.policy-save-actions{display:flex;justify-content:flex-end;padding-top:16px}.sample-property-field{display:grid;grid-template-columns:minmax(0,1fr) 110px;gap:8px;margin-bottom:14px}.sample-property-field label{grid-column:1/-1;display:flex;justify-content:space-between;gap:8px;font-size:13px}.sample-property-field label span{color:var(--mc-text-secondary);font-size:12px}.sample-property-field .el-textarea{grid-column:1/-1}.sample-result{margin-top:16px;padding:12px;border:1px solid var(--mc-border);border-radius:4px;display:grid;gap:6px;font-size:13px}.sample-result.is-valid{border-color:var(--el-color-success)}.sample-result.is-invalid{border-color:var(--el-color-danger)}.sample-result>span{color:var(--mc-text-secondary);font-size:12px}.sample-result ul{margin:4px 0 0;padding-left:18px}.sample-result li{margin:4px 0}.business-policy-panel :deep(.el-alert){margin-bottom:16px}.semantic-field-error{margin-top:8px;color:var(--el-color-danger);font-size:12px}@media(max-width:900px){.business-policy-layout{grid-template-columns:1fr;gap:24px}}@media(max-width:650px){.business-policy-heading,.policy-section-heading{flex-direction:column}.policy-rule-row{grid-template-columns:minmax(0,1fr) auto}.policy-row-actions{grid-column:1/-1;justify-content:flex-end}.sample-property-field{grid-template-columns:1fr}.business-policy-layout{padding-top:16px}}
</style>
