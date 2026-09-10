<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import type { OntologyProjectionNode } from '../ontologyProjection'
import type { DisplayProjection } from '../standardProjection'
import {
  editableRestrictions,
  restrictionEdits,
  restrictionOperators,
  type EditableRestriction,
  type RestrictionInput,
} from '../restrictionEditing'

const props = defineProps<{
  node: OntologyProjectionNode
  nodes: OntologyProjectionNode[]
  axioms: AxiomDescriptor[]
  projection: DisplayProjection
  disabled: boolean
  reload?: () => Promise<boolean>
  pending?: boolean
  retry?: () => Promise<boolean>
  failureMessage?: string
  apply: (edits: AxiomEdit[]) => Promise<boolean>
}>()
const emit = defineEmits<{ close: [] }>()
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.toLowerCase().startsWith('zh') ? zh : en
const classSubject = () => 'class:' + props.node.iri

const choices = computed(() => editableRestrictions(props.projection, props.axioms, classSubject()))
const selected = ref('')
const input = reactive<RestrictionInput>({
  subject: classSubject(),
  operator: restrictionOperators[0],
  property: '',
  filler: '',
  cardinality: '',
})
const error = ref('')
const saving = ref(false)
const preview = ref<AxiomEdit[] | null>(null)
let baseline = ''

const original = computed<EditableRestriction | undefined>(() => choices.value.find(item => item.axiom.axiomId === selected.value))
const formDisabled = computed(() => props.disabled || !!props.pending || saving.value)
const requiresCardinality = computed(() => input.operator.endsWith('Cardinality'))
const propertyNodes = computed(() => props.nodes.filter(node => node.kind === 'objectProperty'))
const fillerNodes = computed(() => props.nodes.filter(node => node.kind === 'class'))
const propertyIri = computed({
  get: () => input.property.replace(/^objectProperty:/u, ''),
  set: (value: string) => { input.property = 'objectProperty:' + value },
})
const fillerIri = computed({
  get: () => input.filler.replace(/^class:/u, ''),
  set: (value: string) => { input.filler = 'class:' + value },
})

function snapshot(): string {
  return JSON.stringify({
    axioms: props.axioms,
    projection: {
      documentDigest: props.projection.documentDigest,
      importLockDigest: props.projection.importLockDigest,
      axiomRefs: props.projection.axiomRefs,
    },
  })
}

function reset(): void {
  const old = original.value
  const firstProperty = propertyNodes.value[0]
  const firstFiller = fillerNodes.value.find(node => node.iri !== props.node.iri) ?? fillerNodes.value[0]
  Object.assign(input, old?.input ?? {
    subject: classSubject(),
    operator: restrictionOperators[0],
    property: firstProperty ? 'objectProperty:' + firstProperty.iri : '',
    filler: firstFiller ? 'class:' + firstFiller.iri : '',
    cardinality: '',
  })
  preview.value = null
  error.value = ''
}

const operatorLabel = (operator: string) => ({
  ObjectSomeValuesFrom: tr('存在限制（至少一个）', 'Some values from'),
  ObjectAllValuesFrom: tr('全称限制（所有值）', 'All values from'),
  ObjectMinCardinality: tr('最小基数', 'Minimum cardinality'),
  ObjectMaxCardinality: tr('最大基数', 'Maximum cardinality'),
  ObjectExactCardinality: tr('精确基数', 'Exact cardinality'),
}[operator] || operator)

function friendlyError(cause: unknown): string {
  const code = cause instanceof Error ? cause.message : String(cause)
  return ({
    INVALID_RESTRICTION_OPERATOR: tr('请选择有效的限制类型。', 'Choose a valid restriction type.'),
    INVALID_SUBJECT: tr('主体必须是当前概念。', 'The subject must be the current concept.'),
    INVALID_PROPERTY: tr('请输入有效的对象关系 IRI，或选择已有关系。', 'Enter a valid object property IRI or choose an existing relation.'),
    INVALID_FILLER: tr('请输入有效的限定概念 IRI，或选择已有概念。', 'Enter a valid filler concept IRI or choose an existing concept.'),
    INVALID_CARDINALITY: tr('基数必须是 0 到 2147483647 的整数。', 'Cardinality must be an integer from 0 to 2147483647.'),
    INVALID_ORIGINAL_RESTRICTION: tr('原规则不再是可安全编辑的直接规则，请重新载入并核对。', 'The original rule is no longer a safe direct rule. Reload and review it.'),
  } as Record<string, string>)[code] || code
}

watch(selected, reset)
watch([() => input.operator, () => input.property, () => input.filler, () => input.cardinality], () => {
  if (!input.operator.endsWith('Cardinality')) input.cardinality = ''
  preview.value = null
  error.value = ''
})

