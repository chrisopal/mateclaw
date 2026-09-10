<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { GraphResult } from '../api/workbenchTypes'
echarts.use([GraphChart, TooltipComponent, CanvasRenderer])
const props = defineProps<{ result: GraphResult }>(); const emit = defineEmits<{ 'select-statement': [event: { statementId: string; revision: number }]; 'select-entity': [id: string] }>(); const { t } = useI18n(); const element = ref<HTMLElement | null>(null); let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
let disposed = false
async function render() {
  await nextTick()
  if (disposed) return
  chart?.dispose()
  chart = null
  const host = element.value
  // Empty graphs and inactive tab panes have no drawing area.
  if (!host || !props.result.edges.length || !host.clientWidth || !host.clientHeight) return
  chart = echarts.init(host); chart.setOption({ tooltip: { trigger: 'item' }, series: [{ type: 'graph', layout: 'force', roam: true, force: { repulsion: 180 }, label: { show: true, color: '#fff' }, edgeLabel: { show: true, formatter: (p: any) => p.data?.predicateIri ?? '' }, data: props.result.nodes.map(node => ({ id: node.id, name: `${node.label}\n${node.iri}`, symbolSize: 52 })), links: props.result.edges.map(edge => ({ source: edge.sourceId, target: edge.targetId, value: edge.predicateIri, predicateIri: edge.predicateIri, statementId: edge.statementId, revision: edge.revision })) }] }); chart.on('click', (params: any) => { if (params.dataType === 'node' && params.data?.id) emit('select-entity', params.data.id); if (params.dataType === 'edge' && params.data?.statementId) emit('select-statement', { statementId: params.data.statementId, revision: params.data.revision ?? 0 }) }) }
watch(() => props.result, () => { void render() }, { deep: true, flush: 'post', immediate: true })
onMounted(() => {
  observer = new ResizeObserver(() => {
    if (disposed || !element.value?.clientWidth || !element.value.clientHeight) return
    if (chart) chart.resize()
    else if (props.result.edges.length) void render()
  })
  if (element.value) observer.observe(element.value)
})
onBeforeUnmount(() => { disposed = true; observer?.disconnect(); chart?.dispose(); chart = null })
</script>
<template><div class="graph-panel"><p v-if="!result.edges.length" class="graph-empty">{{ t('semantic.w.graphEmpty') }}</p><div ref="element" class="semantic-chart" :class="{ 'is-empty': !result.edges.length }" role="img" :aria-label="t('semantic.w.graphLabel')" /><details v-if="result.edges.length"><summary>{{ t('semantic.w.graphExplore') }}</summary><div class="graph-actions"><el-button v-for="node in result.nodes" :key="node.id" text @click="emit('select-entity', node.id)">{{ node.label }}</el-button></div><ul><li v-for="edge in result.edges" :key="edge.statementId"><el-button text @click="emit('select-statement', { statementId: edge.statementId, revision: edge.revision })">{{ edge.predicateIri }} · {{ edge.sourceId }} → {{ edge.targetId }}</el-button></li></ul></details></div></template>
<style scoped>.semantic-chart { height: 360px; width: 100%; min-width: 0; }.semantic-chart.is-empty { height: 0; overflow: hidden; }.graph-empty { color: var(--mc-text-secondary); padding: 16px 0; } summary { cursor: pointer; padding: 8px 0; } .graph-actions { display: flex; flex-wrap: wrap; gap: 4px; } ul { padding-left: 20px; } :deep(.el-button) { white-space: normal; height: auto; min-height: 32px; text-align: left; }</style>
