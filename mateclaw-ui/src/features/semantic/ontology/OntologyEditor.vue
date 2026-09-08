<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessageBox, ElMessage, vLoading } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { ontologyApi } from '../api/ontologyApi'
import type { EntityType, Property, Relation, Definition, DefinitionCategory, Diff } from '../api/types'
import { useOntologyDraft } from './useOntologyDraft'
import EntityTypeEditor from './components/EntityTypeEditor.vue'
import PropertyEditor from './components/PropertyEditor.vue'
import RelationEditor from './components/RelationEditor.vue'
import ValidationPanel from './components/ValidationPanel.vue'
import PublishDialog from './components/PublishDialog.vue'
import OntologyImpactPanel from './components/OntologyImpactPanel.vue'
import OntologyStructureGraph from './components/OntologyStructureGraph.vue'
import './semantic.css'
const { t } = useI18n(),
  route = useRoute(),
  router = useRouter(),
  workspace = useWorkspaceStore()
const ontologyId = () => String(route.params.id),
  workspaceId = () => workspace.currentWorkspaceId ?? ''
const draft = useOntologyDraft(ontologyId, workspaceId)
const {
  form,
  name,
  description,
  id,
  dirty,
  busy,
  version,
  draftVersion,
  validationReport,
  saveError,
  canPublish,
  publicationPending,
} = draft
const editable = computed(() => workspace.can('manage:ontology') && !publicationPending.value)
const termSearch = ref('')
function visibleTerms(category: DefinitionCategory) {
  const query = termSearch.value.trim().toLocaleLowerCase()
  return form.value[category].filter((term) => !query || [term.key, term.label, ...(term.aliases ?? [])].some((text) => text.toLocaleLowerCase().includes(query)))
}
const tab = ref<keyof Definition>('types'),
  drawer = ref(false),
  editingIndex = ref(-1)
const editing = ref<EntityType | Property | Relation>({ key: '', label: '', description: '' }),
  editingBaseline = ref('')
const drawerDirty = computed(() => drawer.value && JSON.stringify(editing.value) !== editingBaseline.value)
const unsaved = computed(() => dirty.value || drawerDirty.value)
const publishOpen = ref(false),
  diff = ref<Diff | null>(null),
  diffBusy = ref(false)
