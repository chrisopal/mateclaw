<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import OntologyGraphCanvas from './OntologyGraphCanvas.vue'
import OntologyDefinitionForm from './OntologyDefinitionForm.vue'
import { graphView } from '../ontologyGraphView'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import { projectOntology, type OntologyNodeKind, type OntologyProjectionEdge } from '../ontologyProjection'
import { termEdits, type TermInput } from '../ontologyForm'
const props = defineProps<{ axioms: AxiomDescriptor[]; editable?: boolean; disabled?: boolean; reloadDraft?: () => Promise<boolean>; editPending?: boolean; retryEdit?: () => Promise<boolean>; failureMessage?: string; apply?: (edits: AxiomEdit[]) => Promise<boolean> }>()
const emit = defineEmits<{ advanced: []; 'inspect-axiom': [axiomId: string] }>()
const searchId = `ontology-term-search-${useId()}`
const canvas = ref<InstanceType<typeof OntologyGraphCanvas>>()
const inspectorOpen = ref(false)
const editingDefinition = ref(false)
const inspectorToggle = ref<{ $el: HTMLButtonElement }>()
async function closeInspector() {
  inspectorOpen.value = false
  await nextTick()
  inspectorToggle.value?.$el.focus({ preventScroll: true })
}
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const labelLanguage = ref(locale.value)
const labelLanguages = computed(() => [...new Set([locale.value, 'en', ...props.axioms.flatMap(a => { const match = /@([A-Za-z]+(?:-[A-Za-z0-9]+)*)\)$/.exec(a.rendering); return match ? [match[1]!] : [] })])])
const model = computed(() => projectOntology(props.axioms, labelLanguage.value))
const query = ref(''); const mode = ref<'schema' | 'individuals'>('schema'); const selectedId = ref(''); const selectedEdgeId = ref('')
const onlyMatches = ref(false); const neighborhood = ref(false)
const nodeKind = ref<OntologyNodeKind | ''>(''); const edgeKind = ref<OntologyProjectionEdge['kind'] | ''>('')
const view = computed(() => graphView(model.value, { query: query.value, mode: mode.value, onlyMatches: onlyMatches.value, neighborId: neighborhood.value ? selectedId.value : undefined, nodeKind: nodeKind.value, edgeKind: edgeKind.value }))
const nodes = computed(() => model.value.nodes.filter(n => (mode.value === 'individuals' ? n.kind === 'individual' : n.kind !== 'individual') && (!nodeKind.value || n.kind === nodeKind.value) && (!query.value.trim() || `${n.label} ${n.iri}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))))
const edges = computed(() => view.value.edges)
const labelById = computed(() => new Map(model.value.nodes.map(n => [n.id, n.label])))
const selected = computed(() => model.value.nodes.find(n => n.id === selectedId.value))
const selectedEdge = computed(() => model.value.edges.find(e => e.id === selectedEdgeId.value))
const rules = computed(() => props.axioms.filter(a => selectedEdge.value ? a.axiomId === selectedEdge.value.axiomId : selected.value ? a.signatureIris.includes(selected.value.iri) : model.value.unprojectedAxiomIds.includes(a.axiomId)))
const ruleLabel = (kind: string) => ({ Declaration:tr('类型定义','Declaration'), AnnotationAssertion:tr('名称与注释','Label / annotation'), SubClassOf:tr('概念继承与限制','Subclass / restriction'), ObjectPropertyDomain:tr('关系的所属概念','Relation domain'), ObjectPropertyRange:tr('关系的关联概念','Relation range'), DataPropertyDomain:tr('属性的所属概念','Attribute domain'), DataPropertyRange:tr('属性的数据类型','Attribute datatype'), HasKey:tr('组合标识规则','Key rule'), EquivalentClasses:tr('等价概念','Equivalent concepts'), DisjointClasses:tr('互斥概念','Disjoint concepts') }[kind] || kind)
const kindLabel = (kind: string) => ({ class:tr('概念','Concept'), objectProperty:tr('关系','Relation'), dataProperty:tr('属性','Attribute'), individual:tr('本体个体','Ontology individual'), datatype:tr('数据类型','Datatype'), annotationProperty:tr('注释','Annotation') }[kind] || kind)
function select(id: string) { editingDefinition.value = false; selectedId.value = id; selectedEdgeId.value = ''; inspectorOpen.value = true; void nextTick(() => canvas.value?.focus()) }
function selectEdge(id: string) { editingDefinition.value = false; selectedEdgeId.value = id; neighborhood.value = false; selectedId.value = ''; inspectorOpen.value = true }
watch(query, () => { void nextTick(() => canvas.value?.focus(view.value.matches[0])) })
watch([mode, nodeKind], () => { selectedId.value = ''; selectedEdgeId.value = ''; neighborhood.value = false; inspectorOpen.value = false })
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
        <label :for="searchId">{{ tr('定义目录','Definition directory') }}</label>
        <el-input :id="searchId" v-model="query" clearable :placeholder="tr('搜索名称或标识','Search name or IRI')" />
        <label class="model-kind-filter">{{ tr('显示语言','Display language') }}<select v-model="labelLanguage" :aria-label="tr('显示语言','Display language')"><option v-for="lang in labelLanguages" :key="lang" :value="lang">{{lang}}</option><option value="">{{tr('无语言优先','Untagged first')}}</option></select></label>
        <el-radio-group v-model="mode" size="small" class="model-mode"><el-radio-button value="schema">{{ tr('概念结构','Schema') }}</el-radio-button><el-radio-button value="individuals">{{ tr('本体个体','Individuals') }}</el-radio-button></el-radio-group>
        <label class="model-kind-filter">{{ tr('定义种类','Definition kind') }}<select v-model="nodeKind" :aria-label="tr('定义种类','Definition kind')"><option value="">{{ tr('全部','All') }}</option><option v-for="kind in (mode === 'individuals' ? ['individual'] : ['class','objectProperty','dataProperty','datatype','annotationProperty'])" :key="kind" :value="kind">{{ kindLabel(kind) }}</option></select></label>
        <el-checkbox v-model="onlyMatches">{{ tr('仅看匹配项','Only matching nodes') }}</el-checkbox>
        <p v-if="query" class="model-note">{{ nodes.length }} {{ tr('项匹配；默认高亮并保留图形上下文。','matches; highlighted in context by default.') }}</p>
        <div class="model-term-list"><button v-for="node in nodes" :key="node.id" type="button" class="model-term" :class="{active:selectedId===node.id}" :aria-pressed="selectedId===node.id" @click="select(node.id)"><span>{{ node.label }}</span><small>{{ kindLabel(node.kind) }}</small></button><p v-if="!nodes.length" class="model-note">{{ tr('没有匹配的定义','No matching definitions') }}</p></div>
      </aside>
      <section class="model-canvas-panel" :aria-label="tr('本体关系图','Ontology graph')">
        <div class="model-canvas-bar">
          <el-checkbox v-model="neighborhood" :disabled="!selectedId">{{ tr('所选一跳邻域','Selected one-hop neighborhood') }}</el-checkbox>
          <label class="model-edge-filter">{{ tr('关联类型','Link type') }}<select v-model="edgeKind" :aria-label="tr('关联类型','Link type')"><option value="">{{ tr('全部','All') }}</option><option value="subClassOf">{{ tr('继承','Subclass') }}</option><option value="domain">{{ tr('定义域','Domain') }}</option><option value="range">{{ tr('值域','Range') }}</option><option value="someValuesFrom">{{ tr('存在限制','Existential') }}</option><option value="allValuesFrom">{{ tr('全称限制','Universal') }}</option></select></label>
        </div>
        <p class="model-note model-visibility" aria-live="polite">{{ tr('当前显示','Showing') }} {{ view.nodes.length }} / {{ model.nodes.length }} · {{ tr('已隐藏','Hidden') }} {{ view.hiddenNodes }}<span v-if="view.truncated"> · {{ tr('已达到 500 节点显示上限，请搜索或使用邻域缩小范围。','500-node display limit reached. Search or narrow to a neighborhood.') }}</span></p>
        <OntologyGraphCanvas ref="canvas" :nodes="view.nodes" :edges="view.edges" :selected-node-id="selectedId" :selected-edge-id="selectedEdgeId" :highlighted-ids="view.matches" @select-node="select" @select-edge="selectEdge" />
        <details class="model-links"><summary>{{ tr('关系列表（支持键盘选择）','Relationship list (keyboard accessible)') }} · {{ edges.length }}</summary><button v-for="edge in edges" :key="edge.id" type="button" class="model-edge" :aria-pressed="selectedEdgeId===edge.id" @click="selectEdge(edge.id)">{{ labelById.get(edge.source) }} → {{ edge.label }} → {{ labelById.get(edge.target) }}</button><p v-if="!edges.length" class="model-note">{{ tr('当前范围没有可图示关联。','No projected links in this view.') }}</p></details>
        <el-button ref="inspectorToggle" class="model-mobile-details" @click="inspectorOpen=true">{{ tr('查看定义详情','Inspect definitions') }}</el-button>
      </section>
      <aside class="model-inspector" :class="{'is-open': inspectorOpen}">
        <el-button class="model-mobile-details" @click="closeInspector">{{ tr('关闭详情','Close details') }}</el-button>
        <h3>{{ selected?.label || (selectedEdge ? tr('关联定义','Link definition') : tr('定义详情','Definition details')) }}</h3>
        <el-button v-if="selected && editable && apply" :disabled="disabled" @click="editingDefinition=true">{{ tr('编辑此定义','Edit this definition') }}</el-button>
        <template v-if="selected"><el-tag size="small">{{ kindLabel(selected.kind) }}</el-tag><details class="model-identifier"><summary>{{ tr('查看标识 IRI','Identifier IRI') }}</summary><code>{{ selected.iri }}</code></details></template>
        <p v-else-if="!selectedEdge" class="model-note">{{ tr('选择左侧定义或图中的节点、连线，查看关联规则。','Select a definition, node or edge to inspect its rules.') }}</p>
        <h4>{{ selected || selectedEdge ? tr('相关规则','Related rules') : tr('其他规则','Other rules') }} · {{ rules.length }}</h4>
        <details v-for="rule in rules" :key="rule.axiomId" class="model-rule"><summary>{{ ruleLabel(rule.axiomType) }}</summary><pre>{{ rule.rendering }}</pre><el-button link type="primary" @click="emit('inspect-axiom',rule.axiomId); inspectorOpen=false">{{ tr('查看公理与来源','Inspect axiom and sources') }}</el-button></details>
        <el-button link type="primary" @click="emit('advanced')">{{ tr('打开高级 OWL 编辑 / 查看','Open advanced OWL editor / viewer') }}</el-button>
      </aside>
    </div>
    <p class="model-note model-footnote">{{ tr('结构图仅展示可明确解析的命名定义与直接关联。未完整图示的公理','Only named definitions and direct links are projected. Axioms not fully visualized') }}：{{ model.unprojectedAxiomIds.length }} / {{ axioms.length }} · {{ tr('全部内容保留在高级视图中。','All content remains available in the advanced view.') }}</p>
    <OntologyDefinitionForm v-if="editingDefinition && selected && editable && apply" :node="selected" :nodes="model.nodes" :axioms="axioms" :disabled="!!disabled" :failure-message="failureMessage" :pending="editPending" :retry="retryEdit" :reload="reloadDraft" :apply="apply" @close="editingDefinition=false" />
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
.ontology-model{color:var(--mc-text-primary);min-width:0}.model-bar,.model-canvas-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.model-bar{padding:4px 0 12px}.model-count{margin-left:16px;font-size:12px;color:var(--mc-text-secondary)}.model-actions{display:flex;gap:8px;flex-wrap:wrap}.model-actions .el-button+.el-button{margin-left:0}.model-note{font-size:12px;line-height:1.7;color:var(--mc-text-secondary);margin:0 0 12px}.model-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 260px;border:1px solid var(--mc-border);border-radius:4px;min-height:480px;overflow:hidden}.model-directory,.model-inspector{padding:16px;min-width:0;background:var(--mc-bg-elevated)}.model-directory{border-right:1px solid var(--mc-border)}.model-directory label{display:block;font-size:13px;font-weight:600;margin-bottom:12px}.model-mode{margin:12px 0}.model-term-list{max-height:480px;overflow:auto}.model-term{display:flex;width:100%;text-align:left;align-items:center;gap:8px;justify-content:space-between;border:0;border-radius:4px;background:transparent;color:var(--mc-text-primary);padding:10px 8px;cursor:pointer}.model-term span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.model-term small{white-space:nowrap;color:var(--mc-text-secondary)}.model-term.active,.model-term:hover{background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.model-canvas-panel{min-width:0;background:var(--mc-bg-base,var(--mc-bg-elevated))}.model-canvas-bar{padding:10px 12px;border-bottom:1px solid var(--mc-border);font-size:13px}.model-inspector{border-left:1px solid var(--mc-border);max-height:555px;overflow:auto}.model-inspector h3{font-size:15px;margin:0 0 12px;overflow-wrap:anywhere}.model-inspector h4{font-size:13px;margin:20px 0 12px}.model-identifier{margin-top:12px}.model-identifier summary,.model-rule summary{cursor:pointer;font-size:12px;overflow-wrap:anywhere}.model-identifier code{display:block;font-size:11px;overflow-wrap:anywhere;margin-top:8px}.model-rule{padding:10px 0;border-bottom:1px solid var(--mc-border)}.model-rule pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;line-height:1.6}.model-footnote{margin:12px 0 0}.model-term:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}@media(max-width:1100px){.model-layout{grid-template-columns:190px minmax(0,1fr)}.model-inspector{grid-column:1/-1;border-left:0;border-top:1px solid var(--mc-border);max-height:260px}}@media(max-width:650px){.model-layout{grid-template-columns:minmax(0,1fr)}.model-directory{border-right:0;border-bottom:1px solid var(--mc-border)}.model-term-list{max-height:160px}.model-count{display:block;margin:6px 0 0}.model-inspector{display:none}.model-inspector.is-open{display:block;position:fixed;inset:auto 0 0;z-index:1900;max-height:70vh;box-shadow:0 -4px 24px #0002;padding:20px}.model-mobile-details{display:inline-flex}}
.model-mobile-details{display:none}.model-kind-filter,.model-edge-filter{font-size:12px;display:flex;gap:8px;align-items:center}.model-kind-filter{margin:8px 0}.model-kind-filter select,.model-edge-filter select{min-width:0;max-width:100%;padding:4px;border:1px solid var(--mc-border);border-radius:4px;background:var(--mc-bg-elevated);color:var(--mc-text-primary)}.model-links{padding:12px;border-top:1px solid var(--mc-border);max-height:180px;overflow:auto;font-size:12px}.model-links summary{cursor:pointer}.model-edge{display:block;width:100%;border:0;background:transparent;color:var(--mc-text-primary);text-align:left;padding:8px;overflow-wrap:anywhere;cursor:pointer}.model-edge:hover,.model-edge[aria-pressed=true]{background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.model-visibility{padding:8px 12px;margin:0}.model-edge:focus-visible{outline:2px solid var(--el-color-primary)}@media(max-width:650px){.model-mobile-details{display:inline-flex}}
</style>
