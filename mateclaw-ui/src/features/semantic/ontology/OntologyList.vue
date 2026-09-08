<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { vLoading } from 'element-plus'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ontologyApi } from '../api/ontologyApi'
import { semanticError, type SemanticError } from '../api/semanticErrors'
import type { Ontology, PackageImportResult } from '../api/types'
import OntologyWorkbenchDialog from './components/OntologyWorkbenchDialog.vue'
import OntologyPackageDialog from './components/OntologyPackageDialog.vue'
import { useSemanticScope } from '../shared/useSemanticScope'
import './semantic.css'
const { t } = useI18n(),
  router = useRouter(),
  { workspace, begin } = useSemanticScope()
const workbenchOntology = ref<Ontology | null>(null)
const items = ref<Ontology[]>([]),
  total = ref(0),
  page = ref(1),
  q = ref(''),
  busy = ref(false),
  error = ref<SemanticError | null>(null)
const creating = ref(false),
  importing = ref(false),
  launching = ref(false),
  name = ref(''),
  description = ref(''),
  createdId = ref<string | null>(null)
let launchGeneration = 0
watch(
  () => workspace.currentWorkspaceId,
  () => {
    workbenchOntology.value = null
    launchGeneration++
    launching.value = false
    busy.value = false
    error.value = null
  },
  { flush: 'sync' },
)
async function load() {
  const c = begin()
  busy.value = true
  error.value = null
  try {
    const result = await ontologyApi.list(c.id, q.value, page.value, c.signal)
    if (c.current()) {
      items.value = result.items
      total.value = result.total
    }
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
async function create() {
  if (!name.value.trim() || busy.value || !workspace.can('manage:ontology')) return
  const c = begin()
  busy.value = true
  error.value = null
  try {
    let id = createdId.value
    if (!id) {
      const created = await ontologyApi.create(
        c.id,
        { name: name.value.trim(), description: description.value },
        c.signal,
      )
      id = created.id
      if (c.current()) createdId.value = id
    }
    await ontologyApi.createDraft(c.id, id, null, c.signal)
    if (c.current()) await router.push({ name: 'OntologyEditor', params: { id } })
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
function openCreate() {
  createdId.value = null
  name.value = ''
  description.value = ''
  creating.value = true
}
function openImport() {
  importing.value = true
}
async function launchBuilder() {
  if (launching.value || !workspace.can('manage:ontology')) return
  const c = begin()
  const requestGeneration = ++launchGeneration
  // begin() cancels a prior list/create request; clear its indicator because
  // the canceled request's stale finally block must not own this component's UI.
  busy.value = false
  launching.value = true
  error.value = null
  try {
    const result = await ontologyApi.ensureBuilder(c.id, c.signal)
    if (c.current()) {
      await router.push({ path: '/chat', query: { agentId: String(result.agentId) } })
    }
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (requestGeneration === launchGeneration) launching.value = false
  }
}
function imported(result: PackageImportResult) {
  void router.push({ name: 'OntologyEditor', params: { id: result.ontologyId } })
}
onMounted(load)
</script>
<template>
  <section class="semantic-page">
    <OntologyWorkbenchDialog v-if="workbenchOntology" :ontology="workbenchOntology" @close="workbenchOntology = null" />
    <header class="semantic-header">
      <div>
        <h1>{{ t('semantic.title') }}</h1>
        <div class="semantic-muted">{{ t('semantic.boundary') }}</div>
      </div>
      <div class="semantic-actions">
        <el-button v-if="workspace.can('manage:ontology')" :loading="launching" @click="launchBuilder">{{ t('semantic.generateFromSource') }}</el-button>
        <el-button v-if="workspace.can('manage:ontology')" @click="openImport">{{ t('semantic.importTemplate') }}</el-button>
        <el-button v-if="workspace.can('manage:ontology')" type="primary" @click="openCreate">{{ t('semantic.create') }}</el-button>
      </div>
    </header>
    <el-alert
      v-if="error"
      type="error"
      :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))"
      :description="error.message"
      :closable="false"
    />
    <div class="semantic-panel">
      <div class="semantic-toolbar">
        <el-input
          v-model="q"
          :aria-label="t('semantic.search')"
          :placeholder="t('semantic.search')"
          clearable
          @keyup.enter="search"
        /><el-button :loading="busy" @click="search">{{ t('semantic.searchAction') }}</el-button>
      </div>
      <el-table v-loading="busy" :data="items" :empty-text="t('semantic.empty')">
        <el-table-column prop="name" :label="t('semantic.name')" min-width="180" /><el-table-column
          prop="description"
          :label="t('semantic.description')"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column :label="t('semantic.version')" width="140"
          ><template #default="{ row }">{{
            row.latestVersion === null ? t('semantic.unpublished') : 'v' + row.latestVersion
          }}</template></el-table-column
        >
        <el-table-column :label="t('semantic.state')" width="120"
          ><template #default="{ row }">{{
            t(
              row.hasDraft
                ? 'semantic.draft'
                : row.latestVersion === null
                  ? 'semantic.unpublished'
                  : 'semantic.published',
            )
          }}</template></el-table-column
        >
        <el-table-column :label="t('semantic.actions')" width="310"
          ><template #default="{ row }"
            ><el-button
              v-if="row.hasDraft"
              link
              type="primary"
              @click="router.push({ name: 'OntologyEditor', params: { id: row.id } })"
              >{{ t(workspace.can('manage:ontology') ? 'semantic.edit' : 'semantic.view') }}</el-button
            ><el-button
              link
              type="primary"
              @click="router.push({ name: 'OntologyVersions', params: { id: row.id } })"
              >{{ t('semantic.history') }}</el-button
            ><el-button type="primary" plain @click="workbenchOntology = row">{{ t('semantic.w.workbench') }}</el-button></template
          ></el-table-column
        >
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :total="total"
        :page-size="20"
        layout="prev, pager, next, total"
        @current-change="load"
      />
    </div>
    <el-dialog
      v-model="creating"
      :title="t('semantic.create')"
      width="min(520px, 95vw)"
      :close-on-click-modal="false"
      ><el-form label-position="top" @submit.prevent="create"
        ><el-form-item :label="t('semantic.name')" required
          ><el-input v-model="name" :disabled="!!createdId" maxlength="128" /></el-form-item
        ><el-form-item :label="t('semantic.description')"
          ><el-input
            v-model="description"
            :disabled="!!createdId"
            type="textarea"
            maxlength="1000"
            :rows="3" /></el-form-item
        ><el-alert
          v-if="error"
          type="error"
          :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))"
          :description="error.message"
          :closable="false" /></el-form
      ><template #footer
        ><el-button @click="creating = false">{{ t('semantic.cancel') }}</el-button
        ><el-button type="primary" :loading="busy" :disabled="!name.trim()" @click="create">{{
          t(createdId ? 'semantic.retry' : 'semantic.create')
        }}</el-button></template
      ></el-dialog
    >
    <OntologyPackageDialog v-model="importing" @created="imported" />
  </section>
</template>
