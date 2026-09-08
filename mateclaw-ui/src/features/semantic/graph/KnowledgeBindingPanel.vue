<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { vLoading } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { graphApi } from '../api/graphApi'
import { ontologyApi } from '../api/ontologyApi'
import { semanticError, type SemanticError } from '../api/semanticErrors'
import type { Binding, Revision } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'

const props = defineProps<{ knowledgeBaseId: string }>()
const { t } = useI18n()
const { workspace, begin } = useSemanticScope()
const binding = ref<Binding | null>(null)
const choices = ref<(Revision & { ontologyName: string })[]>([])
const selectedRevisionId = ref('')
const busy = ref(false)
const error = ref<SemanticError | null>(null)

async function load() {
  const request = begin()
  busy.value = true
  error.value = null
  try {
    const current = await graphApi.binding(request.id, props.knowledgeBaseId, request.signal).catch((e) => {
      if (semanticError(e).status === 404) return null
      throw e
    })
    const ontologies = await ontologyApi.list(request.id, '', 1, request.signal)
    for (let page = 2; ontologies.items.length < ontologies.total; page++) {
      if (!request.current()) return
      const next = await ontologyApi.list(request.id, '', page, request.signal)
      if (next.items.length === 0) break
      ontologies.items.push(...next.items)
    }
    const revisions = await Promise.all(
      ontologies.items.map(async (ontology) =>
        (await ontologyApi.revisions(request.id, ontology.id, request.signal)).map((revision) => ({
          ...revision,
          ontologyName: ontology.name,
        })),
      ),
    )
    if (request.current()) {
      binding.value = current
      choices.value = revisions.flat().filter((revision) => revision.availableForNewBindings)
      selectedRevisionId.value = current?.ontologyRevisionId ?? choices.value[0]?.id ?? ''
    }
  } catch (e) {
    if (request.current()) error.value = semanticError(e)
  } finally {
    if (request.current()) busy.value = false
  }
}

async function submit(action: 'ENABLE' | 'DISABLE' | 'REBIND') {
  const request = begin()
  busy.value = true
  error.value = null
  try {
    const result = await graphApi.bind(
      request.id,
      props.knowledgeBaseId,
      {
        action,
        revisionId: action === 'DISABLE' ? undefined : selectedRevisionId.value,
        expectedGraphVersion: binding.value?.graphVersion,
      },
      request.signal,
    )
    if (request.current()) binding.value = result
  } catch (e) {
    if (request.current()) error.value = semanticError(e)
  } finally {
    if (request.current()) busy.value = false
  }
}

watch(() => [props.knowledgeBaseId, workspace.currentWorkspaceId], () => { binding.value = null; choices.value = []; void load() })
onMounted(load)
</script>

<template>
  <section class="semantic-binding-panel">
    <div class="semantic-binding-heading">
      <div>
        <h3>{{ t('semantic.bindingTitle') }}</h3>
        <p>{{ t('semantic.bindingHelp') }}</p>
      </div>
      <el-tag v-if="binding" :type="binding.enabled ? 'success' : 'info'">
        {{ t(binding.enabled ? 'semantic.bindingEnabled' : 'semantic.bindingDisabled') }}
      </el-tag>
    </div>
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))"
      :description="error.message"
    />
    <div v-loading="busy" class="semantic-binding-controls">
      <el-select v-model="selectedRevisionId" :aria-label="t('semantic.bindingRevision')" :disabled="busy || !workspace.can('publish:ontology')">
        <el-option
          v-for="revision in choices"
          :key="revision.id"
          :value="revision.id"
          :label="`${revision.ontologyName} · v${revision.version}`"
        />
      </el-select>
      <template v-if="workspace.can('publish:ontology')">
        <el-button v-if="!binding" type="primary" :disabled="!selectedRevisionId" @click="submit('ENABLE')">
          {{ t('semantic.bind') }}
        </el-button>
        <el-button v-else-if="!binding.enabled" type="primary" @click="submit('ENABLE')">
          {{ t('semantic.enableBinding') }}
        </el-button>
        <el-button v-else @click="submit('DISABLE')">{{ t('semantic.disableBinding') }}</el-button>
        <el-button v-if="binding" :disabled="!binding.empty || selectedRevisionId === binding.ontologyRevisionId" @click="submit('REBIND')">
          {{ t('semantic.rebind') }}
        </el-button>
      </template>
    </div>
    <router-link v-if="binding?.enabled" :to="`/semantic/graphs/${binding.graphId}`">{{ t('semantic.w.openWorkbench') }}</router-link>
    <p v-if="binding" class="semantic-binding-meta">
      {{ t('semantic.pinnedVersion', { version: binding.ontologyVersion }) }} ·
      {{ binding.empty ? t('semantic.graphEmpty') : t('semantic.graphNotEmpty') }}
    </p>
    <p v-if="binding && !binding.empty" class="semantic-binding-meta semantic-binding-impact-note">
      {{ t('semantic.impactHelp') }}
    </p>
  </section>
</template>

<style scoped>
.semantic-binding-panel { margin: 12px 20px; padding: 18px; border: 1px solid var(--mc-border); border-radius: 12px; background: var(--mc-bg-elevated); }
.semantic-binding-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.semantic-binding-heading h3 { margin: 0; color: var(--mc-text-primary); font-size: 16px; }
.semantic-binding-heading p, .semantic-binding-meta { margin: 5px 0 0; color: var(--mc-text-secondary); font-size: 13px; }
.semantic-binding-controls { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
.semantic-binding-controls .el-select { width: min(420px, 100%); }
.semantic-binding-impact-note { max-width: 720px; }
</style>
