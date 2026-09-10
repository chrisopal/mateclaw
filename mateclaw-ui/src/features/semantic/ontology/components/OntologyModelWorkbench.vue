<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, useId, watch } from 'vue'
import BusinessModelForm from './BusinessModelForm.vue'
import { businessRuleLabel, type ModelChange } from '../businessModel'
import OntologyGraphCanvas from './OntologyGraphCanvas.vue'
import OntologyDefinitionForm from './OntologyDefinitionForm.vue'
import OntologyRestrictionForm from './OntologyRestrictionForm.vue'
import { graphView } from '../ontologyGraphView'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import { type OntologyProjectionNode, type OntologyNodeKind, type OntologyProjectionEdge } from '../ontologyProjection'
import { displayModel, expressionTrees, type DisplayProjection } from '../standardProjection'
import OntologyExpressionTree from './OntologyExpressionTree.vue'
import { termEdits, type TermInput } from '../ontologyForm'
const props = defineProps<{ axioms: AxiomDescriptor[]; projection?: DisplayProjection | null; projectionLoading?: boolean; projectionError?: string; editable?: boolean; disabled?: boolean; reloadDraft?: () => Promise<boolean>; editPending?: boolean; retryEdit?: () => Promise<boolean>; failureMessage?: string; applyModel?: (changes: ModelChange[]) => Promise<boolean>; apply?: (edits: AxiomEdit[]) => Promise<boolean> }>()
const emit = defineEmits<{ advanced: []; 'reload-projection': []; 'inspect-axiom': [axiomId: string] }>()
const searchId = `ontology-term-search-${useId()}`
const canvas = ref<InstanceType<typeof OntologyGraphCanvas>>()
const inspectorOpen = ref(false)
const editingDefinition = ref(false)
const editingRestriction = ref(false)
const restrictionProjection = shallowRef<DisplayProjection | null>(null)
const editingNode = shallowRef<OntologyProjectionNode>()
function startDefinition(){ editingRestriction.value=false; editingNode.value=selected.value; editingDefinition.value=true }
function startRestriction(){ if(!props.projection || selected.value?.kind!=='class')return; editingDefinition.value=false; editingNode.value=selected.value; restrictionProjection.value=props.projection; editingRestriction.value=true }
const inspectorToggle = ref<{ $el: HTMLButtonElement }>()
async function closeInspector() {
  inspectorOpen.value = false
  await nextTick()
  inspectorToggle.value?.$el.focus({ preventScroll: true })
}
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const labelLanguage = ref(locale.value)
const labelLanguages = computed(() => [...new Set([locale.value, 'en', ...(props.projection?.nodes.flatMap(n=>n.labels.map(l=>l.language)) || []).filter(Boolean)])])
const model = computed(() => {
 const model=displayModel(props.projection,labelLanguage.value)
 const labels:Record<string,string>={subClassOf:tr('属于分类','Belongs to'),domain:tr('所属对象','Owner object'),range:tr('关联对象','Related object'),equivalentClasses:tr('等价','Equivalent'),disjointClasses:tr('互斥','Disjoint'),inverseOf:tr('互为逆属性','Inverse properties')}
 const types:Record<string,string>={string:tr('文本','Text'),decimal:tr('数字','Number'),integer:tr('整数','Integer'),boolean:tr('是／否','Yes / no'),dateTime:tr('日期时间','Date and time')}
 return {...model,nodes:model.nodes.map(n=>n.kind==='datatype'&&n.iri.startsWith('http://www.w3.org/2001/XMLSchema#')?{...n,label:types[n.iri.split('#')[1]!]||n.label}:n),edges:model.edges.map(e=>({...e,label:e.kind==='domain'?(model.nodes.find(n=>n.id===e.target)?.kind==='dataProperty'?tr('属性','Attribute'):tr('关系','Relation')):e.kind==='range'&&model.nodes.find(n=>n.id===e.target)?.kind==='datatype'?tr('内容类型','Content type'):labels[e.kind]||e.label}))}
})
const connector=(kind:string)=>['equivalentClasses','disjointClasses','inverseOf'].includes(kind)?'↔':'→'
const query = ref(''); const mode = ref<'schema' | 'individuals'>('schema'); const selectedId = ref(''); const selectedEdgeId = ref('')
const onlyMatches = ref(false); const neighborhood = ref(false)
const nodeKind = ref<OntologyNodeKind | ''>(''); const edgeKind = ref<OntologyProjectionEdge['kind'] | ''>('')
const view = computed(() => graphView(model.value, { query: query.value, mode: mode.value, onlyMatches: onlyMatches.value, neighborId: neighborhood.value ? selectedId.value : undefined, nodeKind: nodeKind.value, edgeKind: edgeKind.value }))
const nodes = computed(() => model.value.nodes.filter(n => (mode.value === 'individuals' ? n.kind === 'individual' : n.kind !== 'individual') && (!nodeKind.value || n.kind === nodeKind.value) && (!query.value.trim() || `${n.label} ${n.iri}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))))
const edges = computed(() => view.value.edges)
const labelById = computed(() => new Map(model.value.nodes.map(n => [n.id, n.label])))
const selected = computed(() => model.value.nodes.find(n => n.id === selectedId.value))
const selectedEdge = computed(() => model.value.edges.find(e => e.id === selectedEdgeId.value))
const selectedDefinition = computed(()=>props.projection?.nodes.find(n=>n.id===selectedId.value))
const rules = computed(() => {
  if(!props.projection)return props.axioms.map(a=>({...a,id:a.axiomId,artifactId:'',imported:false,status:'NOT_RENDERED',reason:''}))
  return props.projection.axiomRefs.filter(a=>selectedEdge.value?a.id===selectedEdge.value.axiomId:selected.value?selected.value.axiomIds.includes(a.id):a.status!=='FULL')
})
const expressionRoots = computed(() => expressionTrees(props.projection, labelLanguage.value))
const expressionRef = (expressionAxiomId: string) => props.projection?.axiomRefs.find(item => item.id === expressionAxiomId || item.axiomId === expressionAxiomId)
const sourceAxiomId = (expressionAxiomId: string) => expressionRef(expressionAxiomId)?.axiomId || expressionAxiomId
const featureLabel=(feature:string)=>({FunctionalObjectProperty:tr('函数属性（OWL）','Functional (OWL)'),InverseFunctionalObjectProperty:tr('逆函数属性（OWL）','Inverse functional (OWL)'),FunctionalDataProperty:tr('函数数据属性（OWL）','Functional data property (OWL)'),TransitiveObjectProperty:tr('传递属性','Transitive'),SymmetricObjectProperty:tr('对称属性','Symmetric'),AsymmetricObjectProperty:tr('非对称属性','Asymmetric'),ReflexiveObjectProperty:tr('自反属性','Reflexive'),IrreflexiveObjectProperty:tr('非自反属性','Irreflexive')}[feature]||feature)
const kindLabel = (kind: string) => ({ class:tr('业务对象','Business object'), objectProperty:tr('关系','Relation'), dataProperty:tr('属性','Attribute'), individual:tr('本体个体','Ontology individual'), datatype:tr('数据类型','Datatype'), annotationProperty:tr('注释','Annotation') }[kind] || kind)
function select(id: string) { editingDefinition.value = false; selectedId.value = id; selectedEdgeId.value = ''; inspectorOpen.value = true; void nextTick(() => canvas.value?.focus()) }
function selectEdge(id: string) { editingDefinition.value = false; selectedEdgeId.value = id; neighborhood.value = false; selectedId.value = ''; inspectorOpen.value = true }
watch(query, () => { void nextTick(() => canvas.value?.focus(view.value.matches[0])) })
watch([mode, nodeKind], () => { selectedId.value = ''; selectedEdgeId.value = ''; neighborhood.value = false; inspectorOpen.value = false })
watch(() => props.projection, () => { if (!props.projection) return; if (!model.value.nodes.some(n => n.id === selectedId.value)) selectedId.value = ''; selectedEdgeId.value = '' })
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
      <div><strong>{{ tr('业务模型','Business model') }}</strong><span v-if="projection" class="model-count">{{ model.nodes.length }} {{ tr('项模型内容','model items') }} · {{ model.edges.length }} {{ tr('条关联','links') }}</span></div>
      <div v-if="editable" class="model-actions"><el-button :disabled="disabled" @click="openForm('Class')">＋ {{ tr('业务对象','Business object') }}</el-button><el-button :disabled="disabled" @click="openForm('ObjectProperty')">＋ {{ tr('关系','Relation') }}</el-button><el-button :disabled="disabled" @click="openForm('DataProperty')">＋ {{ tr('属性','Attribute') }}</el-button></div>
    </div>
    <el-alert v-if="projectionLoading" :title="tr('正在加载模型…','Loading model…')" type="info" :closable="false" />
    <div v-if="projectionError" class="model-projection-error" role="alert"><p>{{tr('模型暂时无法显示，请重新加载；也可从更多操作查看完整文档。','Model unavailable. Reload or use more actions to view the complete document.')}} {{projectionError}}</p><el-button @click="emit('reload-projection')">{{tr('重新加载模型','Reload model')}}</el-button></div>
    <div v-if="!projectionLoading" class="model-layout">
      <aside class="model-directory">
        <label :for="searchId">{{ tr('对象与关系','Objects and relations') }}</label>
        <el-input :id="searchId" v-model="query" clearable :placeholder="tr('搜索对象或关系','Search objects or relations')" />
        <details class="model-filters"><summary>{{tr('显示设置','Display settings')}}</summary>
        <label class="model-kind-filter">{{ tr('显示语言','Display language') }}<select v-model="labelLanguage" :aria-label="tr('显示语言','Display language')"><option v-for="lang in labelLanguages" :key="lang" :value="lang">{{lang}}</option><option value="">{{tr('无语言优先','Untagged first')}}</option></select></label>
        <el-radio-group v-model="mode" size="small" class="model-mode"><el-radio-button value="schema">{{ tr('概念结构','Schema') }}</el-radio-button><el-radio-button value="individuals">{{ tr('本体个体','Individuals') }}</el-radio-button></el-radio-group>
        <label class="model-kind-filter">{{ tr('定义种类','Definition kind') }}<select v-model="nodeKind" :aria-label="tr('定义种类','Definition kind')"><option value="">{{ tr('全部','All') }}</option><option v-for="kind in (mode === 'individuals' ? ['individual'] : ['class','objectProperty','dataProperty','datatype','annotationProperty'])" :key="kind" :value="kind">{{ kindLabel(kind) }}</option></select></label>
        <el-checkbox v-model="onlyMatches">{{ tr('仅看匹配项','Only matching nodes') }}</el-checkbox>
        </details>
        <p v-if="query" class="model-note">{{ nodes.length }} {{ tr('项匹配；默认高亮并保留图形上下文。','matches; highlighted in context by default.') }}</p>
        <div class="model-term-list"><button v-for="node in nodes" :key="node.id" type="button" class="model-term" :class="{active:selectedId===node.id}" :aria-pressed="selectedId===node.id" @click="select(node.id)"><span>{{ node.label }}</span><small>{{ kindLabel(node.kind) }}</small></button><p v-if="!nodes.length" class="model-note">{{ projection ? tr('没有匹配的对象或关系','No matching definitions') : tr('模型暂时不可用','Model unavailable') }}</p></div>
      </aside>
      <section class="model-canvas-panel" :aria-label="tr('本体关系图','Ontology graph')">
        <div class="model-canvas-bar">
          <el-checkbox v-model="neighborhood" :disabled="!selectedId">{{ tr('仅看相关对象','Related objects only') }}</el-checkbox>
          <label class="model-edge-filter">{{ tr('关联类型','Link type') }}<select v-model="edgeKind" :aria-label="tr('关联类型','Link type')"><option value="">{{ tr('全部','All') }}</option><option value="subClassOf">{{ tr('继承','Subclass') }}</option><option value="domain">{{ tr('所属对象','Owner object') }}</option><option value="range">{{ tr('关联对象','Related object') }}</option><option value="equivalentClasses">{{tr('等价','Equivalent')}}</option><option value="disjointClasses">{{tr('互斥','Disjoint')}}</option><option value="inverseOf">{{tr('逆属性','Inverse')}}</option><option value="someValuesFrom">{{ tr('存在限制','Existential') }}</option><option value="allValuesFrom">{{ tr('全称限制','Universal') }}</option></select></label>
        </div>
        <p class="model-note model-visibility" aria-live="polite">{{ tr('当前显示','Showing') }} {{ view.nodes.length }} / {{ model.nodes.length }} · {{ tr('已隐藏','Hidden') }} {{ view.hiddenNodes }}<span v-if="view.truncated"> · {{ tr('已达到 500 节点显示上限，请搜索或使用邻域缩小范围。','500-node display limit reached. Search or narrow to a neighborhood.') }}</span></p>
        <OntologyGraphCanvas ref="canvas" :nodes="view.nodes" :edges="view.edges" :selected-node-id="selectedId" :selected-edge-id="selectedEdgeId" :highlighted-ids="view.matches" @select-node="select" @select-edge="selectEdge" />
        <details class="model-links"><summary>{{ tr('关系列表（支持键盘选择）','Relationship list (keyboard accessible)') }} · {{ edges.length }}</summary><button v-for="edge in edges" :key="edge.id" type="button" class="model-edge" :aria-pressed="selectedEdgeId===edge.id" @click="selectEdge(edge.id)">{{ labelById.get(edge.source) }} {{connector(edge.kind)}} {{ edge.label }} {{connector(edge.kind)}} {{ labelById.get(edge.target) }}</button><p v-if="!edges.length" class="model-note">{{ tr('当前范围没有可图示关联。','No projected links in this view.') }}</p></details>
        <el-button ref="inspectorToggle" class="model-mobile-details" @click="inspectorOpen=true">{{ tr('查看详情','View details') }}</el-button>
      </section>
      <aside class="model-inspector" :class="{'is-open': inspectorOpen}">
        <el-button class="model-mobile-details" @click="closeInspector">{{ tr('关闭详情','Close details') }}</el-button>
        <h3>{{ selected?.label || (selectedEdge ? tr('关联详情','Link definition') : tr('模型详情','Definition details')) }}</h3>
        <el-button v-if="selected && ['class','objectProperty','dataProperty'].includes(selected.kind) && !selectedDefinition?.imported && editable && apply" :disabled="disabled" @click="startDefinition">{{ tr('编辑信息','Edit information') }}</el-button>
        <el-button v-if="selected?.kind==='class' && !selectedDefinition?.imported && editable && apply" :disabled="disabled || projectionLoading || !projection" @click="startRestriction">{{ tr('编辑业务规则','Edit business rules') }}</el-button>
        <template v-if="selected"><el-tag size="small">{{ kindLabel(selected.kind) }}</el-tag><details class="model-identifier"><summary>{{ tr('系统标识','System identifier') }}</summary><code>{{ selected.iri }}</code></details></template>
        <p v-else-if="!selectedEdge" class="model-note">{{ tr('选择对象或关系，查看业务规则。','Select a definition, node or edge to inspect its rules.') }}</p>
        <details v-if="selectedDefinition?.features.length" class="model-help"><summary>{{tr('高级设置','Advanced settings')}}</summary><p class="model-features"><el-tag v-for="feature in selectedDefinition.features" :key="feature">{{featureLabel(feature)}}</el-tag></p><p v-if="selectedDefinition?.features.some(f=>f.includes('Functional'))" class="model-note">{{tr('OWL 函数语义不等于业务单值或必填规则。','OWL functionality is not a business single-value or required-field rule.')}}</p></details>
        <h4>{{ selected || selectedEdge ? tr('相关规则','Related rules') : tr('其他规则','Other rules') }} · {{ rules.length }}</h4>
        <details v-for="rule in rules" :key="rule.id" class="model-rule"><summary>{{ businessRuleLabel(projection,rule.axiomId,locale) }}</summary><p v-if="rule.imported">{{tr('固定导入（只读）','Pinned import (read only)')}} · {{rule.artifactId}}</p><el-button v-if="!rule.imported" link type="primary" @click="emit('inspect-axiom',rule.axiomId); inspectorOpen=false">{{ tr('查看参考资料','View references') }}</el-button></details>
        <el-button link type="primary" @click="emit('advanced')">{{ tr('更多操作','More actions') }}</el-button>
      </aside>
    </div>
    <details v-if="expressionRoots.length" class="model-expressions" aria-label="复杂表达式"><summary>{{tr('组合规则详情','Combined rules')}} · {{expressionRoots.length}}</summary>
      <h4>{{ tr('复杂表达式','Complex expressions') }} · {{ expressionRoots.length }}</h4>
      <p class="model-note">{{ tr('展开查看概念限制和属性链；这些结构只供查看，原公理与来源可随时追溯。','Expand concept restrictions and property chains. These structures are read only; original axioms and sources remain available.') }}</p>
      <div v-for="tree in expressionRoots" :key="tree.expression.id" class="model-expression-root">
        <OntologyExpressionTree :tree="tree" :language="locale" />
        <el-button link type="primary" class="model-expression-source" @click="emit('inspect-axiom',sourceAxiomId(tree.expression.axiomId))">{{ tr('查看原公理与来源','Inspect original axiom and source') }}</el-button>
      </div>
    </details>
    <details class="model-help"><summary>{{tr('模型解析详情','Model parsing details')}}</summary>
    <p v-if="projection" class="model-note model-footnote">{{tr('标准投影覆盖','Standard projection coverage')}} {{projection.coverage.returned}} / {{projection.coverage.total}} · {{tr('未完整图示','Not fully rendered')}} {{model.unprojectedAxiomIds.length}}<span v-if="projection.coverage.truncated"> · {{tr('投影已截断，请在高级视图查看完整文档。','Projection truncated. View the complete document in the advanced view.')}}</span><span v-if="projection.coverage.lockedImportCount"> · {{projection.coverage.lockedImportCount}} {{tr('项固定导入，默认折叠并保留来源。','pinned imports, collapsed with provenance.')}}</span></p>
    <details v-if="projection" class="model-coverage"><summary>{{tr('逐公理覆盖与来源','Axiom coverage and provenance')}}</summary><div v-for="item in projection.axiomRefs" :key="item.id"><strong>{{item.status}} · {{item.axiomType}}</strong><small>{{item.imported?tr('固定导入','Pinned import'):tr('当前文档','Root document')}} · {{item.artifactId}} · {{item.axiomId}}</small><p v-if="item.reason">{{item.reason}}</p><pre>{{item.rendering}}</pre><el-button v-if="!item.imported" link @click="emit('inspect-axiom',item.axiomId)">{{tr('查看参考资料','View references')}}</el-button></div></details>
    </details>
    <BusinessModelForm v-if="applyModel && editable && (formOpen || editingDefinition || editingRestriction)" :mode="formOpen?'create':editingRestriction?'rule':'edit'" :term-kind="form.kind==='Class'?'OBJECT':form.kind==='ObjectProperty'?'RELATION':'ATTRIBUTE'" :node="formOpen?undefined:editingNode" :nodes="model.nodes" :axioms="axioms" :projection="projection||restrictionProjection" :disabled="!!disabled||!!projectionLoading||!projection" :pending="editPending" :retry="retryEdit" :reload="reloadDraft" :failure-message="failureMessage" :apply="applyModel" @close="formOpen=false;editingDefinition=false;editingRestriction=false" />
    <OntologyDefinitionForm v-if="!applyModel && editingDefinition && editingNode && editable && apply" :node="editingNode" :nodes="model.nodes" :axioms="axioms" :disabled="!!disabled" :failure-message="failureMessage" :pending="editPending" :retry="retryEdit" :reload="reloadDraft" :apply="apply" @close="editingDefinition=false" />
    <OntologyRestrictionForm v-if="!applyModel && editingRestriction && editingNode && editable && apply && restrictionProjection" :node="editingNode" :nodes="model.nodes" :axioms="axioms" :projection="projection || restrictionProjection" :disabled="!!disabled || !!projectionLoading || !projection" :failure-message="failureMessage" :pending="editPending" :retry="retryEdit" :reload="reloadDraft" :apply="apply" @close="editingRestriction=false" />
    <el-dialog v-if="!applyModel" v-model="formOpen" :title="tr('新增定义','Add definition')" width="min(540px, 94vw)" :close-on-click-modal="!saving" :show-close="!saving">
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
.model-help{font-size:12px;color:var(--mc-text-secondary);margin:8px 0}.model-help>summary,.model-filters>summary{cursor:pointer;padding:6px 0}.model-filters{margin:12px 0;font-size:12px}.model-inspector>.el-button{margin:0 6px 8px 0}.model-coverage{overflow-wrap:anywhere;max-height:360px;overflow:auto;font-size:12px;margin-top:12px}.model-coverage small{display:block;overflow-wrap:anywhere}.model-coverage pre{white-space:pre-wrap;overflow-wrap:anywhere}.model-coverage>div{padding:12px;border-bottom:1px solid var(--mc-border)}.model-features{display:flex;gap:6px;flex-wrap:wrap}.model-expressions{margin-top:18px;min-width:0;padding:16px;border:1px solid var(--mc-border);border-radius:4px;overflow-wrap:anywhere}.model-expression-root{padding:12px 0;border-bottom:1px solid var(--mc-border);min-width:0}.model-expression-root:last-child{border-bottom:0}.model-expression-source-meta{display:block;color:var(--mc-text-secondary);font-size:11px;overflow-wrap:anywhere;margin:8px 0}.model-expression-source{max-width:100%;white-space:normal;text-align:left}.ontology-model{color:var(--mc-text-primary);min-width:0}.model-bar,.model-canvas-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.model-bar{padding:4px 0 12px}.model-count{margin-left:16px;font-size:12px;color:var(--mc-text-secondary)}.model-actions{display:flex;gap:8px;flex-wrap:wrap}.model-actions .el-button+.el-button{margin-left:0}.model-note{font-size:12px;line-height:1.7;color:var(--mc-text-secondary);margin:0 0 12px}.model-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 260px;border:1px solid var(--mc-border);border-radius:4px;min-height:480px;overflow:hidden}.model-directory,.model-inspector{padding:16px;min-width:0;background:var(--mc-bg-elevated)}.model-directory{border-right:1px solid var(--mc-border)}.model-directory label{display:block;font-size:13px;font-weight:600;margin-bottom:12px}.model-mode{margin:12px 0}.model-term-list{max-height:480px;overflow:auto}.model-term{display:flex;width:100%;text-align:left;align-items:center;gap:8px;justify-content:space-between;border:0;border-radius:4px;background:transparent;color:var(--mc-text-primary);padding:10px 8px;cursor:pointer}.model-term span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.model-term small{white-space:nowrap;color:var(--mc-text-secondary)}.model-term.active,.model-term:hover{background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.model-canvas-panel{min-width:0;background:var(--mc-bg-base,var(--mc-bg-elevated))}.model-canvas-bar{padding:10px 12px;border-bottom:1px solid var(--mc-border);font-size:13px}.model-inspector{border-left:1px solid var(--mc-border);max-height:555px;overflow:auto}.model-inspector h3{font-size:15px;margin:0 0 12px;overflow-wrap:anywhere}.model-inspector h4{font-size:13px;margin:20px 0 12px}.model-identifier{margin-top:12px}.model-identifier summary,.model-rule summary{cursor:pointer;font-size:12px;overflow-wrap:anywhere}.model-identifier code{display:block;font-size:11px;overflow-wrap:anywhere;margin-top:8px}.model-rule{padding:10px 0;border-bottom:1px solid var(--mc-border)}.model-rule pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;line-height:1.6}.model-footnote{margin:12px 0 0}.model-term:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}@media(max-width:1100px){.model-layout{grid-template-columns:190px minmax(0,1fr)}.model-inspector{grid-column:1/-1;border-left:0;border-top:1px solid var(--mc-border);max-height:260px}}@media(max-width:650px){.model-layout{grid-template-columns:minmax(0,1fr)}.model-directory{border-right:0;border-bottom:1px solid var(--mc-border)}.model-term-list{max-height:160px}.model-count{display:block;margin:6px 0 0}.model-inspector{display:none}.model-inspector.is-open{display:block;position:fixed;inset:auto 0 0;z-index:1900;max-height:70vh;box-shadow:0 -4px 24px #0002;padding:20px}.model-mobile-details{display:inline-flex}}
.model-mobile-details{display:none}.model-kind-filter,.model-edge-filter{font-size:12px;display:flex;gap:8px;align-items:center}.model-kind-filter{margin:8px 0}.model-kind-filter select,.model-edge-filter select{min-width:0;max-width:100%;padding:4px;border:1px solid var(--mc-border);border-radius:4px;background:var(--mc-bg-elevated);color:var(--mc-text-primary)}.model-links{padding:12px;border-top:1px solid var(--mc-border);max-height:180px;overflow:auto;font-size:12px}.model-links summary{cursor:pointer}.model-edge{display:block;width:100%;border:0;background:transparent;color:var(--mc-text-primary);text-align:left;padding:8px;overflow-wrap:anywhere;cursor:pointer}.model-edge:hover,.model-edge[aria-pressed=true]{background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.model-visibility{padding:8px 12px;margin:0}.model-edge:focus-visible{outline:2px solid var(--el-color-primary)}@media(max-width:650px){.model-mobile-details{display:inline-flex}}
</style>
