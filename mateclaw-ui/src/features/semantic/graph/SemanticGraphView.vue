<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useI18n } from 'vue-i18n'
import type { Definition } from '../api/types'
import type { GraphResult } from '../api/workbenchTypes'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
const props = defineProps<{ result: GraphResult; definition: Definition }>()
const emit = defineEmits<{ 'select-entity': [id: string]; 'select-statement': [value: { statementId: string; revision: number }] }>()
const element = ref<HTMLDivElement>(), chart = shallowRef<echarts.ECharts | null>(null)
const { t } = useI18n()
const relationName = (key: string) => props.definition.relations.find(r => r.key === key)?.label ?? key
const entityName = (id: string) => props.result.nodes.find(n => n.id === id)?.label ?? id
let observer: ResizeObserver | null = null
let mounted = false
function disposeChart() {
  chart.value?.dispose()
  chart.value = null
}
async function render() {
  await nextTick()
  if (!mounted || !element.value) return
  if (!props.result.nodes.length || !props.result.edges.length) { disposeChart(); return }
  // Hidden tabs and collapsed containers have no coordinate system yet.
  if (element.value.clientWidth <= 0 || element.value.clientHeight <= 0) return
  if (!chart.value) {
    chart.value = echarts.init(element.value)
    chart.value.on('click', params => {
      const data = params.data as { id?: string; statementId?: string; revision?: number }
      if (params.dataType === 'node' && data.id) emit('select-entity', data.id)
      if (data.statementId && data.revision) emit('select-statement', { statementId: data.statementId, revision: data.revision })
    })
  } else chart.value.resize()
  chart.value?.setOption({ tooltip: { renderMode: 'richText' }, series: [{ type: 'graph', layout: 'circular', roam: true, circular: { rotateLabel: false }, left: '20%', right: '20%', top: '20%', bottom: '20%', label: { show: true, color: '#fff' }, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 8, lineStyle: { width: 2, opacity: 0.75 }, edgeLabel: { show: true, formatter: '{c}', fontSize: 11 }, data: props.result.nodes.map(n => ({ id: n.id, name: n.label, symbolSize: 48 })), links: props.result.edges.map(e => ({ source: e.sourceId, target: e.targetId, value: relationName(e.predicateKey), statementId: e.statementId, revision: e.revision })) }] }, true)
}
onMounted(() => {
  mounted = true
  if (!element.value) return
  observer = new ResizeObserver(() => { void render() })
  observer.observe(element.value)
  void render()
})
watch([() => props.result, () => props.definition], () => { void render() }, { flush: 'post' })
onBeforeUnmount(() => { mounted = false; observer?.disconnect(); disposeChart() })
</script>
<template><div class="graph-panel"><p v-if="!result.edges.length" class="graph-empty">{{ t('semantic.w.graphEmpty') }}</p><div ref="element" class="semantic-chart" :class="{ 'is-empty': !result.edges.length }" role="img" :aria-label="t('semantic.w.graphLabel')" /><details v-if="result.edges.length"><summary>{{ t('semantic.w.graphExplore') }}</summary><div class="graph-actions"><el-button v-for="node in result.nodes" :key="node.id" text @click="emit('select-entity', node.id)">{{ node.label }}</el-button></div><ul><li v-for="edge in result.edges" :key="edge.statementId"><el-button text @click="emit('select-statement', { statementId: edge.statementId, revision: edge.revision })">{{ entityName(edge.sourceId) }} → {{ relationName(edge.predicateKey) }} → {{ entityName(edge.targetId) }} · {{ t('semantic.w.viewEvidence') }}</el-button></li></ul></details></div></template>
<style scoped>.semantic-chart { height: 360px; width: 100%; min-width: 0; }.semantic-chart.is-empty { height: 0; overflow: hidden; } .graph-empty { color: var(--mc-text-secondary); padding: 16px 0; } summary { cursor: pointer; padding: 8px 0; } .graph-actions { display: flex; flex-wrap: wrap; gap: 4px; } ul { padding-left: 20px; } :deep(.el-button) { white-space: normal; height: auto; min-height: 32px; text-align: left; } @media(max-width: 600px) { .semantic-chart { height: 260px; } }</style>
