<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ontologyApi } from '../../api/ontologyApi'
import { semanticError, type SemanticError } from '../../api/semanticErrors'
import type { OntologyPackage, PackagePreview, PackageImportResult } from '../../api/types'
import { importDocumentFile } from '../importDocumentFile'
import { useSemanticScope } from '../../shared/useSemanticScope'

const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ created: [result: PackageImportResult] }>()
const { t } = useI18n()
const { workspace, begin, cancel } = useSemanticScope()
const packageData = ref<OntologyPackage | null>(null)
const preview = ref<PackagePreview | null>(null)
const name = ref('')
const operationId = ref('')
const busy = ref(false)
const error = ref<SemanticError | null>(null)
const fileName = ref('')
const rawPackage = ref('')
let fileGeneration = 0

function reset() {
  fileGeneration++
  rawPackage.value = ''
  packageData.value = null
  preview.value = null
  name.value = ''
  operationId.value = ''
  fileName.value = ''
  error.value = null
  busy.value = false
}
watch(open, (value) => { if (!value) { cancel(); reset() } })
watch(() => workspace.currentWorkspaceId, () => { cancel(); reset() }, { flush: 'sync' })

async function readFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const generation = ++fileGeneration
  cancel()
  operationId.value = ''
  packageData.value = null
  rawPackage.value = ''
  fileName.value = file.name
  error.value = null
  preview.value = null
  try {
    if (file.size > 1024 * 1024) throw new Error('package too large')
    const text = await file.text()
    if (generation !== fileGeneration) return
    const imported = importDocumentFile(file.name, text)
    rawPackage.value = imported.rawPackage
    packageData.value = imported.packageData
    name.value = imported.packageData.name
  } catch {
    if (generation !== fileGeneration) return
    packageData.value = null
    error.value = { code: 'INVALID_REQUEST', message: t('semantic.previewRejected'), status: 400, fieldErrors: [] }
  }
}

async function previewPackage() {
  if (!packageData.value || busy.value) return
  const c = begin()
  busy.value = true
  error.value = null
  try {
    const result = await ontologyApi.previewPackage(c.id, rawPackage.value, c.signal)
    if (c.current()) preview.value = result
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}

async function importDraft(recover = false) {
  if ((!packageData.value || !preview.value || !name.value.trim()) && !recover) return
  const c = begin()
  busy.value = true
  error.value = null
  const currentOperation = operationId.value || crypto.randomUUID()
  if (recover) operationId.value = currentOperation
  else operationId.value = ''
  try {
    const result = recover
      ? await ontologyApi.packageImport(c.id, currentOperation, c.signal)
      : await ontologyApi.importPackage(c.id, {
          package: packageData.value!,
          expectedDigest: preview.value!.digest,
          operationId: currentOperation,
          name: name.value.trim(),
        }, c.signal)
    if (c.current()) {
      ElMessage.success(t('semantic.importedDraft'))
      emit('created', result)
      open.value = false
    }
  } catch (e) {
    if (c.current()) {
      error.value = semanticError(e)
      if (!recover && (!error.value.status || error.value.status >= 500)) operationId.value = currentOperation
    }
  } finally {
    if (c.current()) busy.value = false
  }
}
</script>
<template>
  <el-dialog v-model="open" :title="t('semantic.importTitle')" width="min(680px, 95vw)" :close-on-click-modal="false">
    <p class="semantic-muted">{{ t('semantic.importHelp') }}</p>
    <p class="semantic-muted">支持 JSON 模板、Functional Syntax（.ofn/.fss）和 RDF/XML（.rdf/.owl/.xml）。含外部依赖时请使用带锁定 imports 的 JSON 模板。</p>
    <el-form label-position="top">
      <el-form-item :label="t('semantic.choosePackage')" required>
        <input type="file" accept="application/json,application/rdf+xml,.json,.ofn,.fss,.rdf,.owl,.xml" :disabled="busy" @change="readFile" />
        <span v-if="fileName" class="semantic-muted">{{ fileName }}</span>
      </el-form-item>
      <el-form-item v-if="packageData" :label="t('semantic.packageName')" required>
        <el-input v-model="name" maxlength="128" :disabled="busy" />
      </el-form-item>
    </el-form>
    <el-alert v-if="error" type="error" :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))" :description="error.message" :closable="false" />
    <el-button :disabled="!packageData || busy" :loading="busy" @click="previewPackage">{{ t('semantic.preview') }}</el-button>
    <section v-if="preview" class="semantic-panel semantic-package-preview">
      <h3>{{ t('semantic.packageSummary') }}</h3>
      <p>{{ t('semantic.axiomCount', { count: preview.axiomCount }) }} · {{ t('semantic.importCount', { count: preview.importCount }) }}</p>
      <p class="semantic-muted">{{ preview.digest }}</p>
      <el-alert v-if="preview.violations.length" type="warning" :title="t('semantic.previewRejected')" :closable="false">
        <ul><li v-for="violation in preview.violations" :key="violation.path + violation.code">{{ violation.path }}: {{ violation.message }}</li></ul>
      </el-alert>
      <el-alert v-else type="success" :title="t('semantic.previewReady')" :closable="false" />
    </section>
    <el-alert v-if="operationId && error" type="warning" :title="t('semantic.importRecovery')" :closable="false" />
    <template #footer>
      <el-button :disabled="busy" @click="open = false">{{ t('semantic.cancel') }}</el-button>
      <el-button v-if="operationId && error" :loading="busy" @click="importDraft(true)">{{ t('semantic.recoverImport') }}</el-button>
      <el-button type="primary" :loading="busy" :disabled="!preview || !!preview.violations.length || !name.trim()" @click="importDraft()">{{ t('semantic.importDraft') }}</el-button>
    </template>
  </el-dialog>
</template>
