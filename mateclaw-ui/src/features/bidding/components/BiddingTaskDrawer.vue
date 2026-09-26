<template>
  <el-drawer v-model="open" :title="l('解析任务','Analysis tasks')" size="min(720px, 96vw)" @closed="stop">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="loading" class="task-list">
      <el-table class="desktop-task-table" :data="tasks" row-key="taskId" @row-click="select">
        <el-table-column :label="l('解析项','Analysis')" min-width="180"><template #default="{row}">{{ taskSkill(row) }}</template></el-table-column>
        <el-table-column :label="l('状态','Status')" width="140"><template #default="{row}">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="attemptCount" :label="l('尝试次数','Attempts')" width="110" />
        <el-table-column :label="l('操作','Actions')" width="180"><template #default="{row}"><div class="actions"><el-button v-if="canWrite && retryable(row.status)" type="primary" plain size="small" @click.stop="mutate(row,'RETRY_TASK')">{{ l('重试','Retry') }}</el-button><el-button v-if="canWrite && cancellable(row.status)" type="danger" plain size="small" @click.stop="mutate(row,'CANCEL_TASK')">{{ l('取消','Cancel') }}</el-button><span v-if="!canWrite && (retryable(row.status)||cancellable(row.status))" class="read-only">{{ l('只读访问','Read-only access') }}</span></div></template></el-table-column>
      </el-table>
      <div class="mobile-task-list">
        <el-empty v-if="!tasks.length && !loading" :description="l('暂无任务','No tasks')" />
        <article v-for="row in tasks" :key="row.taskId" class="mobile-task-card">
          <div class="task-card-main" role="button" tabindex="0" :aria-label="`${taskSkill(row)} · ${statusLabel(row.status)}`" @click="select(row)" @keydown.enter.prevent="select(row)" @keydown.space.prevent="select(row)">
            <strong class="task-name">{{ taskSkill(row) }}</strong>
            <div class="task-meta"><el-tag effect="plain" size="small">{{ statusLabel(row.status) }}</el-tag><span>{{ l('尝试次数','Attempts') }}: {{ row.attemptCount }}</span></div>
          </div>
          <div class="actions" @click.stop>
            <el-button v-if="canWrite && retryable(row.status)" type="primary" plain size="small" @click="mutate(row,'RETRY_TASK')">{{ l('重试','Retry') }}</el-button>
            <el-button v-if="canWrite && cancellable(row.status)" type="danger" plain size="small" @click="mutate(row,'CANCEL_TASK')">{{ l('取消','Cancel') }}</el-button>
            <span v-if="!canWrite && (retryable(row.status)||cancellable(row.status))" class="read-only">{{ l('只读访问','Read-only access') }}</span>
          </div>
        </article>
      </div>
    </div>
    <section v-if="detail" class="task-detail"><h3>{{ taskSkill(detail) || detail.taskId }}</h3><el-alert v-if="failure(detail)" :title="failure(detail)" type="error" :closable="false" /><h4>{{ l('执行尝试','Attempts') }}</h4><el-table class="desktop-attempt-table" :data="detail.attempts || []" size="small"><el-table-column prop="attemptNo" label="#" width="60"/><el-table-column :label="l('状态','State')" width="120"><template #default="{row}">{{ statusLabel(row.state) }}</template></el-table-column><el-table-column :label="l('原因','Failure')" min-width="180"><template #default="{row}">{{ failureLabel(attemptFailureCode(row)) }}</template></el-table-column></el-table><div class="mobile-attempt-list"><article v-for="attempt in detail.attempts || []" :key="attempt.attemptNo" class="mobile-attempt-card"><div><strong>#{{ attempt.attemptNo }}</strong><el-tag effect="plain" size="small">{{ statusLabel(attempt.state) }}</el-tag></div><p>{{ l('原因','Failure') }}: {{ failureLabel(attemptFailureCode(attempt)) }}</p></article></div><details v-if="detail.attempts?.length"><summary>{{ l('诊断信息','Diagnostics') }}</summary><pre>{{ JSON.stringify(detail.attempts.map(item=>item.rejection||item.result),null,2) }}</pre></details></section>
  </el-drawer>
