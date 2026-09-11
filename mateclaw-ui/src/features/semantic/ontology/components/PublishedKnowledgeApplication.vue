<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Revision } from '../../api/types'
import { sourceSelectionApi, type SourceKnowledgeBase } from '../../api/sourceSelectionApi'
import { useSemanticScope } from '../../shared/useSemanticScope'
import KnowledgeBindingPanel from '../../graph/KnowledgeBindingPanel.vue'
const props = defineProps<{ revision: Revision }>()
const open = defineModel<boolean>({ required: true })
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const { workspace, begin } = useSemanticScope()
const choices = ref<SourceKnowledgeBase[]>([]), selected = ref(''), busy = ref(false), error = ref('')
async function load() {
  const run = begin(); choices.value = []; selected.value = ''; error.value = ''; busy.value = true
  try { const values = await sourceSelectionApi.knowledgeBases(run.id, run.signal); if (run.current()) choices.value = values }
  catch { if (run.current()) error.value = tr('无法读取知识库，请重试。', 'Could not load knowledge bases. Try again.') }
  finally { if (run.current()) busy.value = false }
}
watch(() => [workspace.currentWorkspaceId, props.revision.id], load, { immediate: true })
</script>
<template>
  <el-dialog v-model="open" :title="tr('应用到知识库', 'Apply to knowledge base')" width="min(720px, calc(100vw - 24px))" destroy-on-close>
    <p class="application-version">{{ revision.name }} · v{{ revision.version }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top">
      <el-form-item :label="tr('应用知识库', 'Application knowledge base')">
        <el-select v-model="selected" :loading="busy" :disabled="busy" filterable :aria-label="tr('应用知识库', 'Application knowledge base')">
          <el-option v-for="kb in choices" :key="String(kb.id)" :value="String(kb.id)" :label="kb.name" />
        </el-select>
      </el-form-item>
    </el-form>
    <p class="application-hint">{{ tr('选择使用此模型的知识库；不会自动应用到参考资料所在的知识库。', 'Choose where this model will be used. Reference knowledge bases are not applied automatically.') }}</p>
    <el-button v-if="error" @click="load">{{ tr('重试', 'Retry') }}</el-button>
    <p v-if="!busy && !error && !choices.length">{{ tr('暂无可访问的知识库，请先创建知识库。', 'No accessible knowledge bases. Create one first.') }}</p>
    <KnowledgeBindingPanel v-if="selected" :key="`${workspace.currentWorkspaceId}:${selected}:${revision.id}`" :knowledge-base-id="selected" :target-revision="revision" />
  </el-dialog>
</template>
<style scoped>
.application-version{font-weight:600;margin:0 0 20px;overflow-wrap:anywhere}.application-hint{font-size:12px;color:var(--mc-text-secondary);margin:0}.el-select{width:100%}
</style>
