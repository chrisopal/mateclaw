<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ontologyApi, semanticRequest } from '../../api/ontologyApi'
import {
  modelingTaskApi,
  type ModelingSource,
  type CreateModelingTask,
} from '../../api/modelingTaskApi'
import { useSemanticScope } from '../../shared/useSemanticScope'
const props = defineProps<{
    modelValue: boolean
    ontologyId?: string
    initialKnowledgeBaseId?: string
    initialKnowledgeBaseName?: string
  }>(),
  emit = defineEmits<{
    'update:modelValue': [value: boolean]
    created: [taskId: string, ontologyId: string]
  }>()
const { workspace, begin } = useSemanticScope(),
  router = useRouter()
type SourceItem = {
  knowledgeBaseId: string
  name?: string
  sourceRef?: string
  title?: string
  processingStatus?: string
  sourceDigest?: string
  readable?: boolean
  unreadReason?: string
}
const name = ref(''),
  goal = ref(''),
  mode = ref('text'),
  kb = ref(''),
  agentId = ref(''),
  rows = ref<SourceItem[]>([]),
  bases = ref<SourceItem[]>([]),
  selected = ref<Array<ModelingSource & { title: string }>>([]),
  page = ref(1),
  hasMore = ref(false),
  busy = ref(false),
  error = ref(''),
  sourceAccessDenied = ref(false)
