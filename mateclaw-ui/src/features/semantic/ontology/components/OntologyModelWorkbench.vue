<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import dagre from 'dagre'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import { projectOntology } from '../ontologyProjection'
import { termEdits, type TermInput } from '../ontologyForm'
const props = defineProps<{ axioms: AxiomDescriptor[]; editable?: boolean; disabled?: boolean; apply?: (edits: AxiomEdit[]) => Promise<boolean> }>()
const emit = defineEmits<{ advanced: [] }>()
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const model = computed(() => projectOntology(props.axioms))
const query = ref(''); const mode = ref('schema'); const selectedId = ref(''); const selectedEdgeId = ref(''); const zoom = ref(1)
const nodes = computed(() => model.value.nodes.filter(n => (mode.value === 'individuals' ? n.kind === 'individual' : n.kind !== 'individual') && (!query.value || `${n.label} ${n.iri}`.toLowerCase().includes(query.value.toLowerCase()))))
const selected = computed(() => model.value.nodes.find(n => n.id === selectedId.value))
const selectedEdge = computed(() => model.value.edges.find(e => e.id === selectedEdgeId.value))
const rules = computed(() => props.axioms.filter(a => selectedEdge.value ? a.axiomId === selectedEdge.value.axiomId : selected.value ? a.signatureIris.includes(selected.value.iri) : model.value.unprojectedAxiomIds.includes(a.axiomId)))
const ruleLabel = (kind: string) => ({ Declaration:tr('类型定义','Declaration'), AnnotationAssertion:tr('名称与注释','Label / annotation'), SubClassOf:tr('概念继承与限制','Subclass / restriction'), ObjectPropertyDomain:tr('关系的所属概念','Relation domain'), ObjectPropertyRange:tr('关系的关联概念','Relation range'), DataPropertyDomain:tr('属性的所属概念','Attribute domain'), DataPropertyRange:tr('属性的数据类型','Attribute datatype'), HasKey:tr('组合标识规则','Key rule'), EquivalentClasses:tr('等价概念','Equivalent concepts'), DisjointClasses:tr('互斥概念','Disjoint concepts') }[kind] || kind)
const kindLabel = (kind: string) => ({ class:tr('概念','Concept'), objectProperty:tr('关系','Relation'), dataProperty:tr('属性','Attribute'), individual:tr('本体个体','Ontology individual'), datatype:tr('数据类型','Datatype'), annotationProperty:tr('注释','Annotation') }[kind] || kind)
const layout = computed(() => {
  const graph = new dagre.graphlib.Graph({ multigraph: true }).setGraph({ rankdir: 'TB', nodesep: 32, ranksep: 32, marginx: 20, marginy: 24 }).setDefaultEdgeLabel(() => ({}))
  nodes.value.forEach(n => graph.setNode(n.id, { width: 188, height: 54 }))
  const visible = model.value.edges.filter(e => graph.hasNode(e.source) && graph.hasNode(e.target))
  visible.forEach(e => graph.setEdge(e.source, e.target, { width: 100, height: 18 }, e.id))
  dagre.layout(graph)
  return { nodes: nodes.value.map(n => ({ ...n, x: graph.node(n.id).x, y: graph.node(n.id).y })), edges: visible.map(e => {
    const line = graph.edge(e.source, e.target, e.id)
    return { ...e, sourceNode: model.value.nodes.find(n => n.id === e.source)!, targetNode: model.value.nodes.find(n => n.id === e.target)!, path: line.points.map((p, i) => `${i ? 'L' : 'M'} ${p.x} ${p.y}`).join(' '), labelX: line.x, labelY: line.y }
  }), width: Math.max(480, graph.graph().width || 480), height: Math.max(300, graph.graph().height || 300) }
})
const positioned = computed(() => layout.value.nodes)
const height = computed(() => layout.value.height)
const edges = computed(() => layout.value.edges)
function select(id: string) { selectedId.value = id; selectedEdgeId.value = '' }
watch(() => props.axioms, () => { if (!model.value.nodes.some(n => n.id === selectedId.value)) selectedId.value = ''; selectedEdgeId.value = '' })
const formOpen = ref(false); const saving = ref(false); const error = ref('')
const form = ref<TermInput>({kind:'Class',iri:'',label:'',domain:'',range:''})
function openForm(kind: TermInput['kind']) { form.value = {kind,iri:'',label:'',domain:selected.value?.kind === 'class' ? selected.value.iri : '',range:''}; error.value=''; formOpen.value=true }
async function submit() {
  if (!props.editable || props.disabled || saving.value || !props.apply) return
  error.value=''
  try {
    if (model.value.nodes.some(n => n.iri === form.value.iri)) { error.value=tr('标识已存在，请选择新的 IRI。','IRI already exists. Choose a new identifier.'); return }
    const edits = termEdits(form.value); saving.value=true
    if (await props.apply(edits)) formOpen.value=false
    else error.value=tr('未保存，请查看页面错误并重试。','Not saved. Check the page error and retry.')
  } catch { error.value=tr('请填写名称、合法的完整 IRI，以及关系或属性的两端。','Enter a name, valid absolute IRI and both property endpoints.') }
  finally { saving.value=false }
}
</script>
<template>
  <div class="ontology-model">
    <div class="model-bar">
      <div><strong>{{ tr('本体结构','Ontology structure') }}</strong><span class="model-count">{{ model.nodes.length }} {{ tr('项定义','definitions') }} · {{ model.edges.length }} {{ tr('条结构关联','structural links') }}</span></div>
      <div v-if="editable" class="model-actions"><el-button :disabled="disabled" @click="openForm('Class')">＋ {{ tr('概念','Concept') }}</el-button><el-button :disabled="disabled" @click="openForm('ObjectProperty')">＋ {{ tr('关系','Relation') }}</el-button><el-button :disabled="disabled" @click="openForm('DataProperty')">＋ {{ tr('属性','Attribute') }}</el-button></div>
    </div>
    <p class="model-note">{{ tr('结构图描述概念与属性的定义；定义域 / 值域不表示必填要求或实际业务事实。','The graph describes definitions. Domain / range do not mean required values or actual business facts.') }}</p>
    <div class="model-layout">
      <aside class="model-directory">
        <label for="ontology-term-search">{{ tr('定义目录','Definition directory') }}</label>
        <el-input id="ontology-term-search" v-model="query" clearable :placeholder="tr('搜索名称或标识','Search name or IRI')" />
        <el-radio-group v-model="mode" size="small" class="model-mode"><el-radio-button value="schema">{{ tr('概念结构','Schema') }}</el-radio-button><el-radio-button value="individuals">{{ tr('本体个体','Individuals') }}</el-radio-button></el-radio-group>
        <div class="model-term-list"><button v-for="node in nodes" :key="node.id" type="button" class="model-term" :class="{active:selectedId===node.id}" :aria-pressed="selectedId===node.id" @click="select(node.id)"><span>{{ node.label }}</span><small>{{ kindLabel(node.kind) }}</small></button><p v-if="!nodes.length" class="model-note">{{ tr('没有匹配的定义','No matching definitions') }}</p></div>
      </aside>
      <section class="model-canvas-panel" :aria-label="tr('本体关系图','Ontology graph')">
        <div class="model-canvas-bar"><span>{{ tr('结构图','Structure graph') }}</span><div><el-button size="small" :disabled="zoom<=0.5" :aria-label="tr('缩小','Zoom out')" @click="zoom=Math.max(0.5,zoom-0.25)">−</el-button><el-button size="small" @click="zoom=1">{{ Math.round(zoom*100) }}%</el-button><el-button size="small" :disabled="zoom>=2" :aria-label="tr('放大','Zoom in')" @click="zoom=Math.min(2,zoom+0.25)">＋</el-button></div></div>
        <div class="model-canvas">
          <svg v-if="nodes.length" :viewBox="`0 0 ${layout.width} ${height}`" :style="{width:`${Math.min(layout.width, 480*layout.width/layout.height)*zoom}px`,height:'auto'}" role="group" :aria-label="tr('可选择节点和连线，详情显示在右侧','Select a node or edge to inspect its details')">
            <defs><marker id="ontology-link-arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" /></marker></defs>
            <g v-for="edge in edges" :key="edge.id" class="model-edge" :class="{active:selectedEdgeId===edge.id}" role="button" tabindex="0" :aria-label="`${edge.sourceNode.label} ${edge.label} ${edge.targetNode.label}`" @click="selectedEdgeId=edge.id; selectedId=''" @keydown.enter.prevent="selectedEdgeId=edge.id; selectedId=''" @keydown.space.prevent="selectedEdgeId=edge.id; selectedId=''">
              <path :d="edge.path" marker-end="url(#ontology-link-arrow)" />
              <text :x="edge.labelX" :y="edge.labelY" text-anchor="middle">{{ edge.label }}</text>
            </g>
            <g v-for="node in positioned" :key="node.id" class="model-node" :class="{active:selectedId===node.id}" :transform="`translate(${node.x-94},${node.y-27})`" role="button" tabindex="0" :aria-label="`${kindLabel(node.kind)} ${node.label}`" @click="select(node.id)" @keydown.enter.prevent="select(node.id)" @keydown.space.prevent="select(node.id)">
              <rect width="188" height="54" rx="4" /><text x="12" y="20" class="model-node-kind">{{ kindLabel(node.kind) }}</text><text x="12" y="40">{{ node.label.length>20 ? node.label.slice(0,19)+'…' : node.label }}</text><title>{{ node.label }} · {{ node.iri }}</title>
            </g>
          </svg>
          <el-empty v-else :description="tr('添加概念，开始构建本体结构','Add a concept to start modeling')" />
        </div>
      </section>
      <aside class="model-inspector">
        <h3>{{ selected?.label || (selectedEdge ? tr('关联定义','Link definition') : tr('定义详情','Definition details')) }}</h3>
        <template v-if="selected"><el-tag size="small">{{ kindLabel(selected.kind) }}</el-tag><details class="model-identifier"><summary>{{ tr('查看标识 IRI','Identifier IRI') }}</summary><code>{{ selected.iri }}</code></details></template>
        <p v-else-if="!selectedEdge" class="model-note">{{ tr('选择左侧定义或图中的节点、连线，查看关联规则。','Select a definition, node or edge to inspect its rules.') }}</p>
        <h4>{{ selected || selectedEdge ? tr('相关规则','Related rules') : tr('其他规则','Other rules') }} · {{ rules.length }}</h4>
        <details v-for="rule in rules" :key="rule.axiomId" class="model-rule"><summary>{{ ruleLabel(rule.axiomType) }}</summary><pre>{{ rule.rendering }}</pre></details>
        <el-button link type="primary" @click="emit('advanced')">{{ tr('打开高级 OWL 编辑 / 查看','Open advanced OWL editor / viewer') }}</el-button>
      </aside>
    </div>
    <p class="model-note model-footnote">{{ tr('结构图仅展示可明确解析的命名定义与直接关联。未完整图示的公理','Only named definitions and direct links are projected. Axioms not fully visualized') }}：{{ model.unprojectedAxiomIds.length }} / {{ axioms.length }} · {{ tr('全部内容保留在高级视图中。','All content remains available in the advanced view.') }}</p>
    <el-dialog v-model="formOpen" :title="tr('新增定义','Add definition')" width="min(540px, 94vw)" :close-on-click-modal="!saving" :show-close="!saving">
      <el-form label-position="top" :disabled="disabled || saving" @submit.prevent="submit">
        <el-form-item :label="tr('名称','Name')" required><el-input v-model="form.label" /></el-form-item>
        <el-form-item :label="tr('唯一标识 IRI','Unique identifier IRI')" required><el-input v-model="form.iri" placeholder="https://example.com/quality/Probe" /></el-form-item>
        <template v-if="form.kind!=='Class'"><el-form-item :label="tr('所属概念（定义域）','Domain concept')" required><el-select v-model="form.domain" filterable><el-option v-for="n in model.nodes.filter(n=>n.kind==='class')" :key="n.id" :value="n.iri" :label="n.label" /></el-select></el-form-item><el-form-item :label="form.kind==='ObjectProperty' ? tr('关联概念（值域）','Range concept') : tr('数据类型','Datatype')" required><el-select v-model="form.range" filterable><template v-if="form.kind==='ObjectProperty'"><el-option v-for="n in model.nodes.filter(n=>n.kind==='class')" :key="n.id" :value="n.iri" :label="n.label" /></template><template v-else><el-option v-for="type in ['string','decimal','integer','boolean','dateTime']" :key="type" :value="`http://www.w3.org/2001/XMLSchema#${type}`" :label="type" /></template></el-select></el-form-item></template>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <p class="model-note">{{ tr('保存至当前草稿，发布前仍需校验。','Saves to the current draft. Validate before publishing.') }}</p>
        <el-button native-type="submit" type="primary" :loading="saving">{{ tr('保存定义','Save definition') }}</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>
