import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import Workbench from '../pages/PresalesWorkbench.vue'
import { presalesApi } from '../api/presalesApi'
vi.mock('../api/presalesApi', () => ({ presalesApi: Object.fromEntries(['members', 'capabilities', 'list', 'get', 'create', 'update', 'command', 'employees', 'generate', 'evidence', 'file', 'handoff', 'sources', 'statements'].map(key => [key, vi.fn()])) }))
vi.mock('../shared/locale', () => ({ label: (_zh: string, en: string) => en }))
vi.mock('@/stores/useWorkspaceStore', () => ({ useWorkspaceStore: () => ({ currentWorkspaceId: '90071992547409999', registerBeforeSwitch: () => () => {} }) }))
const project = { id: '90071992547409998', workspaceId: '90071992547409999', version: 3, name: 'Plant A', customer: 'Customer', ownerId: '90071992547409997', status: 'ACTIVE', materials: [], requirements: [], clarifications: [], baselines: [], fitGaps: [], solutions: [], reviews: [], releases: [], tasks: [] }
let app: ReturnType<typeof createApp> | undefined
async function settle() { for (let i = 0; i < 5; i++) { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() } }
async function mount(path = '/presales') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/presales/:projectId?', component: Workbench }] })
  await router.push(path); await router.isReady()
  const root = document.createElement('div'); document.body.append(root)
  app = createApp(Workbench); app.use(router); app.use(ElementPlus); app.mount(root); await settle()
}
function button(label: string): HTMLButtonElement { const result = [...document.querySelectorAll('button')].find(item => item.textContent?.trim() === label); if (!result) throw new Error(`Button not found: ${label}`); return result }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.members).mockResolvedValue([{ id: 'membership', userId: project.ownerId, nickname: 'Jane Doe', username: 'jane' }])
  vi.mocked(presalesApi.capabilities).mockResolvedValue({ enabled: true, semanticEnabled: true, canWrite: true, canApprove: true })
  vi.mocked(presalesApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  vi.mocked(presalesApi.get).mockResolvedValue(structuredClone(project))
  vi.mocked(presalesApi.employees).mockResolvedValue([])
  vi.mocked(presalesApi.sources).mockResolvedValue([])
  vi.mocked(presalesApi.statements).mockResolvedValue([])
})
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
describe('presales workspace behavior', () => {
  it('shows real member names and preserves string user IDs when saving the owner', async () => {
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Jane Doe')
    expect(document.body.textContent).not.toContain(project.ownerId)
    button('Edit project').click(); await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('Jane Doe')
    expect(document.querySelector('.el-dialog')?.textContent).not.toContain('Owner ID')
    vi.mocked(presalesApi.update).mockResolvedValue(project)
    button('Save').click(); await settle()
    expect(presalesApi.update).toHaveBeenCalledWith(project.workspaceId, project.id, expect.objectContaining({ ownerId: project.ownerId }))
  })

  it('renders empty server data without fixtures and creates using string IDs', async () => {
    await mount()
    expect(document.body.textContent).toContain('No projects.')
    expect(document.body.textContent).not.toContain('Plant A')
    button('New project').click(); await settle()
    const inputs = document.querySelectorAll<HTMLInputElement>('.el-dialog input')
    inputs[0].value = 'New plant'; inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1].value = 'New customer'; inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.create).mockResolvedValue(project)
    button('Save').click(); await settle()
    expect(presalesApi.create).toHaveBeenCalledWith('90071992547409999', expect.objectContaining({ name: 'New plant', customer: 'New customer', expectedVersion: 0, operationId: expect.any(String) }))
  })
  it('retains edited input on version conflict and prevents blind resubmission', async () => {
    await mount(`/presales/${project.id}`)
    button('Edit project').click(); await settle()
    const input = document.querySelector<HTMLInputElement>('.el-dialog input')!
    input.value = 'Unsaved change'; input.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.update).mockRejectedValue({ response: { status: 409, data: { msg: 'Project changed' } } })
    button('Save').click(); await settle()
    expect(input.value).toBe('Unsaved change')
    expect(document.body.textContent).toContain('Project changed')
    expect(button('Save').disabled).toBe(true)
  })
  it('shows read-only state and disables writing from server capabilities', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({ enabled: true, semanticEnabled: true, canWrite: false, canApprove: false })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Read-only access')
    expect(button('Edit project').disabled).toBe(true)
    expect(button('Ask employee to analyze').disabled).toBe(true)
  })
  it('blocks execution without an assigned employee and has no model picker', async () => {
    await mount(`/presales/${project.id}`)
    button('Ask employee to analyze').click(); await settle()
    expect(document.body.textContent).toContain('No employee assigned')
    expect(button('Start work').disabled).toBe(true)
    expect(document.querySelector('.el-dialog input[placeholder="Model"]')).toBeNull()
    expect(presalesApi.generate).not.toHaveBeenCalled()
  })
  it('treats a disabled feature 404 as unavailable, not an empty successful list', async () => {
    vi.mocked(presalesApi.capabilities).mockRejectedValue({ response: { status: 404 } })
    await mount()
    expect(document.body.textContent).toContain('Presales is disabled')
    expect(presalesApi.list).not.toHaveBeenCalled()
  })
})