let pending: CreateModelingTask | undefined
async function sources() {
  const c = begin()
  busy.value = true
  error.value = ''
  sourceAccessDenied.value = false
  try {
    if (!agentId.value) {
      const builder = await ontologyApi.ensureBuilder(c.id, c.signal)
      if (!c.current()) return
      agentId.value = String(builder.agentId)
    }
    const result = await semanticRequest<{
      items: SourceItem[]
      hasMore: boolean
    }>(
      c.id,
      {
        url: '/semantic/modeling-tasks/sources',
        params: {
          agentId: agentId.value,
          knowledgeBaseId: kb.value || undefined,
          page: page.value,
          pageSize: 20,
        },
      },
      c.signal,
    )
    if (c.current()) {
      if (!kb.value)
        bases.value =
          page.value === 1 ? result.items : [...bases.value, ...result.items]
      else rows.value = result.items
      hasMore.value = result.hasMore
    }
  } catch (e) {
    if (c.current()) {
      const failure = e as { message?: string; status?: number }
      sourceAccessDenied.value =
        failure.status === 403 ||
        /Source or agent is unavailable or not visible/i.test(
          failure.message || '',
        )
      error.value = sourceAccessDenied.value
        ? '建模助手暂无此知识库的访问权限，请在员工设置中开启知识库并授予访问范围。'
        : failure.message || '资料加载失败，请重试。'
    }
  } finally {
    if (c.current()) busy.value = false
  }
}
function choose(item: SourceItem, checked: boolean) {
  selected.value = selected.value.filter(
    (x) =>
      !(
        x.knowledgeBaseId === item.knowledgeBaseId &&
        x.sourceRef === item.sourceRef
      ),
  )
  if (checked && item.readable && item.sourceRef && item.sourceDigest)
    selected.value.push({
      knowledgeBaseId: item.knowledgeBaseId,
      sourceRef: item.sourceRef,
      sourceDigest: item.sourceDigest,
      title: item.title || '资料',
    })
}
function sourceReason(item: SourceItem) {
  return item.readable
    ? '可读取'
    : (
        {
          NOT_PARSED: '尚未完成解析，请稍后刷新',
          SOURCE_TOO_LARGE: '资料内容过大，请拆分后重新上传',
          SOURCE_EMPTY: '资料内容为空',
          EMPTY_SOURCE: '资料内容为空',
          SOURCE_UNAVAILABLE: '资料不可访问',
          PARSE_FAILED: '解析失败，请在知识库检查原因',
        } as Record<string, string>
      )[item.unreadReason || ''] || '尚不可读取，请在知识库检查解析状态'
}
function changePage(delta: number) {
  page.value += delta
  void sources()
}
function isSelected(item: SourceItem) {
  return selected.value.some(
    (x) =>
      x.knowledgeBaseId === item.knowledgeBaseId &&
      x.sourceRef === item.sourceRef,
  )
}
async function create() {
  if (
    busy.value ||
    !workspace.can('manage:ontology') ||
    !goal.value.trim() ||
    (!props.ontologyId && !name.value.trim()) ||
    (mode.value === 'sources' && !selected.value.length)
  )
    return
  const c = begin()
  busy.value = true
  error.value = ''
  pending ??= {
    operationId: crypto.randomUUID(),
    ontologyId: props.ontologyId,
    newOntology: props.ontologyId
      ? undefined
      : { name: name.value.trim(), description: '' },
    goal: goal.value.trim(),
    sources:
      mode.value === 'sources'
        ? selected.value.map(
            ({ knowledgeBaseId, sourceRef, sourceDigest }) => ({
              knowledgeBaseId,
              sourceRef,
              sourceDigest,
            }),
          )
        : [],
  }
  try {
    const task = await modelingTaskApi.create(c.id, pending, c.signal)
    if (c.current()) {
      pending = undefined
      emit('created', task.id, task.ontologyId)
      emit('update:modelValue', false)
    }
  } catch (e) {
    if (c.current()) error.value = (e as Error).message
  } finally {
    if (c.current()) busy.value = false
  }
}
watch(kb, () => {
  page.value = 1
  rows.value = []
  void sources()
})
watch(mode, (value) => {
  if (value === 'sources' && !bases.value.length) void sources()
})
watch(
  () => props.modelValue,
  (value) => {
    if (value) {
      name.value = ''
      goal.value = ''
      mode.value = 'text'
      if (props.initialKnowledgeBaseId) {
        bases.value = [
          {
            knowledgeBaseId: props.initialKnowledgeBaseId,
            name: props.initialKnowledgeBaseName || '当前知识库',
          },
        ]
        if (kb.value === props.initialKnowledgeBaseId) void sources()
        else kb.value = props.initialKnowledgeBaseId
        mode.value = 'sources'
      }
      selected.value = []
      error.value = ''
      pending = undefined
    }
  },
)
watch(
  () => workspace.currentWorkspaceId,
  () => {
    emit('update:modelValue', false)
    agentId.value = ''
    bases.value = []
    rows.value = []
    selected.value = []
    pending = undefined
  },
)
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="开始业务建模"
    width="min(680px,95vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="emit('update:modelValue', $event)"
    ><el-form label-position="top" @submit.prevent="create">
      <el-form-item v-if="!ontologyId" label="模型名称" required
        ><el-input
          v-model="name"
          aria-label="模型名称"
          maxlength="128"
          :disabled="!!pending"
      /></el-form-item>
      <el-form-item label="建模方式"
        ><el-radio-group v-model="mode" :disabled="!!pending"
          ><el-radio-button value="text">描述业务需求</el-radio-button
          ><el-radio-button value="sources"
            >从资料建模</el-radio-button
          ></el-radio-group
        ></el-form-item
      >
      <el-form-item label="希望建立什么业务模型？" required
        ><el-input
          v-model="goal"
          aria-label="业务建模目标"
          type="textarea"
          :rows="3"
          maxlength="4000"
          :disabled="!!pending"
          placeholder="例如：管理设备、点检项目和故障之间的关系"
      /></el-form-item>
      <section v-if="mode === 'sources'" class="task-sources">
        <el-select
          v-model="kb"
          aria-label="选择知识库"
          placeholder="选择知识库"
          :disabled="busy || !!pending"
          ><el-option
            v-for="base in bases"
            :key="base.knowledgeBaseId"
            :value="base.knowledgeBaseId"
            :label="base.name"
        /></el-select>
        <div class="source-actions">
          <el-button :loading="busy" @click="sources">刷新解析状态</el-button
          ><a
            :href="
              router.resolve({ name: 'Wiki', query: { kbId: kb || undefined } })
                .href
            "
            target="_blank"
            rel="noopener"
            >在知识库上传资料（新窗口）</a
          >
        </div>
        <p v-if="!busy && !bases.length" class="semantic-muted">
          没有可用知识库。请先在智能体管理中为本体建模师授权知识库，再刷新。<a
            :href="router.resolve({ name: 'Agents' }).href"
            target="_blank"
            rel="noopener"
            >打开智能体管理</a
          >
        </p>
        <p class="semantic-muted">
          已选择 {{ selected.length }} 份。资料完成解析后可选择。
        </p>
        <div v-for="item in rows" :key="item.sourceRef" class="source-row">
          <el-checkbox
            :model-value="isSelected(item)"
            :disabled="!item.readable || !!pending"
            @change="choose(item, Boolean($event))"
            >{{ item.title }}</el-checkbox
          ><small
            >{{ isSelected(item) ? '已选择 · ' : ''
            }}{{ sourceReason(item) }}</small
          >
        </div>
        <div class="source-actions">
          <el-button :disabled="page === 1 || busy" @click="changePage(-1)"
            >上一页</el-button
          ><span>第 {{ page }} 页</span
          ><el-button :disabled="!hasMore || busy" @click="changePage(1)"
            >下一页</el-button
          >
        </div>
      </section>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <p v-if="error && sourceAccessDenied">
        <a
          :href="router.resolve({ name: 'Agents' }).href"
          target="_blank"
          rel="noopener"
          >打开员工设置</a
        >
      </p></el-form
    ><template #footer
      ><el-button :disabled="busy" @click="emit('update:modelValue', false)"
        >取消</el-button
      ><el-button
        type="primary"
        :loading="busy"
        :disabled="
          !goal.trim() ||
          (!ontologyId && !name.trim()) ||
          (mode === 'sources' && !selected.length)
        "
        @click="create"
        >{{ pending ? '重试创建任务' : '创建任务' }}</el-button
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.task-sources {
  margin-bottom: 16px;
}
.source-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.source-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.source-row small {
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}
.source-row :deep(.el-checkbox) {
  white-space: normal;
  height: auto;
}
.source-row :deep(.el-checkbox__label) {
  white-space: normal;
}
</style>

<style scoped>
.source-row :deep(.el-checkbox) {
  --el-checkbox-checked-bg-color: var(
    --mc-user-bubble-bg,
    var(--el-color-primary)
  );
  --el-checkbox-checked-input-border-color: var(
    --mc-user-bubble-bg,
    var(--el-color-primary)
  );
  --el-checkbox-checked-icon-color: var(
    --mc-user-bubble-color,
    var(--el-color-white)
  );
}
</style>