<style scoped>
.ontology-model{color:var(--mc-text-primary);min-width:0}.model-bar,.model-canvas-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.model-bar{padding:4px 0 12px}.model-count{margin-left:16px;font-size:12px;color:var(--mc-text-secondary)}.model-actions{display:flex;gap:8px;flex-wrap:wrap}.model-actions .el-button+.el-button{margin-left:0}.model-note{font-size:12px;line-height:1.7;color:var(--mc-text-secondary);margin:0 0 12px}.model-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 260px;border:1px solid var(--mc-border);border-radius:4px;min-height:480px;overflow:hidden}.model-directory,.model-inspector{padding:16px;min-width:0;background:var(--mc-bg-elevated)}.model-directory{border-right:1px solid var(--mc-border)}.model-directory label{display:block;font-size:13px;font-weight:600;margin-bottom:12px}.model-mode{margin:12px 0}.model-term-list{max-height:480px;overflow:auto}.model-term{display:flex;width:100%;text-align:left;align-items:center;gap:8px;justify-content:space-between;border:0;border-radius:4px;background:transparent;color:var(--mc-text-primary);padding:10px 8px;cursor:pointer}.model-term span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.model-term small{white-space:nowrap;color:var(--mc-text-secondary)}.model-term.active,.model-term:hover{background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.model-canvas-panel{min-width:0;background:var(--mc-bg-base,var(--mc-bg-elevated))}.model-canvas-bar{padding:10px 12px;border-bottom:1px solid var(--mc-border);font-size:13px}.model-canvas{overflow:auto;height:500px}.model-canvas svg{display:block;margin:0 auto}.model-inspector{border-left:1px solid var(--mc-border);max-height:555px;overflow:auto}.model-inspector h3{font-size:15px;margin:0 0 12px;overflow-wrap:anywhere}.model-inspector h4{font-size:13px;margin:20px 0 12px}.model-identifier{margin-top:12px}.model-identifier summary,.model-rule summary{cursor:pointer;font-size:12px;overflow-wrap:anywhere}.model-identifier code{display:block;font-size:11px;overflow-wrap:anywhere;margin-top:8px}.model-rule{padding:10px 0;border-bottom:1px solid var(--mc-border)}.model-rule pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;line-height:1.6}.model-footnote{margin:12px 0 0}.model-node{cursor:pointer}.model-node rect{fill:var(--mc-bg-elevated);stroke:var(--mc-border);stroke-width:1.5}.model-node.active rect,.model-node:hover rect{stroke:var(--el-color-primary);fill:var(--el-color-primary-light-9)}.model-node text{fill:var(--mc-text-primary);font-size:13px}.model-node .model-node-kind{fill:var(--mc-text-secondary);font-size:10px}.model-edge{cursor:pointer;color:var(--mc-text-secondary)}.model-edge path{fill:none;stroke:currentColor;stroke-width:1.5}.model-edge text{fill:var(--mc-text-secondary);font-size:11px;paint-order:stroke;stroke:var(--mc-bg-elevated);stroke-width:4px}.model-edge.active{color:var(--el-color-primary)}.model-node:focus-visible rect,.model-edge:focus-visible path{stroke:var(--el-color-primary);stroke-width:3}.model-term:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}@media(max-width:1100px){.model-layout{grid-template-columns:190px minmax(0,1fr)}.model-inspector{grid-column:1/-1;border-left:0;border-top:1px solid var(--mc-border);max-height:260px}}@media(max-width:650px){.model-layout{grid-template-columns:minmax(0,1fr)}.model-directory{border-right:0;border-bottom:1px solid var(--mc-border)}.model-term-list{max-height:160px}.model-count{display:block;margin:6px 0 0}.model-canvas{height:340px}}
</style>