let diffController: AbortController | null = null
async function confirmLeave() {
  if (publicationPending.value) {
    ElMessage.warning(t('semantic.pendingPublication'))
    return false
  }
  if (!unsaved.value) return true
  try {
    await ElMessageBox.confirm(t('semantic.leaveWarning'), t('semantic.unsaved'), {
      confirmButtonText: t('semantic.leave'),
      cancelButtonText: t('semantic.stay'),
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}
const unregister = workspace.registerBeforeSwitch(confirmLeave)
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(confirmLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (unsaved.value || publicationPending.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  unregister()
  window.removeEventListener('beforeunload', beforeUnload)
  diffController?.abort()
})
function openRow(category: DefinitionCategory, index = -1) {
  tab.value = category
  editingIndex.value = index
  editing.value =
    index >= 0
      ? JSON.parse(JSON.stringify(form.value[category][index]))
      : category === 'types'
        ? { key: '', label: '', description: '' }
        : category === 'properties'
          ? {
              key: '',
              label: '',
              description: '',
              ownerTypeKey: '',
              valueType: 'TEXT',
              multiplicity: 'SINGLE',
              fixedUnit: null,
            }
          : {
              key: '',
              label: '',
              description: '',
              sourceTypeKey: '',
              targetTypeKey: '',
              multiplicity: 'SINGLE',
            }
  editingBaseline.value = JSON.stringify(editing.value)
  drawer.value = true
}
function applyRow() {
  if (!editable.value || !editing.value.key.trim() || !editing.value.label.trim()) return
  const rows = form.value[tab.value] as EntityType[]
  if (rows.some((row, index) => index !== editingIndex.value && row.key === editing.value.key)) {
    ElMessage.warning(t('semantic.duplicateKey'))
    return
  }
  if (tab.value === 'types' && editingIndex.value >= 0) {
    const previous = rows[editingIndex.value].key
    if (
      previous !== editing.value.key &&
      (form.value.properties.some((p) => p.ownerTypeKey === previous) ||
        form.value.relations.some((r) => r.sourceTypeKey === previous || r.targetTypeKey === previous))
    ) {
      ElMessage.warning(t('semantic.referenced'))
      return
    }
  }
  const value = JSON.parse(JSON.stringify(editing.value))
  if (editingIndex.value < 0) rows.push(value)
  else rows[editingIndex.value] = value
  drawer.value = false
}
async function closeDrawer(done: () => void) {
  if (!drawerDirty.value) {
    done()
    return
  }
  try {
    await ElMessageBox.confirm(t('semantic.leaveWarning'), t('semantic.unsaved'), {
      confirmButtonText: t('semantic.leave'),
      cancelButtonText: t('semantic.stay'),
    })
    done()
  } catch {
    /* keep input */
  }
}
async function removeRow(category: DefinitionCategory, index: number) {
  if (!editable.value) return
  const key = form.value[category][index].key
  if (
    category === 'types' &&
    (form.value.properties.some((p) => p.ownerTypeKey === key) ||
      form.value.relations.some((r) => r.sourceTypeKey === key || r.targetTypeKey === key))
  ) {
    ElMessage.warning(t('semantic.referenced'))
    return
  }
  try {
    await ElMessageBox.confirm(t('semantic.deleteWarning', { key }), t('semantic.delete'), {
      confirmButtonText: t('semantic.delete'),
      cancelButtonText: t('semantic.cancel'),
      type: 'warning',
    })
    form.value[category].splice(index, 1)
  } catch {
    /* cancelled */
  }
}
async function locate(path: string) {
  if (path === 'types' || path === 'properties' || path === 'relations') {
    openRow(path)
    return
  }
  const match = path.match(/(types|properties|relations)\[(\d+)\](?:\.(\w+))?/)
  if (match) {
    const category = match[1] as DefinitionCategory,
      index = Number(match[2])
    if (form.value[category][index]) {
      openRow(category, index)
      await nextTick()
      setTimeout(
        () =>
          document
            .querySelector<HTMLInputElement>(
              `.semantic-drawer [data-semantic-field="${match[3] ?? 'key'}"] input, .semantic-drawer input[data-semantic-field="${match[3] ?? 'key'}"]`,
            )
            ?.focus(),
        250,
      )
    }
  } else {
    document.getElementById(path === 'description' ? 'ontology-description' : 'ontology-name')?.focus()
  }
}
async function preparePublish() {
  if (!canPublish.value) return
  diffBusy.value = true
  const ws = workspaceId(),
    oid = ontologyId()
  diffController?.abort()
  diffController = new AbortController()
  try {
    const result = await ontologyApi.diff(
      ws,
      oid,
      draft.baseRevisionId.value,
      id.value!,
      diffController.signal,
    )
    if (ws === workspaceId() && oid === ontologyId()) {
      diff.value = result
      publishOpen.value = true
    }
  } catch {
    ElMessage.error(t('semantic.requestFailed'))
  } finally {
    diffBusy.value = false
  }
}
async function publish(note: string) {
  const result = await draft.publish(note)
  if (result) {
    publishOpen.value = false
    await router.push({
      name: 'OntologyVersions',
      params: { id: ontologyId() },
      query: { revision: result.id },
    })
  }
}
async function discard() {
  try {
    await ElMessageBox.confirm(t('semantic.discardWarning'), t('semantic.discard'), {
      confirmButtonText: t('semantic.discard'),
      cancelButtonText: t('semantic.cancel'),
      type: 'warning',
    })
    if (await draft.discard()) await router.push({ name: 'OntologyVersions', params: { id: ontologyId() } })
  } catch {
    /* cancelled */
  }
}
onMounted(draft.load)
</script>
<template>
  <section class="semantic-page">
    <header class="semantic-header">
      <div>
        <h1>{{ name || t('semantic.draft') }}</h1>
        <span class="semantic-muted"
          >{{ t('semantic.draft') }} · v{{ version }} · {{ t('semantic.draftVersion') }} {{ draftVersion }} ·
          {{ t(dirty ? 'semantic.unsaved' : 'semantic.saved') }}</span
        >
      </div>
      <div class="semantic-actions">
        <el-button @click="router.push({ name: 'OntologyList' })">{{ t('semantic.back') }}</el-button
        ><el-button @click="router.push({ name: 'OntologyVersions', params: { id: ontologyId() } })">{{
          t('semantic.history')
        }}</el-button
        ><template v-if="editable && id"
          ><el-button :disabled="busy" @click="discard">{{ t('semantic.discard') }}</el-button
          ><el-button :disabled="!dirty || busy" @click="draft.save">{{ t('semantic.save') }}</el-button
          ><el-button :disabled="dirty || busy" @click="draft.validate">{{
            t('semantic.validate')
          }}</el-button></template
        ><el-button
          v-if="workspace.can('publish:ontology') && id"
          type="primary"
          :disabled="!canPublish || drawerDirty"
          :loading="diffBusy"
          @click="preparePublish"
          >{{ t('semantic.publish') }}</el-button
        >
      </div>
    </header>
    <el-alert
      v-if="publicationPending"
      type="warning"
      :title="t('semantic.pendingPublication')"
      :closable="false"
    /><el-button v-if="publicationPending" :loading="busy" @click="publish('')">{{
      t('semantic.recoverPublication')
    }}</el-button>
    <el-alert
      v-if="!editable && !publicationPending"
      type="info"
      :title="t('semantic.readonly')"
      :closable="false"
    />
    <el-alert
      v-if="saveError"
      type="error"
      :title="t('semantic.errors.' + saveError.code, t('semantic.requestFailed'))"
      :description="saveError.message"
      :closable="false"
    />
    <el-button v-if="!id && !busy" @click="draft.load">{{ t('semantic.retry') }}</el-button>
    <template v-if="id"
      ><div v-loading="busy" class="semantic-panel">
        <el-form label-position="top" :disabled="!editable || busy"
          ><div class="semantic-grid">
            <el-form-item :label="t('semantic.name')" required
              ><el-input id="ontology-name" v-model="name" maxlength="128" /></el-form-item
            ><el-form-item :label="t('semantic.description')"
              ><el-input id="ontology-description" v-model="description" type="textarea" :rows="2"
            /></el-form-item></div
        ></el-form>
        <details class="ontology-draft-graph">
          <summary>{{ t('semantic.structure.draftPreview') }}</summary>
          <OntologyStructureGraph :definition="form" />
        </details>
        <el-tabs v-model="tab"
          ><el-tab-pane
            v-for="category in ['types', 'properties', 'relations'] as const"
            :key="category"
            :name="category"
            :label="t('semantic.' + category) + ' (' + form[category].length + ')'"
            ><div class="semantic-toolbar">
              <el-button v-if="editable" :disabled="busy" @click="openRow(category)">{{
                t('semantic.add')
              }}</el-button>
              <el-input v-model="termSearch" clearable :placeholder="t('semantic.termSearch')" :aria-label="t('semantic.termSearch')" style="max-width: 320px" />
            </div>
            <el-table :data="visibleTerms(category)" :empty-text="t('semantic.emptyDefinitions')"
              ><el-table-column prop="key" :label="t('semantic.key')" min-width="140" /><el-table-column
                prop="label"
                :label="t('semantic.label')"
                min-width="140"
              /><el-table-column
                v-if="category === 'properties'"
                prop="ownerTypeKey"
                :label="t('semantic.ownerTypeKey')"
                min-width="140"
              /><el-table-column
                v-if="category === 'properties'"
                :label="t('semantic.valueType')"
                min-width="120"
                ><template #default="{ row }">{{
                  row.valueType ? t('semantic.values.' + row.valueType) : '—'
                }}</template></el-table-column
              ><el-table-column
                v-if="category === 'relations'"
                prop="sourceTypeKey"
                :label="t('semantic.sourceTypeKey')"
                min-width="140"
              /><el-table-column
                v-if="category === 'relations'"
                prop="targetTypeKey"
                :label="t('semantic.targetTypeKey')"
                min-width="140"
              /><el-table-column
                prop="description"
                :label="t('semantic.description')"
                min-width="180"
                show-overflow-tooltip
              /><el-table-column :label="t('semantic.actions')" width="150"
                ><template #default="{ row }"
                  ><el-button link type="primary" :disabled="busy" @click="openRow(category, form[category].indexOf(row))">{{
                    t(editable ? 'semantic.edit' : 'semantic.view')
                  }}</el-button
                  ><el-button
                    v-if="editable"
                    link
                    type="danger"
                    :disabled="busy"
                    :aria-label="t('semantic.delete') + ' ' + row.key"
                    @click="removeRow(category, form[category].indexOf(row))"
                    >{{ t('semantic.delete') }}</el-button
                  ></template
                ></el-table-column
              ></el-table
            ></el-tab-pane
          ></el-tabs
        >
      </div>
      <ValidationPanel
        :report="validationReport"
        :errors="saveError?.fieldErrors"
        :dirty="dirty"
        @locate="locate"
      />
      <OntologyImpactPanel
        v-if="workspace.can('publish:ontology')"
        :ontology-id="ontologyId()"
        :expected-draft-version="!dirty ? draftVersion : undefined"
        :enabled="!dirty && !!id"
      />
    </template>
    <el-drawer
      v-model="drawer"
      class="semantic-drawer"
      :title="t('semantic.' + tab)"
      :before-close="closeDrawer"
      :close-on-click-modal="false"
      ><EntityTypeEditor
        v-if="tab === 'types'"
        v-model="editing as EntityType"
        :readonly="!editable"
      /><PropertyEditor
        v-else-if="tab === 'properties'"
        v-model="editing as Property"
        :types="form.types"
        :readonly="!editable"
      /><RelationEditor
        v-else
        v-model="editing as Relation"
        :types="form.types"
        :readonly="!editable"
      /><template #footer
        ><el-button @click="closeDrawer(() => (drawer = false))">{{ t('semantic.cancel') }}</el-button
        ><el-button
          v-if="editable"
          type="primary"
          :disabled="!editing.key.trim() || !editing.label.trim()"
          @click="applyRow"
          >{{ t('semantic.apply') }}</el-button
        ></template
      ></el-drawer
    >
    <PublishDialog
      v-model="publishOpen"
      :busy="busy"
      :diff="diff"
      :ontology-id="ontologyId()"
      :expected-draft-version="draftVersion"
      :error="saveError ? t('semantic.errors.' + saveError.code, t('semantic.requestFailed')) : undefined"
      @publish="publish"
    />
  </section>
</template>
