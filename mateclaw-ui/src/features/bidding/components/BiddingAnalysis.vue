<template>
  <section class="analysis">
    <header class="analysis-heading"><div><h2>{{ l('解析结果', 'Analysis results') }}</h2><span v-if="baseline" class="baseline">{{ l('已确认基线', 'Confirmed baseline') }} V{{ baseline.ref.version }}</span></div><div class="actions"><el-button v-if="canApprove && activeGroup?.complete" type="primary" :disabled="hasConflicts || !!baseline" @click="$emit('confirm', activeGroup.taskGroupId)">{{ l('确认解析基线', 'Confirm analysis baseline') }}</el-button><el-button v-else-if="!canApprove" disabled>{{ l('只读访问', 'Read only') }}</el-button></div></header>
    <el-alert v-if="!groups.length && !baseline" :title="l('尚无解析结果。先确认文件来源并开始解析。', 'No analysis results. Confirm sources and start analysis first.')" type="info" :closable="false" />
    <el-alert v-if="hasConflicts" :title="l('存在待处理冲突；确认前请逐项修订。', 'Conflicts remain. Resolve each item before confirmation.')" type="warning" :closable="false" />
    <el-tabs v-if="availableSkills.length" v-model="activeSkill" class="skill-tabs">
      <el-tab-pane v-for="skill in availableSkills" :key="skill.id" :name="skill.id" :label="skill.label">
        <div v-if="payloadFor(skill.id)" class="result-panel">
          <div class="result-toolbar"><el-tag effect="plain">{{ groupStatus }}</el-tag><el-button v-if="canWrite && !baseline" type="primary" plain size="small" @click="openEdit(skill.id)">{{ l('人工修订', 'Edit result') }}</el-button></div>
          <div v-if="skill.id === 'bidding-tender-profile'" class="structured-grid">
            <div v-for="field in profileFields" :key="field.label"><span>{{ field.label }}</span><strong>{{ printable(field.value) }}</strong></div>
            <el-table :data="array(payloadFor(skill.id)?.deadlines)" size="small"><el-table-column prop="name" :label="l('时间事项','Deadline')" min-width="150"/><el-table-column prop="value" :label="l('日期','Date')" width="180"/><el-table-column :label="l('证据','Evidence')" min-width="120"><template #default="{row}"><el-button v-if="row.evidenceRefs?.length" type="primary" size="small" @click="$emit('evidence', row.evidenceRefs[0])">{{ l('查看证据','Evidence') }}</el-button><span v-else>—</span></template></el-table-column></el-table>
          </div>
          <el-table v-else :data="resultRows(skill.id)" size="small" row-key="id" class="result-table">
            <el-table-column prop="title" :label="l('条款','Item')" min-width="220" show-overflow-tooltip />
            <el-table-column prop="detail" :label="l('要求 / 判定','Requirement / rule')" min-width="260" show-overflow-tooltip />
            <el-table-column prop="score" :label="l('分值','Score')" width="100" />
            <el-table-column :label="l('证据','Evidence')" min-width="120"><template #default="{row}"><el-button v-if="row.evidenceRefs?.length" type="primary" size="small" @click="$emit('evidence', row.evidenceRefs[0])">{{ l('查看证据','Evidence') }}</el-button><span v-else>—</span></template></el-table-column>
          </el-table>
          <details class="coverage"><summary>{{ l('覆盖范围与警告','Coverage and warnings') }}</summary><p>{{ l('已处理区块','Processed blocks') }}: {{ blockCount(skill.id,'processedBlockIds') }} · {{ l('未处理','Unprocessed') }}: {{ blockCount(skill.id,'unprocessedBlockIds') }}</p><p v-for="(warning,index) in warnings(skill.id)" :key="index">{{ warning }}</p></details>
        </div>
        <el-empty v-else :description="l('此项解析尚未完成', 'This analysis is not complete')" />
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="editOpen" :title="l('修订解析结果', 'Edit analysis result')" width="min(860px, 95vw)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item :label="l('修订原因','Reason')" required><el-input v-model="editReason" maxlength="1000" /></el-form-item><el-form-item :label="l('结构化结果 JSON','Structured result JSON')" required><el-input v-model="editText" type="textarea" :rows="18" spellcheck="false" /></el-form-item></el-form>
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
      <template #footer><el-button @click="editOpen=false">{{ l('取消','Cancel') }}</el-button><el-button type="primary" :disabled="!editReason.trim()" @click="saveEdit">{{ l('保存修订','Save revision') }}</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AnalysisGroup, AnalysisPayload, AnalysisView } from '../api/types'