</template>
<script setup lang="ts">
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { biddingApi } from '../api/biddingApi'
import { operationId } from '../shared/state'
import type { Project, Task, TaskDetails } from '../api/types'
const props=defineProps<{modelValue:boolean;workspaceId:string;project:Project;canWrite:boolean}>(),emit=defineEmits<{ 'update:modelValue':[boolean]; changed:[] }>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const open=ref(false),tasks=ref<Task[]>([]),detail=ref<TaskDetails>(),loading=ref(false),error=ref('')
let controller:AbortController|undefined,timer:number|undefined
const active=(items:Task[])=>items.some(task=>['QUEUED','RUNNING','WAITING_RETRY'].includes(task.status))
async function load(){controller?.abort();controller=new AbortController();const signal=controller.signal,ws=props.workspaceId,id=props.project.id;if(!ws||!id)return;loading.value=true;try{const result=await biddingApi.tasks(ws,id,signal);if(ws===props.workspaceId&&id===props.project.id&&!signal.aborted){tasks.value=result.items;if(detail.value){const selected=tasks.value.find(task=>task.taskId===detail.value?.taskId);if(selected){const current=await biddingApi.task(ws,selected.taskId,signal);if(ws===props.workspaceId&&id===props.project.id&&!signal.aborted)detail.value=current}}if(props.modelValue&&active(tasks.value))timer=window.setTimeout(()=>void load(),2000)}}catch{if(!signal.aborted)error.value=l('任务读取失败。','Could not load tasks.')}finally{if(!signal.aborted)loading.value=false}}
watch(()=>[props.modelValue,props.workspaceId,props.project.id] as const,(next,previous)=>{const [visible,workspaceId,projectId]=next,oldWorkspaceId=previous?.[1],oldProjectId=previous?.[2];if(workspaceId!==oldWorkspaceId||projectId!==oldProjectId){tasks.value=[];detail.value=undefined;error.value=''}open.value=visible;stop();if(visible)void load()},{immediate:true})
watch(open,value=>emit('update:modelValue',value))
function stop(){controller?.abort();if(timer)window.clearTimeout(timer);timer=undefined}
async function select(row:Task){controller?.abort();controller=new AbortController();const signal=controller.signal,ws=props.workspaceId,id=props.project.id;try{const value=await biddingApi.task(ws,row.taskId,signal);if(!signal.aborted&&ws===props.workspaceId&&id===props.project.id)detail.value=value}catch{if(!signal.aborted)error.value=l('任务详情读取失败。','Could not load task details.')}}
async function mutate(row:Task,action:string){try{await biddingApi.command(props.workspaceId,props.project.id,{operationId:operationId(),expected:props.project.ref,action,payload:{taskId:row.taskId}});emit('changed');await load()}catch{error.value=l('任务状态已变化，请刷新后重试。','Task state changed. Refresh before retrying.')}}
const skillLabels:Record<string,[string,string]>={
  'bidding-tender-profile':['招标文件基本信息','Tender profile'],
  'bidding-elimination-analysis':['废标条款','Disqualification analysis'],
  'bidding-requirement-analysis':['技术与商务要求','Technical and commercial requirements'],
  'bidding-scoring-analysis':['评分标准','Scoring criteria'],
}
const statusLabels:Record<string,[string,string]>={QUEUED:['排队中','Queued'],RUNNING:['执行中','Running'],WAITING_RETRY:['等待重试','Waiting to retry'],FAILED:['失败','Failed'],SUCCEEDED:['已完成','Succeeded'],CANCELLED:['已取消','Cancelled'],STALE:['已过期','Stale']}
const failureLabels:Record<string,[string,string]>={
  MODEL_TIMEOUT:['模型响应超时','Model response timed out'],EXECUTION_TIMEOUT:['执行超时','Execution timed out'],SKILL_NOT_LOADED:['技能包未能加载','The configured skill could not be loaded'],
  OUTPUT_INVALID:['结果格式不符合要求','The result format is invalid'],OUTPUT_SCHEMA_MISSING:['缺少结果结构定义','The result schema is missing'],OUTPUT_LIMIT:['输出超出限制','Output exceeded the allowed limit'],
  STREAM_INCOMPLETE:['模型输出未完整结束','The model response did not complete'],EXECUTION_RUNTIME_FAILURE:['执行环境发生错误','The execution runtime failed'],
  CONFIGURATION_REQUIRED:['请先完成员工配置','Employee configuration is required'],EMPLOYEE_UNAVAILABLE:['分析员工不可用','The assigned employee is unavailable'],
  SKILL_PIN_STALE:['员工技能配置已变化','The pinned skill configuration changed'],SKILL_NOT_ASSIGNED:['此技能未分配给项目','This skill is not assigned to the project'],SKILL_ROLE_MISMATCH:['技能与岗位不匹配','The skill does not match its assigned role'],SKILL_HANDLER_MISSING:['缺少对应的结果处理器','No result handler is available for this skill'],
  SOURCE_NOT_READY:['来源文件尚未就绪','A source file is not ready'],SOURCE_NOT_CONFIRMED:['来源尚未确认','The source set is not confirmed'],SOURCE_STALE:['来源文件已变化','A source file has changed'],DEPENDENCY_STALE:['任务依赖已变化','A task dependency has changed'],SOURCE_SET_INCOMPLETE:['来源集合不完整','The selected source set is incomplete'],
  TASK_ACTOR_MISSING:['发起任务的成员已不可用','The task creator is unavailable'],AUTHORIZATION_REVALIDATION_FAILED:['项目权限已变化','Project access changed'],UNAUTHENTICATED:['登录状态已失效','The session is no longer valid'],FORBIDDEN:['当前账号无权执行此操作','This account is not allowed to perform the action'],
  ATTEMPT_STALE:['本次尝试已失效','This attempt is no longer active'],RESULT_HANDLER_FAILURE:['结果保存失败','The result could not be saved'],COMPLETE_FAILURE:['任务结果处理失败','The task result could not be processed'],TASK_NOT_RETRYABLE:['当前任务状态不支持重试','This task state cannot be retried']
}
function label(map:Record<string,[string,string]>,key:string|undefined,fallback:[string,string]){const pair=key?map[key]:undefined;return pair?l(pair[0],pair[1]):l(fallback[0],fallback[1])}
function statusLabel(value:string){return label(statusLabels,value,['其他状态','Other status'])}
function failureLabel(value:string|undefined){return value?label(failureLabels,value,['其他错误','Other failure']):'—'}
function skillLabel(value:string|undefined){return label(skillLabels,value,['其他任务','Other task'])}
function taskSkill(value:Task|TaskDetails){const detail=value as TaskDetails;const listed=tasks.value.find(task=>task.taskId===value.taskId)?.skillId;const snapshot=(detail.snapshot as {_bidding?:{skillId?:string}}|undefined)?._bidding?.skillId;return skillLabel(value.skillId||listed||snapshot)}
function attemptFailureCode(attempt:{failureCode?:string;rejection?:unknown}){const rejection=attempt.rejection as {code?:string}|undefined;return rejection?.code||attempt.failureCode}
function failure(value:TaskDetails){const last=(value.attempts||[]).at(-1);const code=last?attemptFailureCode(last):undefined;return code?failureLabel(code):''}
function retryable(status:string){return ['FAILED','CANCELLED','STALE'].includes(status)}
function cancellable(status:string){return ['QUEUED','RUNNING','WAITING_RETRY'].includes(status)}
onBeforeUnmount(stop)
</script>
<style scoped>
.actions{display:flex;gap:8px;align-items:center;flex-wrap:nowrap}.read-only{color:var(--mc-text-secondary);font-size:12px}.task-detail{border-top:1px solid var(--mc-border-light);margin-top:18px;padding-top:16px}.task-detail h3{font-size:16px;overflow-wrap:anywhere}.task-detail h4{font-size:13px;color:var(--mc-text-secondary)}pre{max-height:280px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.task-detail details{margin-top:12px}.task-detail summary{cursor:pointer;color:var(--mc-primary)}.mobile-task-list,.mobile-attempt-list{display:none}
@media(max-width:600px){:deep(.el-drawer__body){padding:12px;overflow-x:hidden}.desktop-task-table,.desktop-attempt-table{display:none}.mobile-task-list,.mobile-attempt-list{display:grid;gap:10px;min-width:0}.mobile-task-card,.mobile-attempt-card{display:grid;gap:10px;min-width:0;padding:12px;border:1px solid var(--mc-border);border-radius:6px;background:var(--mc-bg-elevated)}.task-card-main{display:grid;gap:8px;min-width:0;cursor:pointer}.task-card-main:focus-visible{outline:2px solid var(--mc-primary);outline-offset:2px}.task-name{overflow-wrap:anywhere}.task-meta,.mobile-attempt-card>div{display:flex;align-items:center;gap:10px;flex-wrap:wrap;color:var(--mc-text-secondary);font-size:12px}.mobile-task-card .actions{flex-wrap:wrap;justify-content:flex-start}.mobile-attempt-card p{margin:0;overflow-wrap:anywhere;color:var(--mc-text-secondary);font-size:13px}}
</style>