function prepare(): void {
  if (formDisabled.value) return
  try {
    if (selected.value && !original.value) throw new Error(tr('原规则已变化，请重新选择并核对。', 'The original rule changed. Select and review it again.'))
    const candidate: RestrictionInput = { ...input, subject: classSubject() }
    const edits = restrictionEdits(candidate, original.value)
    if (!edits.length) throw new Error(tr('规则没有变化。', 'The rule has no changes.'))
    const duplicate = choices.value.some(item => item.axiom.axiomId !== selected.value && restrictionEdits(candidate, item).length === 0)
    if (duplicate) throw new Error(tr('相同规则已经存在。', 'An identical rule already exists.'))
    baseline = snapshot()
    preview.value = edits
    error.value = ''
  } catch (cause) {
    preview.value = null
    error.value = friendlyError(cause)
  }
}

async function save(): Promise<void> {
  if (formDisabled.value || !preview.value) return
  if (baseline !== snapshot()) {
    preview.value = null
    error.value = tr('本体或投影已更新，请重新预览并核对。', 'The ontology or projection changed. Preview and review again.')
    return
  }
  saving.value = true
  error.value = ''
  try {
    const edits = preview.value
    if (await props.apply(edits)) emit('close')
    else {
      if (!props.pending) preview.value = null
      error.value = props.failureMessage || tr('未保存，输入已保留。并发冲突须重新载入后核对，不要直接重放。', 'Not saved; inputs were retained. Reload after a conflict and review before trying again.')
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}

async function recover(): Promise<void> {
  if (!props.pending || !props.retry || saving.value) return
  saving.value = true
  error.value = ''
  try {
    if (await props.retry()) emit('close')
    else error.value = props.failureMessage || tr('恢复尚未完成，请检查错误后重试。', 'Recovery did not complete. Check the error before retrying.')
  } finally {
    saving.value = false
  }
}

async function loadLatest(): Promise<void> {
  if (!props.reload || formDisabled.value) return
  saving.value = true
  try {
    if (await props.reload()) {
      preview.value = null
      error.value = tr('已载入最新本体，当前输入仍保留。请重新选择规则并核对后预览。', 'The latest ontology is loaded and the current inputs were retained. Select the rule again and review before previewing.')
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="tr('编辑概念规则', 'Edit concept rule')"
    width="min(640px, 94vw)"
    :close-on-click-modal="!formDisabled"
    :close-on-press-escape="!formDisabled"
    :show-close="!formDisabled"
    @update:model-value="!$event && emit('close')"
  >
    <p class="restriction-identity">{{ node.label }} · <code>{{ node.iri }}</code></p>
    <p class="restriction-help">{{ tr('仅支持直接的对象属性存在、全称和基数规则；嵌套、带注释或未完整投影的规则请在高级视图中编辑。', 'Only direct object property some, all, and cardinality rules are editable here; use the advanced view for nested, annotated, or incomplete rules.') }}</p>
    <el-alert v-if="pending" type="warning" :closable="false" :title="tr('保存结果尚未确定，输入已冻结。请恢复原请求。', 'Save outcome is unknown. Inputs are frozen; recover the original request.')" />
    <el-button v-if="pending && retry" :loading="saving" @click="recover">{{ tr('恢复本次保存', 'Recover this save') }}</el-button>

    <form class="restriction-form" @submit.prevent="prepare">
      <label>
        {{ tr('主体概念 IRI', 'Subject concept IRI') }}
        <input :value="node.iri" readonly :aria-label="tr('主体概念 IRI', 'Subject concept IRI')" />
      </label>
      <label>
        {{ tr('编辑规则', 'Edit rule') }}
        <select v-model="selected" :disabled="formDisabled" :aria-label="tr('编辑规则', 'Edit rule')">
          <option value="">{{ tr('新增一条规则', 'Add a new rule') }}</option>
          <option v-for="item in choices" :key="item.axiom.axiomId" :value="item.axiom.axiomId">{{ operatorLabel(item.input.operator) }} · {{ item.input.property.replace(/^objectProperty:/u, '') }} · {{ item.input.filler.replace(/^class:/u, '') }}{{ item.input.cardinality ? ' · ' + tr('基数 ', 'cardinality ') + item.input.cardinality : '' }}</option>
        </select>
      </label>
      <label>
        {{ tr('限制类型', 'Restriction type') }}
        <select v-model="input.operator" :disabled="formDisabled" :aria-label="tr('限制类型', 'Restriction type')">
          <option v-for="operator in restrictionOperators" :key="operator" :value="operator">{{ operatorLabel(operator) }}</option>
        </select>
      </label>
      <label>
        {{ tr('关系 IRI', 'Relation IRI') }}
        <input v-model="propertyIri" :disabled="formDisabled" :aria-label="tr('关系 IRI', 'Relation IRI')" placeholder="urn:example:hasPart" />
        <select :value="propertyNodes.some(candidate => candidate.id === input.property) ? input.property : ''" :disabled="formDisabled" :aria-label="tr('选择已有关系', 'Choose existing relation')" @change="input.property = ($event.target as HTMLSelectElement).value">
          <option value="">{{ tr('选择已有关系', 'Choose an existing relation') }}</option>
          <option v-for="candidate in propertyNodes" :key="candidate.id" :value="candidate.id">{{ candidate.label }} · {{ candidate.iri }}</option>
        </select>
      </label>
      <label>
        {{ tr('限定概念 IRI', 'Filler concept IRI') }}
        <input v-model="fillerIri" :disabled="formDisabled" :aria-label="tr('限定概念 IRI', 'Filler concept IRI')" placeholder="urn:example:Part" />
        <select :value="fillerNodes.some(candidate => candidate.id === input.filler) ? input.filler : ''" :disabled="formDisabled" :aria-label="tr('选择已有概念', 'Choose existing concept')" @change="input.filler = ($event.target as HTMLSelectElement).value">
          <option value="">{{ tr('选择已有概念', 'Choose an existing concept') }}</option>
          <option v-for="candidate in fillerNodes" :key="candidate.id" :value="candidate.id">{{ candidate.label }} · {{ candidate.iri }}</option>
        </select>
      </label>
      <label v-if="requiresCardinality">
        {{ tr('基数', 'Cardinality') }}
        <input :value="input.cardinality" type="number" min="0" max="2147483647" step="1" :disabled="formDisabled" :aria-label="tr('基数', 'Cardinality')" @input="input.cardinality = ($event.target as HTMLInputElement).value" />
      </label>
      <p class="restriction-help">{{ tr('全称限制只说明所有已存在的关系值都符合限定概念，不表示必须存在关系；最大基数也不是业务上的“单值”标记。', 'An all-values-from restriction describes existing relation values and does not assert existence; a maximum cardinality is not a business “single value” flag.') }}</p>
      <p v-if="original" class="restriction-help">{{ tr('原规则', 'Original rule') }} <code>{{ original.axiom.rendering }}</code></p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div class="restriction-actions">
        <el-button native-type="submit" :disabled="formDisabled">{{ tr('预览规则变更', 'Preview rule changes') }}</el-button>
        <el-button :disabled="formDisabled" @click="reset">{{ tr('重置输入', 'Reset input') }}</el-button>
        <el-button :disabled="formDisabled" @click="emit('close')">{{ tr('取消', 'Cancel') }}</el-button>
        <el-button v-if="reload && !pending" :loading="saving" :disabled="formDisabled" @click="loadLatest">{{ tr('载入最新本体并核对', 'Load latest ontology for review') }}</el-button>
      </div>
    </form>

    <section v-if="preview" class="restriction-preview" aria-live="polite">
      <h3>{{ tr('待保存的规则变更', 'Rule changes to save') }}</h3>
      <div v-for="(edit, index) in preview" :key="index" class="restriction-preview-edit">
        <strong>{{ edit.kind }}</strong>
        <pre>{{ edit.functionalSyntax || (edit.axiomId ? original?.axiom.rendering : '') }}</pre>
      </div>
      <p>{{ tr('仅修改以上规则。规则替换会生成新的来源公理标识；旧来源绑定不会自动移植。保存后请核对来源依据并重新校验草稿。', 'Only these rules change. Replacing a rule creates a new source axiom identity; existing source bindings are not transferred automatically. Review evidence and validate the draft after saving.') }}</p>
      <el-button type="primary" :loading="saving" :disabled="formDisabled" @click="save">{{ tr('确认保存规则', 'Confirm rule changes') }}</el-button>
    </section>
  </el-dialog>
</template>

<style scoped>
.restriction-identity,.restriction-help{overflow-wrap:anywhere}.restriction-help{font-size:12px;color:var(--mc-text-secondary);line-height:1.6}.restriction-help code{display:block}.restriction-form{display:grid;gap:14px}.restriction-form label{display:grid;gap:6px;font-size:13px}.restriction-form input,.restriction-form select{width:100%;min-width:0;box-sizing:border-box;padding:8px;border:1px solid var(--mc-border);border-radius:4px;background:var(--mc-bg-elevated);color:var(--mc-text-primary)}.restriction-actions{display:flex;gap:8px;flex-wrap:wrap}.restriction-preview{border-top:1px solid var(--mc-border);margin-top:16px;padding-top:12px}.restriction-preview pre{white-space:pre-wrap;overflow-wrap:anywhere}.restriction-preview p{font-size:12px;line-height:1.6}.restriction-preview-edit{margin-bottom:8px}
</style>