const props = defineProps<{ analysis: AnalysisView; canWrite: boolean; canApprove: boolean }>()
const emit = defineEmits<{ evidence:[ref: Record<string, unknown>]; edit:[body:{taskGroupId:string;skillId:string;payload:AnalysisPayload;reason:string}] ; confirm:[groupId:string] }>()
const { locale } = useI18n(), l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const labels = [ ['bidding-tender-profile',l('基本信息','Basic information')], ['bidding-elimination-analysis',l('废标条件','Disqualification')], ['bidding-requirement-analysis',l('技术与商务要求','Requirements')], ['bidding-scoring-analysis',l('评分标准','Scoring')] ]
const baseline = computed(() => props.analysis.baseline)
const groups = computed(() => props.analysis.groups || [])
const activeGroup = computed<AnalysisGroup | undefined>(() => groups.value.find(group => group.complete) || groups.value[0])
const activeSkill = ref(labels[0]![0]), availableSkills = computed(() => labels.map(([id,label])=>({id,label})))
watch(() => groups.value, value => { if (value.length && !activeGroup.value) activeSkill.value=labels[0]![0] }, { immediate:true })
function statusLabel(status?:string) { const labels:Record<string,string>={SUCCEEDED:l('已完成','Completed'),FAILED:l('失败','Failed'),RUNNING:l('执行中','Running'),QUEUED:l('等待执行','Queued'),CANCELLED:l('已取消','Cancelled'),STALE:l('已失效','Outdated')}; return status ? labels[status] || l('候选','Candidate') : l('候选','Candidate') }
const groupStatus = computed(() => baseline.value ? l('已确认','Confirmed') : statusLabel(activeGroup.value?.status))
const hasConflicts = computed(() => (activeGroup.value?.conflicts || []).length>0 || !!baseline.value?.payload.conflicts?.length)
function payloadFor(skill:string):AnalysisPayload|undefined { return baseline.value?.payload.analyses?.[skill] || activeGroup.value?.skills?.[skill] }
const profileFields = computed(() => { const basic = payloadFor(labels[0]![0])?.basicInfo as Record<string,unknown>|undefined; return [ {label:l('项目名称','Project'),value:basic?.project},{label:l('招标人','Tenderer'),value:basic?.tenderer},{label:l('标段','Lot'),value:basic?.lot} ] })
function array(value:unknown):Record<string,any>[] { return Array.isArray(value)?value as Record<string,any>[]:[] }
function blockCount(skill:string,key:'processedBlockIds'|'unprocessedBlockIds') { const coverage=payloadFor(skill)?.coverage as {processedBlockIds?:string[];unprocessedBlockIds?:string[]}|undefined; return coverage?.[key]?.length || 0 }
function warnings(skill:string) { const value=payloadFor(skill)?.warnings; return Array.isArray(value)?value.map(String):[] }
function printable(value:unknown):string { if(value == null || value === '') return '—'; if(typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value); if(Array.isArray(value)) return value.map(printable).filter(item=>item!=='—').join('; '); if(typeof value === 'object') { const record=value as Record<string,unknown>; return [record.field,record.reason].filter(item=>item != null && item !== '').map(String).join(': ') || JSON.stringify(value) } return String(value) }
function resultRows(skill:string) { const payload=payloadFor(skill); if(skill==='bidding-elimination-analysis') return array(payload?.items).map(item=>({id:item.id,title:item.text,detail:[item.scope,item.trigger,array(item.unknowns).map(printable).join('; ')].filter(Boolean).join(' · '),score:'',evidenceRefs:item.evidenceRefs})); if(skill==='bidding-requirement-analysis') return array(payload?.requirements).map(item=>({id:item.id,title:item.text,detail:[item.category==='TECHNICAL'?l('技术要求','Technical'):item.category==='COMMERCIAL'?l('商务要求','Commercial'):'',array(item.constraints).map(printable).join('; '),printable(item.acceptance)].filter(Boolean).join(' · '),score:'',evidenceRefs:item.evidenceRefs})); return array(payload?.criteria).map(item=>({id:item.id,title:item.title,detail:item.rule,score:item.score==null?l('待核实','Unknown'):`${item.score}${item.unit||''}`,evidenceRefs:item.evidenceRefs})) }
const editOpen=ref(false), editText=ref(''), editReason=ref(''), editSkill=ref(''), editError=ref('')
function openEdit(skill:string) { editSkill.value=skill; editText.value=JSON.stringify(payloadFor(skill)||{},null,2); editReason.value=''; editError.value=''; editOpen.value=true }
function saveEdit() { try { const payload=JSON.parse(editText.value) as AnalysisPayload; const taskGroupId=activeGroup.value?.taskGroupId || baseline.value?.payload.taskGroupId; if(!taskGroupId) throw new Error('Missing analysis task group'); emit('edit',{taskGroupId,skillId:editSkill.value,payload,reason:editReason.value}) } catch { editError.value=l('JSON 格式错误或解析组不存在。','Invalid JSON or analysis group.') } }
</script>
<style scoped>
.analysis { display:grid; gap:12px; min-width:0; }.analysis-heading,.analysis-heading>div,.result-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; }.analysis-heading h2 { margin:0; font-size:18px; }.baseline { color:var(--mc-text-secondary); font-size:13px; }.actions { display:flex; gap:8px; align-items:center; }.result-panel { display:grid; gap:12px; min-width:0; }.table-scroll,.result-table { min-width:0; }.structured-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }.structured-grid>div { display:flex; flex-direction:column; gap:6px; padding:12px; border:1px solid var(--mc-border-light); border-radius:4px; }.structured-grid :deep(.el-table) { grid-column:1 / -1; width:100%; }.structured-grid span { color:var(--mc-text-secondary); font-size:12px; }.coverage { color:var(--mc-text-secondary); font-size:13px; }.coverage summary { cursor:pointer; color:var(--mc-action-text,var(--mc-primary)); }.skill-tabs { background:var(--mc-bg-elevated); border:1px solid var(--mc-border); border-radius:6px; padding:0 16px 16px; }.el-button:focus-visible { outline:2px solid var(--mc-primary); outline-offset:2px; }@media(max-width:768px) { .analysis-heading { align-items:flex-start; flex-wrap:wrap; }.structured-grid { grid-template-columns:1fr; } }
</style>
