<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor } from '../../api/types'
import type { OntologyProjectionNode } from '../ontologyProjection'
import type { DisplayProjection } from '../standardProjection'
import type { ModelChange } from '../businessModel'
import { businessRuleLabel } from '../businessModel'
import { editableDefinition, definitionFields, type DefinitionField } from '../definitionEditing'
import { editableRestrictions } from '../restrictionEditing'
const props = defineProps<{mode:'create'|'edit'|'rule';termKind?:'OBJECT'|'RELATION'|'ATTRIBUTE';node?:OntologyProjectionNode;nodes:OntologyProjectionNode[];axioms:AxiomDescriptor[];projection?:DisplayProjection|null;disabled:boolean;pending?:boolean;retry?:()=>Promise<boolean>;reload?:()=>Promise<boolean>;failureMessage?:string;apply:(changes:ModelChange[])=>Promise<boolean>}>()
const emit=defineEmits<{close:[]}>()
const {locale}=useI18n();const tr=(zh:string,en:string)=>locale.value.startsWith('zh')?zh:en
const title=computed(()=>props.mode==='rule'?tr('编辑业务规则','Edit business rule'):props.mode==='edit'?tr('编辑信息','Edit information'):({OBJECT:tr('新增业务对象','New business object'),RELATION:tr('新增对象关系','New relation'),ATTRIBUTE:tr('新增业务属性','New attribute')}[props.termKind??'OBJECT']))
const objects=computed(()=>props.nodes.filter(n=>n.kind==='class'))
const relations=computed(()=>props.nodes.filter(n=>n.kind==='objectProperty'))
const dataTypes=[['string','文本','Text'],['decimal','数字','Number'],['integer','整数','Integer'],['boolean','是／否','Yes / no'],['dateTime','日期时间','Date and time']]
const selected=ref(''),name=ref(''),value=ref(''),language=ref(''),domain=ref(''),range=ref(''),property=ref(''),filler=ref(''),count=ref(''),field=ref<DefinitionField>('label'),operator=ref<'SOME'|'ALL'|'MIN'|'MAX'|'EXACT'>('SOME')
const operatorMap={ObjectSomeValuesFrom:'SOME',ObjectAllValuesFrom:'ALL',ObjectMinCardinality:'MIN',ObjectMaxCardinality:'MAX',ObjectExactCardinality:'EXACT'} as const
const operatorNames=computed(()=>({SOME:tr('至少关联一个','At least one'),ALL:tr('关联对象均为','All related objects are'),MIN:tr('至少关联指定数量','At least this many'),MAX:tr('最多关联指定数量','At most this many'),EXACT:tr('恰好关联指定数量','Exactly this many')}))
const fieldName=(f:DefinitionField)=>({label:tr('名称','Name'),comment:tr('说明','Description'),SubClassOf:tr('所属分类','Parent category'),ObjectPropertyDomain:tr('起点对象','Source object'),ObjectPropertyRange:tr('终点对象','Target object'),DataPropertyDomain:tr('所属对象','Owner'),DataPropertyRange:tr('内容类型','Content type')}[f])
const definitions=computed(()=>props.node?props.axioms.flatMap(a=>{const v=editableDefinition(a,props.node!.iri,props.node!.kind);return v?[v]:[]}):[])
const rules=computed(()=>editableRestrictions(props.projection,props.axioms,props.node?.id??''))
const original=computed(()=>props.mode==='rule'?rules.value.find(r=>r.axiom.axiomId===selected.value):definitions.value.find(d=>d.axiomId===selected.value))
const literal=computed(()=>field.value==='label'||field.value==='comment')
const numeric=computed(()=>['MIN','MAX','EXACT'].includes(operator.value))
const saving=ref(false),error=ref(''),preview=ref<ModelChange|null>(null),previewText=ref('')
const locked=computed(()=>props.disabled||props.pending||saving.value)
let baseline=''
const snapshot=()=>JSON.stringify([props.axioms,props.projection?.documentDigest])
const label=(id:string)=>props.nodes.find(n=>n.iri===id)?.label??tr('所选对象','Selected object')
function reset(){
 error.value='';preview.value=null
 if(props.mode==='rule') {const old=rules.value.find(r=>r.axiom.axiomId===selected.value)?.input;operator.value=old?operatorMap[old.operator]:'SOME';property.value=old?.property.replace(/^objectProperty:/,'')??'';filler.value=old?.filler.replace(/^class:/,'')??'';count.value=old?.cardinality??''}
 else {const old=definitions.value.find(d=>d.axiomId===selected.value);field.value=old?.kind??'label';value.value=old?.value??'';language.value=old?.language??'';name.value='';domain.value='';range.value=''}
}
watch(selected,reset)
watch([name,value,language,domain,range,property,filler,count,field,operator],()=>{preview.value=null;error.value='';if(!numeric.value)count.value=''})
function prepare(){
 if(locked.value)return
 error.value=''
 if(selected.value&&!original.value){error.value=tr('这条内容已更新，请重新选择。','This item changed. Select it again.');return}
 let change:ModelChange
 if(props.mode==='create'){
  if(!name.value.trim()||(props.termKind!=='OBJECT'&&(!domain.value||!range.value))){error.value=tr('请填写名称并选择关联对象或内容类型。','Enter a name and choose the related objects or content type.');return}
  change={kind:'CREATE_TERM',termKind:props.termKind??'OBJECT',name:name.value.trim(),...(props.termKind!=='OBJECT'?{domainId:domain.value,rangeId:range.value}:{})}
  previewText.value=[title.value,name.value.trim(),domain.value?label(domain.value):'',props.termKind==='RELATION'?label(range.value):''].filter(Boolean).join(' · ')
 }else if(props.mode==='rule'){
  if(!property.value||!filler.value||(numeric.value&&(!/^(0|[1-9]\d*)$/.test(count.value)||Number(count.value)>2147483647))){error.value=tr('请选择关系和对象；数量须为非负整数。','Choose a relation and object; enter a nonnegative whole number.');return}
  const same=rules.value.find(r=>operatorMap[r.input.operator]===operator.value&&r.input.property==='objectProperty:'+property.value&&r.input.filler==='class:'+filler.value&&r.input.cardinality===(numeric.value?count.value:''))
  if(same){error.value=tr(same.axiom.axiomId===selected.value?'内容没有变化。':'已有相同规则。',same.axiom.axiomId===selected.value?'No changes.':'This rule already exists.');return}
  change={kind:'REPLACE_RESTRICTION',targetId:props.node!.iri,operator:operator.value,propertyId:property.value,fillerId:filler.value,...(numeric.value?{cardinality:Number(count.value)}:{})}
  previewText.value=`${props.node!.label} · ${label(property.value)} · ${operatorNames.value[operator.value]} ${numeric.value?count.value+' · ':''}${label(filler.value)}`
 }else{
  if(!value.value.trim()){error.value=tr('请填写或选择内容。','Enter or choose a value.');return}
  if(definitions.value.some(d=>d.kind===field.value&&d.value===value.value&&d.language===language.value)){error.value=tr('内容没有变化或已存在。','No changes, or this value already exists.');return}
  const fields={label:'NAME',comment:'DESCRIPTION',SubClassOf:'PARENT',ObjectPropertyDomain:'DOMAIN',ObjectPropertyRange:'RANGE',DataPropertyDomain:'DOMAIN',DataPropertyRange:'RANGE'} as const
  change={kind:'REPLACE_DEFINITION',targetId:props.node!.iri,termKind:props.node!.kind==='class'?'OBJECT':props.node!.kind==='objectProperty'?'RELATION':'ATTRIBUTE',field:fields[field.value],value:value.value,language:language.value}
  previewText.value=`${props.node!.label} · ${fieldName(field.value)}：${literal.value?value.value:field.value==='DataPropertyRange'?tr('所选内容类型','Selected content type'):label(value.value)}`
 }
 if(selected.value)change.originalAxiomId=selected.value
 baseline=snapshot();preview.value=change
}
async function save(){if(locked.value||!preview.value)return;if(baseline!==snapshot()){preview.value=null;error.value=tr('模型已更新，请重新预览。','Model changed. Preview again.');return}saving.value=true;try{if(await props.apply([preview.value]))emit('close');else{error.value=props.failureMessage||tr('保存未完成，输入已保留。','Save incomplete; input retained.');if(!props.pending)preview.value=null}}finally{saving.value=false}}
async function reloadModel(){if(!props.reload||saving.value||props.pending)return;saving.value=true;try{await props.reload();preview.value=null;error.value=tr('已更新模型，请核对输入后重新预览。','Model refreshed. Review input and preview again.')}finally{saving.value=false}}
async function recoverSave(){if(!props.retry||saving.value)return;saving.value=true;try{if(await props.retry())emit('close')}finally{saving.value=false}}
</script>
<template>
 <el-dialog :model-value="true" :title="title" width="min(560px,94vw)" :close-on-click-modal="!locked" :close-on-press-escape="!locked" :show-close="!locked" @update:model-value="!$event&&emit('close')">
  <p v-if="node" class="business-form-context">{{node.label}}</p>
  <el-alert v-if="pending" :title="tr('正在确认上次保存结果，请恢复保存。','Recover the previous save before continuing.')" type="warning" :closable="false"/><el-button v-if="pending" :loading="saving" @click="recoverSave">{{tr('恢复保存','Recover save')}}</el-button>
  <form class="business-form" @submit.prevent="prepare">
   <template v-if="mode==='create'">
    <label>{{tr('名称','Name')}}<input v-model="name" :disabled="locked" :aria-label="tr('名称','Name')" maxlength="128"/></label>
    <template v-if="termKind!=='OBJECT'"><label>{{tr('所属对象','Owner object')}}<select v-model="domain" :disabled="locked" :aria-label="tr('所属对象','Owner object')"><option value="" disabled>{{tr('选择对象','Choose object')}}</option><option v-for="n in objects" :key="n.id" :value="n.iri">{{n.label}}</option></select></label>
    <label v-if="termKind==='RELATION'">{{tr('关联对象','Related object')}}<select v-model="range" :disabled="locked" :aria-label="tr('关联对象','Related object')"><option value="" disabled>{{tr('选择对象','Choose object')}}</option><option v-for="n in objects" :key="n.id" :value="n.iri">{{n.label}}</option></select></label>
    <label v-else>{{tr('内容类型','Content type')}}<select v-model="range" :disabled="locked" :aria-label="tr('内容类型','Content type')"><option value="" disabled>{{tr('选择类型','Choose type')}}</option><option v-for="d in dataTypes" :key="d[0]" :value="'http://www.w3.org/2001/XMLSchema#'+d[0]">{{tr(d[1]!,d[2]!)}}</option></select></label></template>
   </template>
   <template v-else-if="mode==='edit'">
    <label>{{tr('编辑内容','Edit item')}}<select v-model="selected" :disabled="locked" :aria-label="tr('编辑内容','Edit item')"><option value="">{{tr('补充信息','Add information')}}</option><option v-for="d in definitions" :key="d.axiomId" :value="d.axiomId">{{fieldName(d.kind)}} · {{['label','comment'].includes(d.kind)?d.value:d.kind==='DataPropertyRange'?tr('内容类型','Content type'):label(d.value)}}</option></select></label>
    <label>{{tr('信息类型','Information type')}}<select v-model="field" :disabled="locked||!!selected" :aria-label="tr('信息类型','Information type')"><option v-for="f in definitionFields(node!.kind)" :key="f" :value="f">{{fieldName(f)}}</option></select></label>
    <label v-if="literal">{{tr('内容','Value')}}<input v-model="value" :disabled="locked" :aria-label="tr('内容','Value')"/></label>
    <label v-else-if="field==='DataPropertyRange'">{{tr('内容类型','Content type')}}<select v-model="value" :disabled="locked" :aria-label="tr('内容类型','Content type')"><option value="" disabled>{{tr('选择类型','Choose type')}}</option><option v-for="d in dataTypes" :key="d[0]" :value="'http://www.w3.org/2001/XMLSchema#'+d[0]">{{tr(d[1]!,d[2]!)}}</option></select></label>
    <label v-else>{{tr('关联对象','Related object')}}<select v-model="value" :disabled="locked" :aria-label="tr('关联对象','Related object')"><option value="" disabled>{{tr('选择对象','Choose object')}}</option><option v-for="n in objects" :key="n.id" :value="n.iri">{{n.label}}</option></select></label>
   </template>
   <template v-else>
    <label>{{tr('编辑规则','Edit rule')}}<select v-model="selected" :disabled="locked" :aria-label="tr('编辑规则','Edit rule')"><option value="">{{tr('新增规则','Add rule')}}</option><option v-for="r in rules" :key="r.axiom.axiomId" :value="r.axiom.axiomId">{{businessRuleLabel(projection,r.axiom.axiomId,locale)}}</option></select></label>
    <label>{{tr('对象关系','Relation')}}<select v-model="property" :disabled="locked" :aria-label="tr('对象关系','Relation')"><option value="" disabled>{{tr('选择关系','Choose relation')}}</option><option v-for="n in relations" :key="n.id" :value="n.iri">{{n.label}}</option></select></label>
    <label>{{tr('关联要求','Relationship requirement')}}<select v-model="operator" :disabled="locked" :aria-label="tr('关联要求','Relationship requirement')"><option v-for="(text,key) in operatorNames" :key="key" :value="key">{{text}}</option></select></label>
    <label v-if="numeric">{{tr('数量','Count')}}<input v-model="count" type="number" min="0" step="1" max="2147483647" :disabled="locked" :aria-label="tr('数量','Count')"/></label>
    <label>{{tr('关联对象','Related object')}}<select v-model="filler" :disabled="locked" :aria-label="tr('关联对象','Related object')"><option value="" disabled>{{tr('选择对象','Choose object')}}</option><option v-for="n in objects" :key="n.id" :value="n.iri">{{n.label}}</option></select></label>
    <small v-if="operator==='ALL'">{{tr('不要求关系必须存在。','Does not require the relation to exist.')}}</small>
   </template>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <el-button v-if="error&&props.reload&&!pending" @click="reloadModel">{{tr('载入最新模型','Reload model')}}</el-button>
   <div class="business-form-actions"><el-button :disabled="locked" @click="emit('close')">{{tr('取消','Cancel')}}</el-button><el-button :disabled="locked" @click="reset">{{tr('重置','Reset')}}</el-button><el-button native-type="submit" :disabled="locked">{{tr('预览变更','Preview changes')}}</el-button></div>
  </form>
  <section v-if="preview" class="business-form-preview"><strong>{{tr('变更确认','Review change')}}</strong><p>{{previewText}}</p><small v-if="selected">{{tr('修改后需重新确认参考资料。','Review references after changing this item.')}}</small><el-button type="primary" :disabled="locked" :loading="saving" @click="save">{{tr('确认保存','Confirm save')}}</el-button></section>
 </el-dialog>
</template>
<style scoped>
.business-form{display:grid;gap:16px}.business-form label{display:grid;gap:8px;font-size:13px}.business-form input,.business-form select{box-sizing:border-box;min-width:0;width:100%;height:36px;padding:6px 10px;border:1px solid var(--mc-border);border-radius:4px;background:var(--mc-bg-elevated);color:var(--mc-text-primary)}.business-form-context{font-weight:600;margin:0 0 20px}.business-form-actions{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px}.business-form-actions .el-button+.el-button{margin-left:0}.business-form-preview{margin-top:20px;border-top:1px solid var(--mc-border);padding-top:16px;overflow-wrap:anywhere}.business-form-preview small{display:block;margin-bottom:12px}.business-form small{color:var(--mc-text-secondary)}
</style>
