<template>
  <el-drawer v-model="open" :title="l('解析任务','Analysis tasks')" size="min(720px, 96vw)" @closed="stop">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-loading="loading" :data="tasks" row-key="taskId" @row-click="select">
      <el-table-column :label="l('解析项','Analysis')" min-width="180"><template #default="{row}">{{ taskSkill(row) }}</template></el-table-column>
      <el-table-column prop="status" :label="l('状态','Status')" width="140" />
      <el-table-column prop="attemptCount" :label="l('尝试次数','Attempts')" width="110" />
      <el-table-column :label="l('操作','Actions')" width="180"><template #default="{row}"><div class="actions"><el-button v-if="['FAILED','CANCELLED','STALE'].includes(row.status)" type="primary" plain size="small" @click.stop="mutate(row,'RETRY_TASK')">{{ l('重试','Retry') }}</el-button><el-button v-if="['QUEUED','RUNNING','WAITING_RETRY'].includes(row.status)" type="danger" plain size="small" @click.stop="mutate(row,'CANCEL_TASK')">{{ l('取消','Cancel') }}</el-button></div></template></el-table-column>
    </el-table>
    <section v-if="detail" class="task-detail"><h3>{{ taskSkill(detail) || detail.taskId }}</h3><el-alert v-if="failure(detail)" :title="failure(detail)" type="error" :closable="false" /><h4>{{ l('执行尝试','Attempts') }}</h4><el-table :data="detail.attempts || []" size="small"><el-table-column prop="attemptNo" label="#" width="60"/><el-table-column prop="state" :label="l('状态','State')" width="120"/><el-table-column :label="l('原因','Failure')" min-width="180"><template #default="{row}">{{ row.rejection?.code || '—' }}</template></el-table-column></el-table><details v-if="detail.attempts?.length"><summary>{{ l('诊断信息','Diagnostics') }}</summary><pre>{{ JSON.stringify(detail.attempts.map(item=>item.rejection||item.result),null,2) }}</pre></details></section>
  </el-drawer>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { biddingApi } from '../api/biddingApi'
import { operationId } from '../shared/state'
import type { Project, Task, TaskDetails } from '../api/types'
const props=defineProps<{modelValue:boolean;workspaceId:string;project:Project}>(),emit=defineEmits<{ 'update:modelValue':[boolean]; changed:[] }>()
const {locale}=useI18n(),l=(zh:string,en:string)=>String(locale.value).startsWith('zh')?zh:en
const open=ref(false),tasks=ref<Task[]>([]),detail=ref<TaskDetails>(),loading=ref(false),error=ref('')
let controller:AbortController|undefined,timer:number|undefined
const active=(items:Task[])=>items.some(task=>['QUEUED','RUNNING','WAITING_RETRY'].includes(task.status))
async function load(){controller?.abort();controller=new AbortController();const signal=controller.signal,ws=props.workspaceId,id=props.project.id;if(!ws||!id)return;loading.value=true;try{const result=await biddingApi.tasks(ws,id,signal);if(ws===props.workspaceId&&id===props.project.id&&!signal.aborted){tasks.value=result.items;if(detail.value){const selected=tasks.value.find(task=>task.taskId===detail.value?.taskId);if(selected)detail.value=await biddingApi.task(ws,selected.taskId,signal)}if(props.modelValue&&active(tasks.value))timer=window.setTimeout(()=>void load(),2000)}}catch{if(!signal.aborted)error.value=l('任务读取失败。','Could not load tasks.')}finally{if(!signal.aborted)loading.value=false}}
watch(()=>[props.modelValue,props.workspaceId,props.project.id] as const,([visible])=>{open.value=visible;stop();if(visible)void load()},{immediate:true})
watch(open,value=>emit('update:modelValue',value))
function stop(){controller?.abort();if(timer)window.clearTimeout(timer);timer=undefined}
async function select(row:Task){controller?.abort();controller=new AbortController();try{detail.value=await biddingApi.task(props.workspaceId,row.taskId,controller.signal)}catch{error.value=l('任务详情读取失败。','Could not load task details.')}}
async function mutate(row:Task,action:string){try{await biddingApi.command(props.workspaceId,props.project.id,{operationId:operationId(),expected:props.project.ref,action,payload:{taskId:row.taskId}});emit('changed');await load()}catch{error.value=l('任务状态已变化，请刷新后重试。','Task state changed. Refresh before retrying.')}}
function taskSkill(value:Task|TaskDetails){return (value as TaskDetails).snapshot?((value as TaskDetails).snapshot as { _bidding?:{skillId?:string} })._bidding?.skillId:(value as Task).skillId||''}
function failure(value:TaskDetails){const attempts=value.attempts||[];const last=attempts.at(-1);const rejection=last?.rejection as {code?:string}|undefined;return rejection?.code||last?.failureCode||''}
onBeforeUnmount(stop)
</script>
<style scoped>.actions{display:flex;gap:8px;align-items:center;flex-wrap:nowrap}.task-detail{border-top:1px solid var(--mc-border-light);margin-top:18px;padding-top:16px}.task-detail h3{font-size:16px}.task-detail h4{font-size:13px;color:var(--mc-text-secondary)}pre{max-height:280px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.task-detail details{margin-top:12px}.task-detail summary{cursor:pointer;color:var(--mc-primary)}@media(max-width:600px){.actions{flex-wrap:wrap}}</style>
