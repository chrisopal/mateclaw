<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import type { OntologyProjectionNode } from '../ontologyProjection'
import { definitionEdits, definitionFields, editableDefinition, type DefinitionField } from '../definitionEditing'
const props=defineProps<{node:OntologyProjectionNode;nodes:OntologyProjectionNode[];axioms:AxiomDescriptor[];disabled:boolean;reload?:()=>Promise<boolean>;pending?:boolean;retry?:()=>Promise<boolean>;failureMessage?:string;apply:(edits:AxiomEdit[])=>Promise<boolean>}>()
const emit=defineEmits<{close:[]}>()
const {locale}=useI18n();const tr=(zh:string,en:string)=>locale.value.startsWith('zh')?zh:en
const fieldLabel=(kind:DefinitionField)=>({label:tr('名称','Label'),comment:tr('描述','Description'),SubClassOf:tr('命名父类','Named superclass'),ObjectPropertyDomain:tr('关系定义域','Relation domain'),ObjectPropertyRange:tr('关系值域','Relation range'),DataPropertyDomain:tr('属性定义域','Attribute domain'),DataPropertyRange:tr('属性数据类型','Attribute datatype')}[kind])
const choices=computed(()=>props.axioms.flatMap(a=>{const value=editableDefinition(a,props.node.iri,props.node.kind);return value?[{axiom:a,...value}]:[]}))
const selected=ref('');const kind=ref<DefinitionField>('label');const value=ref('');const language=ref('');const error=ref('');const saving=ref(false);const preview=ref<AxiomEdit[]|null>(null);let baseline=''
const original=computed(()=>choices.value.find(a=>a.axiomId===selected.value))
const literal=computed(()=>kind.value==='label'||kind.value==='comment')
const targets=computed(()=>props.nodes.filter(n=>n.kind===(kind.value==='DataPropertyRange'?'datatype':'class')))
function reset(){const old=original.value;kind.value=old?.kind??'label';value.value=old?.value??'';language.value=old?.language??'';preview.value=null;error.value=''}
watch(selected,reset)
watch([kind,value,language],()=>{preview.value=null;error.value=''})
function prepare(){
 if(props.disabled||saving.value)return
 try {
  if(selected.value&&!original.value)throw Error(tr('原公理已变化，请重新选择并核对。','The original axiom changed. Select and review again.'))
  preview.value=definitionEdits(props.node.iri,kind.value,value.value,language.value,original.value?.axiom)
  if(!preview.value.length)throw Error(tr('内容没有变化。','No changes.'))
  if(choices.value.some(item=>item.axiomId!==selected.value && item.kind===kind.value && !definitionEdits(props.node.iri,kind.value,value.value,language.value,item.axiom).length))throw Error(tr('该公理已经存在。','This axiom already exists.'))
  baseline=JSON.stringify(props.axioms);error.value=''
 }catch(e){preview.value=null;error.value=(e as Error).message}
}
async function loadLatest(){
 if(!props.reload||props.pending||saving.value)return
 saving.value=true
 try {if(await props.reload()){preview.value=null;error.value=tr('已载入最新本体，输入仍保留。请重新选择目标公理并核对后预览。','Latest ontology loaded; inputs retained. Reselect the axiom and review before previewing.')}}finally{saving.value=false}
}
async function recover(){
 if(!props.pending||!props.retry||saving.value)return
 saving.value=true;error.value=''
 try {if(await props.retry())emit('close');else error.value=props.failureMessage||tr('恢复尚未完成，请检查错误后重试。','Recovery not completed. Check the error before retrying.')}finally{saving.value=false}
}
async function save(){
 if(props.disabled||saving.value||!preview.value)return
 if(baseline!==JSON.stringify(props.axioms)){preview.value=null;error.value=tr('本体已更新，请重新预览并核对。','The ontology changed. Preview and review again.');return}
 saving.value=true;error.value=''
 try{if(await props.apply(preview.value))emit('close');else { if(!props.pending)preview.value=null; error.value=props.failureMessage||tr('未保存，输入已保留。请检查页面错误；并发冲突须重新载入后核对，不要直接重放。','Not saved. Inputs retained. Review page errors and reload after a conflict before retrying.')} }catch(e){error.value=(e as Error).message}finally{saving.value=false}
}
</script>
<template>
 <el-dialog :model-value="true" :title="tr('编辑定义','Edit definition')" width="min(620px, 94vw)" :close-on-click-modal="!saving && !disabled" :close-on-press-escape="!saving && !disabled" :show-close="!saving && !disabled" @update:model-value="!$event && emit('close')">
  <p class="definition-identity">{{node.label}} · <code>{{node.iri}}</code></p>
  <p class="definition-help">{{tr('IRI 保持不变；名称和描述按 IRI 共享。多条定义逐条编辑，复杂或带注释的公理请在高级视图中修改。','IRI stays unchanged; labels and comments are shared by IRI. Edit each axiom separately; use the advanced view for complex or annotated axioms.')}}</p>
