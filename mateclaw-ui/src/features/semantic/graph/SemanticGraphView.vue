<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { GraphResult } from '../api/workbenchTypes'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
const props = defineProps<{ result: GraphResult }>()
const emit = defineEmits<{ 'select-statement': [value: { statementId: string; revision: number }] }>()
const element = ref<HTMLDivElement>(), chart = ref<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null
function render() {
  chart.value?.setOption({ tooltip: { renderMode: 'richText' }, series: [{ type: 'graph', layout: 'circular', roam: true, circular: { rotateLabel: false }, left: '20%', right: '20%', top: '20%', bottom: '20%', label: { show: true, color: '#fff' }, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 8, lineStyle: { width: 2, opacity: 0.75 }, edgeLabel: { show: true, formatter: '{c}', fontSize: 11 }, data: props.result.nodes.map(n => ({ id: n.id, name: n.label, symbolSize: 48 })), links: props.result.edges.map(e => ({ source: e.sourceId, target: e.targetId, value: e.predicateKey, statementId: e.statementId, revision: e.revision })) }] }, true)
}
onMounted(() => {
  if (!element.value) return
  chart.value = echarts.init(element.value)
  chart.value.on('click', params => { const edge = params.data as { statementId?: string; revision?: number }; if (edge.statementId && edge.revision) emit('select-statement', { statementId: edge.statementId, revision: edge.revision }) })
  observer = new ResizeObserver(() => chart.value?.resize()); observer.observe(element.value); render()
})
watch(() => props.result, render)
onBeforeUnmount(() => { observer?.disconnect(); chart.value?.dispose() })
</script>
<template><div ref="element" class="semantic-chart" role="img" aria-label="Semantic trusted graph" /></template>
<style scoped>.semantic-chart { height: 360px; width: 100%; min-width: 0; }</style>
