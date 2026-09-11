<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ValidationReport, Violation } from '../../api/types'
import type { DisplayProjection } from '../standardProjection'
import { isPublicationReport, requiredChecks } from '../validationReport'
const props = defineProps<{ report: ValidationReport | null; errors?: Violation[]; dirty: boolean; projection?: DisplayProjection | null }>()
defineEmits<{ locate: [path: string] }>()
const { t, te, locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const passed = computed(() => !props.dirty && isPublicationReport(props.report, props.report?.draftVersion ?? 0))
const expired = computed(() => props.dirty || props.report?.stale === true)
const labels: Record<string, [string, string]> = {
  STRUCTURE: ['模型结构', 'Model structure'], LOGIC: ['模型逻辑', 'Model logic'],
  POLICY: ['业务规则', 'Business rules'], SOURCES: ['参考资料', 'References'],
}
const impossibleTypes = computed(() => props.report?.checks?.find(check => check.kind === 'LOGIC')?.unsatisfiableClasses ?? [])
function classLabel(iri: string) {
  const node = props.projection?.nodes.find(item => item.iri === iri || item.id === iri)
  const label = node?.labels.find(item => item.language.toLowerCase() === locale.value.toLowerCase())
    ?? node?.labels.find(item => item.language.split('-')[0] === locale.value.split('-')[0]) ?? node?.labels[0]
  return label?.value || iri.split(/[#/:]/).pop() || tr('未命名对象', 'Unnamed object')
}
function issueLabel(issue: Violation) {
  const known: Record<string, [string, string]> = {
    LOGIC_INCONSISTENT: ['模型存在逻辑矛盾，请调整对象关系或规则。', 'The model is inconsistent. Review relationships and rules.'],
    LOGIC_UNSATISFIABLE: ['部分对象类型无法成立，请修改其约束。', 'Some object types cannot exist. Review their constraints.'],
    LOGIC_TIMEOUT: ['逻辑检查超时，请重新检查。', 'The logical check timed out. Run checks again.'],
    LOGIC_FAILED: ['逻辑检查未完成，请重试。', 'The logical check failed. Try again.'],
    REASONING_DISABLED: ['当前环境未启用逻辑检查。', 'Logical checking is disabled in this environment.'],
    SOURCE_CHANGED: ['参考资料已变化，请核对并更新引用。', 'References changed. Review and update the cited evidence.'],
    SOURCE_UNAVAILABLE: ['参考资料不可用，请恢复资料或更新引用。', 'References are unavailable. Restore or replace the cited evidence.'],
    SOURCE_REVIEW_REQUIRED: ['参考资料尚未确认，请在参考资料中核对原文。', 'References need confirmation. Review the original evidence.'],
    SOURCE_REVIEW_PENDING: ['参考资料仍有待处理的变更。', 'Reference changes still need review.'],
    POLICY_CLASS_NOT_FOUND: ['业务规则引用的对象类型不存在。', 'A business rule refers to a missing object type.'],
    POLICY_DATA_PROPERTY_NOT_FOUND: ['业务规则引用的属性不存在。', 'A business rule refers to a missing property.'],
    POLICY_DUPLICATE_RULE: ['同一对象属性有重复规则。', 'A property has duplicate business rules.'],
  }
  const label = known[issue.code]
  return label ? tr(...label) : te('semantic.violations.' + issue.code) ? t('semantic.violations.' + issue.code) : issue.message
}
function status(kind: string) { return expired.value ? 'STALE' : props.report?.checks?.find(item => item.kind === kind)?.status ?? 'NOT_RUN' }
function statusLabel(value: string) {
  const labels: Record<string, [string, string]> = { PASS: ['通过', 'Passed'], FAIL: ['需处理', 'Needs attention'], NOT_RUN: ['未完成', 'Not completed'], ERROR: ['检查失败', 'Check failed'], TIMEOUT: ['检查超时', 'Timed out'], STALE: ['已过期', 'Expired'] }
  const label = labels[value] ?? labels.ERROR!
  return tr(...label)
}
</script>
<template>
  <section class="validation-report" aria-live="polite">
    <p class="validation-summary">{{ expired ? tr('模型或资料已变化，请重新检查。', 'The model or references changed. Run checks again.') : passed ? tr('检查通过，可以发布。', 'Checks passed. Ready to publish.') : tr('完成以下检查后再发布。', 'Complete the checks before publishing.') }}</p>
    <dl class="validation-layers">
      <div v-for="kind in requiredChecks" :key="kind"><dt>{{ tr(...labels[kind]!) }}</dt><dd :class="`check-${status(kind).toLowerCase()}`">{{ statusLabel(status(kind)) }}</dd></div>
    </dl>
    <p v-if="impossibleTypes.length" class="validation-summary">{{ tr('无法成立的对象类型：', 'Unsatisfiable object types: ') }}{{ impossibleTypes.map(classLabel).join('、') }}</p>
    <p class="semantic-muted validation-scope">{{ tr('业务规则检查其定义；实际业务覆盖仍需人工确认。', 'Business rules are checked for valid definitions; business coverage requires human confirmation.') }}</p>
    <details v-if="report?.violations.length || errors?.length" class="validation-details">
      <summary>{{ tr('查看待处理事项', 'View issues') }} · {{ (errors?.length ?? 0) + (report?.violations.length ?? 0) }}</summary>
      <el-table :data="[...(errors ?? []), ...(report?.violations ?? [])]">
        <el-table-column :label="t('semantic.issue')" min-width="200"><template #default="{ row }">{{ issueLabel(row) }}</template></el-table-column>
        <el-table-column width="100"><template #default="{ row }"><el-button link type="primary" @click="$emit('locate', row.path)">{{ t('semantic.locate') }}</el-button></template></el-table-column>
      </el-table>
    </details>
  </section>
</template>
<style scoped>
.validation-report{max-width:860px;padding:16px 0}.validation-summary{margin:0 0 12px;font-size:14px;font-weight:600}.validation-layers{margin:0}.validation-layers>div{display:flex;justify-content:space-between;gap:16px;padding:12px 0;border-bottom:1px solid var(--mc-border);font-size:14px}.validation-layers dd{margin:0;color:var(--mc-text-secondary)}.validation-layers .check-pass{color:var(--el-color-success)}.validation-layers .check-fail,.validation-layers .check-error,.validation-layers .check-timeout{color:var(--el-color-danger)}.validation-layers .check-stale{color:var(--el-color-warning)}.validation-scope{font-size:12px;margin:12px 0 0}.validation-details{margin-top:16px}.validation-details summary{cursor:pointer;font-size:13px}
</style>
