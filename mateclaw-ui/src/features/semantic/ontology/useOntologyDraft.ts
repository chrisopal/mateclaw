import { computed, onScopeDispose, ref, watch } from 'vue'
import { ontologyApi, type OntologyApi } from '../api/ontologyApi'
import { semanticError, type SemanticError } from '../api/semanticErrors'
import type { AxiomDescriptor, DocumentInput, Draft, EditDraft, PublishDraft, Revision, SaveDraft, ValidationReport } from '../api/types'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
type PendingEdit = { scope: string; ontology: string; body: EditDraft }
export function useOntologyDraft(ontologyId: () => string, workspaceId: () => string, api: OntologyApi = ontologyApi, canEdit: () => boolean = () => true) {
  const document = ref<DocumentInput | null>(null)
  const axioms = ref<AxiomDescriptor[]>([])
  const name = ref('')
  const description = ref('')
  const id = ref<string | null>(null)
  const baseRevisionId = ref<string | null>(null)
  const draftVersion = ref(0)
  const version = ref(0)
  const saved = ref('')
  const busy = ref(false)
  const saveError = ref<SemanticError | null>(null)
  const validationReport = ref<ValidationReport | null>(null)
  const pendingPublication = ref<{ scope: string; ontology: string; body: PublishDraft } | null>(null)
  const pendingEdit = ref<PendingEdit | null>(null)
  const snapshot = () => JSON.stringify({ name: name.value, description: description.value, document: document.value })
  const dirty = computed(() => id.value !== null && snapshot() !== saved.value)
  const publicationPending = computed(() => pendingPublication.value !== null)
  const editPending = computed(() => pendingEdit.value !== null)
  const canPublish = computed(() => !!id.value && !dirty.value && !busy.value && !publicationPending.value && !editPending.value && validationReport.value?.valid === true && validationReport.value.draftVersion === draftVersion.value)
  let generation = 0
  let controller: AbortController | null = null
  function cancel() { generation++; controller?.abort(); controller = null; busy.value = false }
  function context() { cancel(); controller = new AbortController(); busy.value = true; saveError.value = null; return { ws: workspaceId(), ontology: ontologyId(), generation, signal: controller.signal } }
  type Context = ReturnType<typeof context>
  const current = (c: Context) => c.generation === generation && c.ws === workspaceId() && c.ontology === ontologyId() && !c.signal.aborted
  function apply(value: Draft) { id.value = value.id; baseRevisionId.value = value.baseRevisionId; draftVersion.value = value.draftVersion; version.value = value.version; name.value = value.name; description.value = value.description; document.value = clone(value.document.source); axioms.value = clone(value.document.axioms); saved.value = snapshot(); validationReport.value = null }
  function failure(c: Context, error: unknown) { if (current(c)) saveError.value = semanticError(error) }
  function finish(c: Context) { if (current(c)) busy.value = false }
  async function load() { if (publicationPending.value || editPending.value) return false; const c = context(); try { const value = await api.getDraft(c.ws, c.ontology, c.signal); if (current(c)) { apply(value); return true } } catch (error) { failure(c, error) } finally { finish(c) } return false }
  async function save() {
    if (!id.value || !document.value || busy.value || publicationPending.value || editPending.value) return false
    const c = context(); const sent = snapshot()
    try {
      const value = await api.saveDraft(c.ws, c.ontology, { expectedDraftVersion: draftVersion.value, name: name.value, description: description.value, document: clone(document.value), operationId: crypto.randomUUID() } satisfies SaveDraft, c.signal)
      if (current(c)) { if (snapshot() === sent) apply(value); else { draftVersion.value = value.draftVersion; saved.value = JSON.stringify({ name: value.name, description: value.description, document: value.document?.source }); validationReport.value = null }; return true }
    } catch (error) { failure(c, error) } finally { finish(c) }
    return false
  }
  function transportAmbiguous(error: unknown) { const status = semanticError(error).status; return status === 0 || status >= 500 }
  async function runEdit(pending: PendingEdit, retry: boolean) {
    const c = context()
    try {
      const value = await (retry ? api.retryEdit : api.editDraft)(c.ws, c.ontology, pending.body, c.signal)
      if (current(c)) { pendingEdit.value = null; apply(value); return true }
    } catch (error) {
      if (current(c)) {
        if (transportAmbiguous(error)) pendingEdit.value = pending
        else pendingEdit.value = null
        failure(c, error)
      }
    } finally { finish(c) }
    return false
  }
  async function edit(changes: EditDraft['changes']) {
    if (!id.value || dirty.value || busy.value || publicationPending.value || editPending.value || !canEdit() || !changes.length) return false
    const pending: PendingEdit = { scope: workspaceId(), ontology: ontologyId(), body: { expectedDraftVersion: draftVersion.value, changes: clone(changes), operationId: crypto.randomUUID() } }
    pendingEdit.value = pending
    return runEdit(pending, false)
  }
  async function retryEdit() {
    const pending = pendingEdit.value
    if (!pending || busy.value || !canEdit() || pending.scope !== workspaceId() || pending.ontology !== ontologyId()) return false
    return runEdit(pending, true)
  }
  async function validate() { if (dirty.value || !id.value || busy.value || publicationPending.value || editPending.value) return false; const c = context(); const sent = snapshot(); try { const report = await api.validate(c.ws, c.ontology, draftVersion.value, c.signal); if (current(c) && snapshot() === sent && report.draftVersion === draftVersion.value) { validationReport.value = report; return report.valid } } catch (error) { failure(c, error) } finally { finish(c) } return false }
  async function publish(note: string): Promise<Revision | null> {
    if ((!publicationPending.value && (!canPublish.value || !note.trim())) || busy.value || editPending.value) return null
    const c = context(); pendingPublication.value ??= { scope: c.ws, ontology: c.ontology, body: { expectedDraftVersion: draftVersion.value, operationId: crypto.randomUUID(), note: note.trim() } }; const pending = pendingPublication.value
    try { let revision: Revision; try { revision = await api.publish(c.ws, c.ontology, pending.body, c.signal) } catch (error) { const failure = semanticError(error); if (failure.status && failure.status < 500) { if (current(c)) pendingPublication.value = null; throw error } revision = (await api.operation(c.ws, pending.body.operationId, c.signal)).result }
      if (current(c)) { pendingPublication.value = null; id.value = null; saved.value = snapshot(); validationReport.value = null; return revision }
    } catch (error) { failure(c, error) } finally { finish(c) }
    return null
  }
  async function discard() { if (!id.value || busy.value || publicationPending.value || editPending.value) return false; const c = context(); try { await api.discard(c.ws, id.value, draftVersion.value, c.signal); if (current(c)) { id.value = null; document.value = null; saved.value = snapshot(); validationReport.value = null; return true } } catch (error) { failure(c, error) } finally { finish(c) } return false }
  watch([name, description, document], () => { validationReport.value = null }, { deep: true, flush: 'sync' })
  watch(() => [workspaceId(), ontologyId()], () => { cancel(); id.value = null; document.value = null; name.value = ''; description.value = ''; saved.value = ''; validationReport.value = null; saveError.value = null; pendingPublication.value = null; pendingEdit.value = null }, { flush: 'sync' })
  onScopeDispose(cancel)
  return { document, axioms, name, description, id, baseRevisionId, draftVersion, version, dirty, busy, validationReport, saveError, canPublish, publicationPending, editPending, load, save, edit, retryEdit, validate, publish, discard, cancel }
}
