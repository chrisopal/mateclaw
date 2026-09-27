<template>
  <section class="candidate-compare">
    <header><div><h3>{{ l('候选对比','Candidate comparison') }}</h3><el-tag effect="plain">{{ statusLabel(candidate.status) }}</el-tag><el-tag v-if="selected" type="success" effect="plain">{{ l('当前选定','Selected') }}</el-tag></div><div class="actions"><el-button type="primary" plain :disabled="!canRevise || busy" @click="$emit('revise')">{{ l('继续修订','Continue editing') }}</el-button><el-button type="primary" :disabled="!adoptable || busy" :loading="busy" @click="$emit('adopt')">{{ l('采用此候选','Adopt candidate') }}</el-button></div></header>
    <div class="input-version"><strong>{{ l('本次输入版本','Input versions') }}</strong><span>{{ inputVersions || '—' }}</span></div>
    <section><h4>{{ l('本次内容','Candidate content') }}</h4><div class="content-scroll"><template v-if="blocks.length"><article v-for="(block,index) in blocks" :key="index"><h5 v-if="block.type==='heading'">{{ block.text }}</h5><p v-else-if="block.type==='paragraph'">{{ block.text }}</p><ol v-else-if="block.type==='list' && block.ordered"><li v-for="(item,itemIndex) in block.items" :key="itemIndex">{{ item }}</li></ol><ul v-else-if="block.type==='list'"><li v-for="(item,itemIndex) in block.items" :key="itemIndex">{{ item }}</li></ul><div v-else-if="block.type==='table'" class="table-preview"><strong>{{ (block.columns||[]).join(' · ') }}</strong><p v-for="(row,rowIndex) in block.rows||[]" :key="rowIndex">{{ row.join(' · ') }}</p><el-tag size="small" effect="plain">{{ l('表格预览','Table preview') }}</el-tag></div><p v-else>—</p></article></template><el-empty v-else :description="l('此候选没有正文内容','No chapter content in this candidate')" /></div></section>
    <div class="coverage"><section><h4>{{ l('缺少材料','Missing materials') }}</h4><ul v-if="strings(candidate.payload.missingMaterials).length"><li v-for="(item,index) in strings(candidate.payload.missingMaterials)" :key="index">{{ item }}</li></ul><p v-else>—</p></section><section><h4>{{ l('未解决事项','Unresolved items') }}</h4><ul v-if="strings(candidate.payload.unresolvedItems).length"><li v-for="(item,index) in strings(candidate.payload.unresolvedItems)" :key="index">{{ item }}</li></ul><p v-else>—</p></section></div>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ChapterRevision } from '../api/types'
import { canAdopt } from '../shared/state'
const props=defineProps<{candidate:ChapterRevision;selected?:boolean;currentVersion:number|string;expectedVersion:number|string;currentRef?:import('../api/types').Ref;canRevise:boolean;busy?:boolean}>()
defineEmits<{adopt:[];revise:[]}>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const adoptable=computed(()=>props.candidate.status==='CANDIDATE'&&canAdopt(props.candidate.status,props.currentVersion,props.expectedVersion)&&sameRef(props.currentRef,props.candidate.headGuard))
function sameRef(left?:import('../api/types').Ref,right?:import('../api/types').Ref){return !!left&&!!right&&left.kind===right.kind&&String(left.id)===String(right.id)&&left.digest===right.digest&&Number(left.version)===Number(right.version)}
const blocks=computed(()=>{const chapter=props.candidate.payload.chapter as {blocks?:Record<string,any>[]} | undefined;return Array.isArray(chapter?.blocks)?chapter.blocks:[]})
const inputVersions=computed(()=>props.candidate.inputRefs.map(item=>`V${item.version}`).join(' · '))
function strings(value:unknown):string[]{return Array.isArray(value)?value.map(item=>typeof item==='string'?item:summary(item as Record<string,unknown>)).filter(item=>!!item&&item!=='—'):[]}
function summary(value:Record<string,unknown>){return [value.title,value.text,value.description,value.reason].filter(item=>typeof item==='string'&&item.trim()).join(' · ')||'—'}
function statusLabel(value:string){return value==='CANDIDATE'?l('候选','Candidate'):value==='STALE'?l('依赖已变化','Inputs changed'):value==='ADOPTED'?l('已采用','Adopted'):l('不可用','Unavailable')}
</script>
<style scoped>
.candidate-compare{display:grid;gap:14px;min-width:0}.candidate-compare>header,.candidate-compare>header>div{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.candidate-compare>header{justify-content:space-between}.candidate-compare h3{margin:0 8px 0 0;font-size:15px}.candidate-compare h4{margin:0 0 8px;font-size:13px}.actions{display:flex;gap:8px}.input-version{display:flex;gap:12px;align-items:center;color:var(--mc-text-secondary);font-size:13px;overflow-wrap:anywhere}.input-version strong{color:var(--mc-text-primary)}.content-scroll{max-height:430px;overflow:auto;border:1px solid var(--mc-border-light);border-radius:4px;padding:12px}.content-scroll article{margin-bottom:12px;line-height:1.65}.content-scroll h5{margin:0;font-size:15px}.content-scroll p{margin:4px 0;white-space:pre-wrap;overflow-wrap:anywhere}.table-preview{border:1px solid var(--mc-border);padding:8px;margin:8px 0}.coverage{display:grid;grid-template-columns:1fr 1fr;gap:16px}.coverage ul{margin:0;padding-left:20px}.coverage p{margin:0;color:var(--mc-text-secondary)}.el-button:focus-visible{outline:2px solid var(--mc-primary);outline-offset:2px}@media(max-width:680px){.candidate-compare>header{align-items:flex-start}.coverage{grid-template-columns:1fr}}
</style>
