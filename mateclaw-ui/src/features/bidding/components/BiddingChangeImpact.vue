<template>
  <section class="change-impact" aria-labelledby="change-impact-title">
    <header class="impact-heading">
      <div>
        <h2 id="change-impact-title">{{ l('版本变更影响','Version change impact') }}</h2>
        <span>{{ view.formalBlocked ? l('正式流程暂不可继续','Formal progress is blocked') : l('影响已核对','Impact reviewed') }}</span>
      </div>
      <el-button type="primary" plain :disabled="busy" @click="$emit('refresh')">{{ l('刷新影响','Refresh impact') }}</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-else-if="view.formalBlocked" :title="l('存在待核对的版本变更，影响未确认前不能继续正式流程。','A version change still needs review. Formal progress remains blocked until its impact is proven and confirmed.')" type="warning" :closable="false" />
    <el-empty v-if="!events.length" :description="l('暂无版本变更事件','No version change events')" />
    <article v-for="event in events" :key="event.eventId" class="impact-event">
      <header class="event-heading">
        <div><h3>{{ labelFor(event.changedRef,event) }}</h3><el-tag effect="plain">{{ statusLabel(event.status) }}</el-tag></div>
        <span v-if="event.replacementRef" class="replacement">{{ l('替换为','Replaced by') }}：{{ labelFor(event.replacementRef,event) }}</span>
      </header>
      <div class="impact-columns">
        <section><h4>{{ l('受影响内容','Affected content') }} · {{ refs(event.impact?.affectedRefs).length }}</h4><ul v-if="refs(event.impact?.affectedRefs).length"><li v-for="ref in refs(event.impact?.affectedRefs)" :key="refKey(ref)">{{ labelFor(ref,event) }}</li></ul><p v-else>—</p></section>
        <section><h4>{{ l('已证明未受影响','Proven unaffected') }} · {{ refs(event.impact?.unaffectedRefs).length }}</h4><ul v-if="refs(event.impact?.unaffectedRefs).length"><li v-for="ref in refs(event.impact?.unaffectedRefs)" :key="refKey(ref)">{{ labelFor(ref,event) }}</li></ul><p v-else>—</p></section>
        <section><h4>{{ l('尚无法确认','Unknown impact') }} · {{ refs(event.impact?.unknownRefs).length }}</h4><ul v-if="refs(event.impact?.unknownRefs).length"><li v-for="ref in refs(event.impact?.unknownRefs)" :key="refKey(ref)">{{ labelFor(ref,event) }}</li></ul><p v-else>—</p></section>
      </div>
      <div v-if="eventCanConfirm(event)" class="event-actions"><el-button type="primary" plain :disabled="busy" @click="$emit('confirm',event.confirmation!.payload!)">{{ l('确认影响并应用替换','Confirm impact and apply replacement') }}</el-button></div>
      <p v-if="!eventCanConfirm(event)&&(event.status!=='CONFIRMED'||event.impact?.formalBlocked)" class="event-blocked">{{ l('服务端尚不能验证该变更影响，因此暂不可确认。','The service cannot yet verify this change impact, so confirmation is unavailable.') }}</p>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ChangeImpactConfirmationPayload, ChangeImpactEvent, ChangeImpactView, Ref } from '../api/types'

const props=defineProps<{view:ChangeImpactView;busy?:boolean;error?:string;canConfirm?:boolean}>()
defineEmits<{refresh:[];confirm:[payload:ChangeImpactConfirmationPayload]}>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const events=computed(()=>Array.isArray(props.view.events)?props.view.events:[])
const error=computed(()=>props.error||'')
function refs(values:unknown):Ref[]{return Array.isArray(values)?values.filter((value):value is Ref=>!!value&&typeof value==='object'&&typeof (value as Ref).kind==='string'&&(typeof (value as Ref).id==='string'||typeof (value as Ref).id==='number')&&(typeof (value as Ref).version==='string'||typeof (value as Ref).version==='number')&&typeof (value as Ref).digest==='string'):[]}
function refKey(ref:Ref){return String(ref.kind)+':'+String(ref.id)+':'+String(ref.version)+':'+ref.digest}
function labelFor(ref:Ref|null|undefined,event:ChangeImpactEvent){if(!ref||typeof ref.kind!=='string')return l('待核对内容','Content under review');const label=[...(event.refLabels||[]),...(event.impact?.refLabels||[])].find(item=>item?.ref&&refKey(item.ref)===refKey(ref))?.title;if(label)return label;const kind=ref.kind==='source'?l('招标来源','Tender source'):ref.kind==='sourceSet'?l('生效来源集','Active source set'):ref.kind==='analysisBaseline'?l('解析基线','Analysis baseline'):ref.kind==='outline'?l('技术目录','Technical outline'):ref.kind==='chapter'?l('技术章节','Technical chapter'):ref.kind==='manuscript'?l('整本正文','Manuscript'):ref.kind==='material'?l('授权材料','Authorized material'):l('待核对内容','Content under review');return kind+' · V'+String(ref.version)}
function eventCanConfirm(event:ChangeImpactEvent){return props.canConfirm!==false&&event.status==='PENDING'&&event.confirmation?.ready===true&&!!event.confirmation.payload&&Array.isArray(event.impact?.unknownRefs)&&event.impact.unknownRefs.length===0}
function statusLabel(status:string){return status==='CONFIRMED'?l('已确认','Confirmed'):status==='PENDING'?l('待核对','Pending review'):l('待处理','Needs review')}
</script>

<style scoped>
.change-impact{display:grid;gap:12px;margin:0 0 20px;min-width:0}.impact-heading,.impact-heading>div,.event-heading,.event-heading>div{display:flex;align-items:center;gap:8px}.impact-heading,.event-heading{justify-content:space-between}.impact-heading h2{margin:0;font-size:16px}.impact-heading span,.replacement{color:var(--mc-text-secondary);font-size:13px}.impact-event{display:grid;gap:12px;min-width:0;padding:14px;border:1px solid var(--mc-border);border-radius:6px;background:var(--mc-bg-elevated)}.event-heading h3{margin:0;font-size:14px}.impact-columns{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.impact-columns section{min-width:0}.impact-columns h4{margin:0 0 6px;font-size:13px}.impact-columns ul{margin:0;padding-left:18px}.impact-columns li{overflow-wrap:anywhere}.impact-columns p{margin:0;color:var(--mc-text-secondary)}.event-actions{display:flex;justify-content:flex-start}.event-blocked{margin:0;color:var(--mc-text-secondary);font-size:13px}.el-button:focus-visible{outline:2px solid var(--mc-primary);outline-offset:2px}@media(max-width:760px){.impact-heading{align-items:flex-start}.event-heading{align-items:flex-start;flex-direction:column}.impact-columns{grid-template-columns:1fr}}
</style>
