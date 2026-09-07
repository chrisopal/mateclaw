<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { ontologyApi } from '../api/ontologyApi'
import { semanticError, type SemanticError } from '../api/semanticErrors'
import type { Ontology, Revision, Diff } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'
import './semantic.css'
const { t, locale } = useI18n(),
  route = useRoute(),
  router = useRouter(),
  { workspace, begin } = useSemanticScope()
const ontologyId = () => String(route.params.id),
  ontology = ref<Ontology | null>(null),
  revisions = ref<Revision[]>([]),
  selectedId = ref(''),
  from = ref(''),
  to = ref(''),
  diff = ref<Diff | null>(null),
  busy = ref(false),
  error = ref<SemanticError | null>(null)
const selected = computed(() => revisions.value.find((r) => r.id === selectedId.value))
function displayTime(iso: string) {
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(locale.value, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        timeZoneName: 'short',
      })
}
async function load() {
  const c = begin()
  busy.value = true
  error.value = null
  try {
    const [o, list] = await Promise.all([
      ontologyApi.get(c.id, ontologyId(), c.signal),
      ontologyApi.revisions(c.id, ontologyId(), c.signal),
    ])
    if (c.current()) {
      ontology.value = o
      revisions.value = [...list].sort((a, b) => b.version - a.version)
      selectedId.value = String(route.query.revision || revisions.value[0]?.id || '')
      from.value = revisions.value[1]?.id ?? ''
      to.value = revisions.value[0]?.id ?? ''
    }
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
async function compare() {
  if (!to.value) return
  const c = begin()
  busy.value = true
  error.value = null
  diff.value = null
  try {
    const result = await ontologyApi.diff(c.id, ontologyId(), from.value || null, to.value, c.signal)
    if (c.current()) diff.value = result
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
async function createDraft() {
  if (busy.value || !workspace.can('manage:ontology')) return
  const c = begin()
  busy.value = true
  error.value = null
  try {
    await ontologyApi.createDraft(c.id, ontologyId(), selectedId.value || null, c.signal)
    if (c.current()) await router.push({ name: 'OntologyEditor', params: { id: ontologyId() } })
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
async function changeAvailability() {
  const revision = selected.value
  if (!revision || !workspace.can('publish:ontology')) return
  try {
    await ElMessageBox.confirm(t('semantic.availabilityWarning'), t('semantic.availability'), {
      confirmButtonText: t('semantic.confirm'),
      cancelButtonText: t('semantic.cancel'),
    })
  } catch {
    return
  }
  const c = begin()
  busy.value = true
  error.value = null
  try {
    const updated = await ontologyApi.availability(
      c.id,
      ontologyId(),
      revision.id,
      !revision.availableForNewBindings,
      c.signal,
    )
    if (c.current()) revisions.value = revisions.value.map((r) => (r.id === updated.id ? updated : r))
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <section class="semantic-page">
    <header class="semantic-header">
      <div>
        <h1>{{ ontology?.name || t('semantic.history') }}</h1>
        <span class="semantic-muted">{{ t('semantic.historyHelp') }}</span>
      </div>
      <div class="semantic-actions">
        <el-button @click="router.push({ name: 'OntologyList' })">{{ t('semantic.back') }}</el-button
        ><el-button
          v-if="ontology?.hasDraft"
          @click="router.push({ name: 'OntologyEditor', params: { id: ontologyId() } })"
          >{{ t('semantic.openDraft') }}</el-button
        ><el-button
          v-if="workspace.can('manage:ontology') && !ontology?.hasDraft"
          type="primary"
          :disabled="busy"
          @click="createDraft"
          >{{ t(selected ? 'semantic.copyDraft' : 'semantic.newDraft') }}</el-button
        >
      </div>
    </header>
    <el-alert
      v-if="error"
      type="error"
      :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))"
      :description="error.message"
      :closable="false"
    />
    <div v-loading="busy" class="semantic-panel">
      <el-table
        :data="revisions"
        :empty-text="t('semantic.noVersions')"
        highlight-current-row
        @row-click="selectedId = $event.id"
        ><el-table-column :label="t('semantic.version')" width="100"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="selectedId = row.id"
              >v{{ row.version }}</el-button
            ></template
          ></el-table-column
        ><el-table-column prop="name" :label="t('semantic.name')" min-width="180" /><el-table-column
          prop="publicationNote"
          :label="t('semantic.publicationNote')"
          min-width="200"
        /><el-table-column :label="t('semantic.publishedAt')" min-width="190"
          ><template #default="{ row }"
            ><span :title="row.publishedAt">{{ displayTime(row.publishedAt) }}</span></template
          ></el-table-column
        ><el-table-column :label="t('semantic.availability')" width="150"
          ><template #default="{ row }">{{
            t(row.availableForNewBindings ? 'semantic.available' : 'semantic.unavailable')
          }}</template></el-table-column
        ></el-table
      >
    </div>
    <div v-if="selected" class="semantic-panel">
      <div class="semantic-header">
        <h2>{{ t('semantic.version') }} v{{ selected.version }} · {{ selected.name }}</h2>
        <el-button v-if="workspace.can('publish:ontology')" :disabled="busy" @click="changeAvailability">{{
          t(selected.availableForNewBindings ? 'semantic.disableAvailability' : 'semantic.enableAvailability')
        }}</el-button>
      </div>
      <p>{{ selected.description }}</p>
      <p class="semantic-muted">
        {{ t('semantic.publishedBy') }} {{ selected.publishedBy }} ·
        <span :title="selected.publishedAt">{{ displayTime(selected.publishedAt) }}</span>
      </p>
      <el-tabs
        ><el-tab-pane
          v-for="category in ['types', 'properties', 'relations'] as const"
          :key="category"
          :label="t('semantic.' + category) + ' (' + selected.definition[category].length + ')'"
          ><el-table :data="selected.definition[category]" :empty-text="t('semantic.emptyDefinitions')"
            ><el-table-column prop="key" :label="t('semantic.key')" min-width="140" /><el-table-column
              prop="label"
              :label="t('semantic.label')"
              min-width="140"
            /><el-table-column
              prop="description"
              :label="t('semantic.description')"
              min-width="160"
            /><el-table-column
              v-if="category === 'properties'"
              prop="ownerTypeKey"
              :label="t('semantic.ownerTypeKey')"
              min-width="130"
            /><el-table-column
              v-if="category === 'properties'"
              :label="t('semantic.valueType')"
              min-width="110"
              ><template #default="{ row }">{{
                t('semantic.values.' + row.valueType)
              }}</template></el-table-column
            ><el-table-column
              v-if="category === 'properties'"
              prop="fixedUnit"
              :label="t('semantic.fixedUnit')"
              min-width="100"
            /><el-table-column
              v-if="category === 'relations'"
              prop="sourceTypeKey"
              :label="t('semantic.sourceTypeKey')"
              min-width="130"
            /><el-table-column
              v-if="category === 'relations'"
              prop="targetTypeKey"
              :label="t('semantic.targetTypeKey')"
              min-width="130"
            /><el-table-column v-if="category !== 'types'" :label="t('semantic.multiplicity')" min-width="100"
              ><template #default="{ row }">{{
                t('semantic.values.' + row.multiplicity)
              }}</template></el-table-column
            ></el-table
          ></el-tab-pane
        ></el-tabs
      >
    </div>
    <div v-if="revisions.length" class="semantic-panel">
      <h3>{{ t('semantic.compare') }}</h3>
      <div class="semantic-toolbar">
        <el-select v-model="from" :aria-label="t('semantic.from')" style="width: 180px" @change="diff = null"
          ><el-option value="" :label="t('semantic.emptyBaseline')" /><el-option
            v-for="revision in revisions"
            :key="revision.id"
            :value="revision.id"
            :label="'v' + revision.version" /></el-select
        ><span>{{ t('semantic.to') }}</span
        ><el-select v-model="to" :aria-label="t('semantic.to')" style="width: 180px" @change="diff = null"
          ><el-option
            v-for="revision in revisions"
            :key="revision.id"
            :value="revision.id"
            :label="'v' + revision.version" /></el-select
        ><el-button :disabled="busy || !to || from === to" @click="compare">{{
          t('semantic.compare')
        }}</el-button>
      </div>
      <el-table v-if="diff" :data="diff.changes" :empty-text="t('semantic.noChanges')"
        ><el-table-column :label="t('semantic.change')" width="100"
          ><template #default="{ row }">{{ t('semantic.changes.' + row.kind) }}</template></el-table-column
        ><el-table-column :label="t('semantic.category')" width="110"
          ><template #default="{ row }">{{ t('semantic.' + row.category) }}</template></el-table-column
        ><el-table-column prop="key" :label="t('semantic.key')" min-width="120" /><el-table-column
          :label="t('semantic.before')"
          min-width="220"
          ><template #default="{ row }">
            <pre>{{ JSON.stringify(row.before, null, 2) }}</pre>
          </template></el-table-column
        ><el-table-column :label="t('semantic.after')" min-width="220"
          ><template #default="{ row }">
            <pre>{{ JSON.stringify(row.after, null, 2) }}</pre>
          </template></el-table-column
        ></el-table
      >
    </div>
  </section>
</template>