<el-alert v-if="pending" type="warning" :closable="false" :title="tr('保存结果尚未确定，输入已冻结。请恢复原请求。','Save outcome is unknown. Inputs are frozen; recover the original request.')" /><el-button v-if="pending && retry" :loading="saving" @click="recover">{{tr('恢复本次保存','Recover this save')}}</el-button>
  <form class="definition-form" @submit.prevent="prepare">
   <label>{{tr('编辑项目','Edit item')}}<select v-model="selected" :disabled="saving||disabled" :aria-label="tr('编辑项目','Edit item')"><option value="">{{tr('新增一条定义','Add one definition')}}</option><option v-for="item in choices" :key="item.axiomId" :value="item.axiomId">{{fieldLabel(item.kind)}} · {{item.value}} {{item.language?`@${item.language}`:''}}</option></select></label>
   <label>{{tr('定义内容','Definition field')}}<select v-model="kind" :disabled="!!selected||saving||disabled" :aria-label="tr('定义内容','Definition field')"><option v-for="field in definitionFields(node.kind)" :key="field" :value="field">{{fieldLabel(field)}}</option></select></label>
   <template v-if="literal"><label>{{tr('内容','Value')}}<input v-model="value" :aria-label="tr('内容','Value')" :disabled="saving||disabled" /></label><label>{{tr('语言标签','Language tag')}}<input v-model="language" :aria-label="tr('语言标签','Language tag')" placeholder="zh-CN / en / 留空" :disabled="saving||disabled" /></label></template>
   <label v-else>{{tr('目标 IRI','Target IRI')}}<input v-model="value" :aria-label="tr('目标 IRI','Target IRI')" :disabled="saving||disabled" placeholder="urn:example:Concept" /><select :value="targets.some(n=>n.iri===value)?value:''" :aria-label="tr('选择已有目标','Choose existing target')" :disabled="saving||disabled" @change="value=($event.target as HTMLSelectElement).value"><option value="" disabled>{{tr('选择已有定义，或直接输入完整 IRI','Choose an existing definition or enter an absolute IRI')}}</option><option v-for="n in targets" :key="n.id" :value="n.iri">{{n.label}} · {{n.iri}}</option></select></label>
   <p v-if="original" class="definition-help">{{tr('原公理','Original axiom')}}<code>{{original.axiom.rendering}}</code></p>
   <el-alert v-if="error" :title="error" type="error" :closable="false" />
   <el-button v-if="error && props.reload && !pending" :disabled="saving||disabled" @click="loadLatest">{{tr('载入最新本体并核对','Load latest ontology for review')}}</el-button>
   <div class="definition-actions"><el-button native-type="submit" :disabled="saving||disabled">{{tr('预览变更','Preview changes')}}</el-button><el-button :disabled="saving||disabled" @click="reset">{{tr('重置输入','Reset input')}}</el-button><el-button :disabled="saving||disabled" @click="emit('close')">{{tr('取消','Cancel')}}</el-button></div>
  </form>
  <section v-if="preview" class="definition-preview" aria-live="polite"><h3>{{tr('待保存的公理变更','Axiom changes to save')}}</h3><div v-for="(edit,i) in preview" :key="i"><strong>{{edit.kind}}</strong><pre>{{edit.functionalSyntax || original?.axiom.rendering}}</pre></div><p>{{tr('仅修改以上公理。替换会生成新公理标识，旧来源绑定不会自动移植；保存后请核对来源依据并重新校验草稿。','Only these axioms change. Replacement creates new axiom identities; source bindings are not automatically transferred. Review evidence and validate the draft after saving.')}}</p><el-button type="primary" :loading="saving" :disabled="disabled||saving" @click="save">{{tr('确认保存定义','Confirm definition changes')}}</el-button></section>
 </el-dialog>
</template>
<style scoped>
.definition-identity,.definition-help{overflow-wrap:anywhere}.definition-help{font-size:12px;color:var(--mc-text-secondary);line-height:1.6}.definition-help code{display:block}.definition-form{display:grid;gap:14px}.definition-form label{display:grid;gap:6px;font-size:13px}.definition-form input,.definition-form select{width:100%;min-width:0;box-sizing:border-box;padding:8px;border:1px solid var(--mc-border);border-radius:4px;background:var(--mc-bg-elevated);color:var(--mc-text-primary)}.definition-actions{display:flex;gap:8px;flex-wrap:wrap}.definition-preview{border-top:1px solid var(--mc-border);margin-top:16px;padding-top:12px}.definition-preview pre{white-space:pre-wrap;overflow-wrap:anywhere}.definition-preview p{font-size:12px;line-height:1.6}
</style>