describe('untrusted content and scope invariants', () => {
  it('renders AI source content as text and never creates external images', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, tasks: [{ id: 'task-1', skill: 'S1', status: 'SUCCEEDED', result: { items: [{ title: 'External source', text: '<img src="https://attacker.invalid/secret">', originKind: 'AI_SUGGESTION' }], unknowns: [], assumptions: [] } }] })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('<img src="https://attacker.invalid/secret">')
    expect(document.querySelector('img[src^="https://attacker.invalid"]')).toBeNull()
    button('Review proposal').click(); await settle()
    expect(document.querySelector<HTMLTextAreaElement>('.el-dialog textarea')?.value).toContain('<img')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
})

describe('clarification workflow', () => {
  it('blocks answered records without source and submits the recorded source with version protection', async () => {
    const clarification = { id: 'question-1', question: 'Confirm line scope?', status: 'ANSWERED', answer: 'Two lines', answerSourceId: '' }
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, clarifications: [clarification] })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(item => item.textContent === 'Requirements & questions')!
    tab.click(); await settle()
    button('Revise answer').click(); await settle()
    button('Save').click(); await settle()
    expect(document.body.textContent).toContain('Provide an answer and its source')
    expect(presalesApi.command).not.toHaveBeenCalled()
    const source = document.querySelector<HTMLInputElement>('input[placeholder="Meeting date, record title and section, or email subject"]')!
    source.value = 'Meeting 2026-09-18, paragraph 2'; source.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4, clarifications: [{ ...clarification, answerSourceId: source.value }] })
    button('Save').click(); await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(project.workspaceId, project.id, expect.objectContaining({ expectedVersion: 3, action: 'SAVE_CLARIFICATION', payload: expect.objectContaining({ answerSourceId: 'Meeting 2026-09-18, paragraph 2' }) }))
    expect(document.body.textContent).toContain('Meeting 2026-09-18, paragraph 2')
  })
})

describe('employee-driven workbench', () => {
  it('executes through the bound employee without a per-task model override', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, agentId: '90071992547409996', agentName: 'Presales specialist' })
    vi.mocked(presalesApi.employees).mockResolvedValue([{ id: '90071992547409996', name: 'Presales specialist', enabled: true }])
    vi.mocked(presalesApi.generate).mockResolvedValue({ ...project, agentId: '90071992547409996', tasks: [] })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).not.toMatch(/G1|G2/)
    expect([...document.querySelectorAll('button')].some(b => b.textContent === 'Add question')).toBe(false)
    button('Delegate to employee').click(); await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('Presales specialist')
    button('Start work').click(); await settle()
    expect(presalesApi.generate).toHaveBeenCalledWith(project.workspaceId, project.id, expect.objectContaining({ skill: 'S1', expectedVersion: 3, taskGoal: expect.any(String) }))
    expect(vi.mocked(presalesApi.generate).mock.calls[0][2]).not.toHaveProperty('modelId')
  })
  it('offers continuation to the employee after supplied information is saved', async () => {
    const question = { id: 'q', question: 'Scope?', impact: 'Scope', status: 'ANSWERED', answer: 'Two lines', answerSourceId: 'Meeting', proposedByTaskId: 'task' }
    const current = { ...project, agentId: 'employee', clarifications: [question] }
    vi.mocked(presalesApi.get).mockResolvedValue(current)
    vi.mocked(presalesApi.command).mockResolvedValue({ ...current, version: 4 })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(item => item.textContent === 'Requirements & questions')!
    tab.click(); await settle(); button('Revise answer').click(); await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('The employee needs this information')
    button('Save').click(); await settle()
    expect(document.body.textContent).toContain('Delegate to presales employee')
    expect(presalesApi.generate).not.toHaveBeenCalled()
  })
})
