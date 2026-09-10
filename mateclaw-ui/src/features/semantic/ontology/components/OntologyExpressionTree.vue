<script setup lang="ts">
import { computed } from 'vue'
import { expressionOperatorLabel, expressionRoleLabel, type DisplayExpressionTree } from '../standardProjection'

defineOptions({ name: 'OntologyExpressionTree' })
const props = defineProps<{ tree: DisplayExpressionTree; language: string }>()
const isChain = computed(() => /PropertyChain|Chain/.test(props.tree.expression.operator))
const subject = computed(() => props.tree.operands.find(item => ['subject', 'subClass', 'superProperty'].includes(item.operand.role)))
const nestedPreview = computed(() => props.tree.expression.path === 'axiom' ? props.tree.operands.find(item => item.expression)?.expression : undefined)
const pathLabel = computed(() => props.language.toLowerCase().startsWith('zh') ? '查看表达式路径' : 'Expression path')
const note = computed(() => {
  const operator = props.tree.expression.operator
  if (/AllValuesFrom/.test(operator)) return props.language.toLowerCase().startsWith('zh') ? '全称限制只约束所有已存在的值，不表示必须存在。' : 'A universal restriction constrains existing values; it does not require one to exist.'
  if (/MaxCardinality/.test(operator)) return props.language.toLowerCase().startsWith('zh') ? '最大基数不等同于业务上的 SINGLE 或单值字段。' : 'Maximum cardinality is not a business SINGLE or single-value field rule.'
  if (isChain.value) return props.language.toLowerCase().startsWith('zh') ? '属性链顺序具有语义，按原顺序显示。' : 'Property chain order is meaningful and shown as returned.'
  return ''
})
</script>

<template>
  <details class="ontology-expression-node">
    <summary class="ontology-expression-heading">
      <strong>{{ expressionOperatorLabel(tree.expression.operator, language) }}</strong>
      <span v-if="subject" class="ontology-expression-subject">{{ subject.label }}</span><span v-if="nestedPreview" class="ontology-expression-subject"> · {{ expressionOperatorLabel(nestedPreview.expression.operator, language) }}</span>
    </summary>
    <details v-if="tree.expression.path" class="ontology-expression-path">
      <summary>{{ pathLabel }}</summary>
      <code>{{ tree.expression.path }}</code>
    </details>
    <p v-if="note" class="model-note ontology-expression-note">{{ note }}</p>
    <ol v-if="tree.operands.length" class="ontology-expression-operands" :class="{ 'is-chain': isChain }">
      <li v-for="item in tree.operands" :key="`${tree.expression.id}:${item.operand.position}:${item.operand.role}`" class="ontology-expression-operand">
        <span class="ontology-expression-role">{{ expressionRoleLabel(item.operand.role, language) }} · #{{ item.operand.position + 1 }}</span>
        <OntologyExpressionTree v-if="item.expression" :tree="item.expression" :language="language" />
        <span v-else class="ontology-expression-value">
          <strong>{{ item.label }}</strong>
          <code v-if="item.target">{{ item.target.iri }}</code>

        </span>
      </li>
    </ol>
    <span v-else class="model-note">—</span>
  </details>
</template>

<style scoped>
.ontology-expression-node{min-width:0}.ontology-expression-heading{display:list-item;align-items:baseline;gap:8px;flex-wrap:wrap;min-width:0;cursor:pointer;list-style:disclosure-closed}.ontology-expression-node[open]>summary{list-style:disclosure-open}.ontology-expression-heading strong{overflow-wrap:anywhere}.ontology-expression-subject{margin-left:8px;font-size:12px;color:var(--mc-text-primary);overflow-wrap:anywhere}.ontology-expression-heading code,.ontology-expression-value code{font-size:10px;color:var(--mc-text-secondary);overflow-wrap:anywhere}.ontology-expression-path{margin:6px 0 0 18px;font-size:10px;color:var(--mc-text-secondary);overflow-wrap:anywhere}.ontology-expression-path code{display:block;margin-top:4px;overflow-wrap:anywhere}.ontology-expression-operands{margin:8px 0 0 18px;padding-left:18px;min-width:0}.ontology-expression-operands.is-chain{padding-left:22px}.ontology-expression-operand{padding:5px 0 5px 4px;min-width:0;border-left:1px solid var(--mc-border)}.ontology-expression-role{display:block;font-size:11px;color:var(--mc-text-secondary);overflow-wrap:anywhere}.ontology-expression-value{display:flex;gap:6px;align-items:baseline;flex-wrap:wrap;min-width:0;overflow-wrap:anywhere}.ontology-expression-note{margin:6px 0 0}
@media(max-width:650px){.ontology-expression-operands{margin-left:4px;padding-left:10px}.ontology-expression-operands.is-chain{padding-left:12px}.ontology-expression-path{margin-left:4px}}
</style>
