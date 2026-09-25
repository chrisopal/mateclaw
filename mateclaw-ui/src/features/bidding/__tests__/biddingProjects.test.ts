import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingProjects from '../pages/BiddingProjects.vue'
import { biddingApi } from '../api/biddingApi'
import { isCurrentRequest } from '../shared/state'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/stores/useWorkspaceStore', () => ({ useWorkspaceStore: () => ({ currentWorkspaceId: 'ws-1', registerBeforeSwitch: () => () => {} }) }))
vi.mock('../api/biddingApi', () => ({ biddingApi: { capabilities: vi.fn(), members: vi.fn(), dashboard: vi.fn(), list: vi.fn(), create: vi.fn() } }))
let app: App | undefined, host: HTMLElement | undefined
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const unavailable = Object.assign(new Error('404'), { response: { status: 404 } })
afterEach(() => { app?.unmount(); host?.remove(); app=undefined; host=undefined; vi.clearAllMocks() })
async function mount() {
  host=document.createElement('div'); document.body.append(host); app=createApp(BiddingProjects)
  app.use(ElementPlus).use(createI18n({ legacy:false, locale:'en-US', messages:{'en-US':en} })).mount(host)
  await flush()
}

describe('bidding projects', () => {
  it('shows module unavailable for 404 instead of an empty ledger', async () => {
    vi.mocked(biddingApi.capabilities).mockRejectedValue(unavailable)
    await mount()
    expect(host!.textContent).toContain('Bidding is not enabled')
    expect(host!.textContent).not.toContain('No bidding projects')
  })

  it('keeps responses owned by the captured workspace and submits the real member userId', async () => {
    expect(isCurrentRequest('ws-1','', 'ws-2','')).toBe(false)
    expect(isCurrentRequest('ws-1','', 'ws-1','')).toBe(true)
    vi.mocked(biddingApi.capabilities).mockResolvedValue({ enabled:true, canWrite:true, canApprove:true })
    vi.mocked(biddingApi.members).mockResolvedValue([{ userId:'90071992547409999', nickname:'Lin', username:'lin' }])
    vi.mocked(biddingApi.dashboard).mockResolvedValue({ inProgress:0, dueWithin7Days:0, overdueDeadlines:0, unknownDeadlines:0, pendingConfirmation:0, failedTasks:0 })
    vi.mocked(biddingApi.list).mockResolvedValue({ items:[], total:0, page:1, pageSize:20 })
    vi.mocked(biddingApi.create).mockResolvedValue({ id:'project-1' } as never)
    await mount()
    expect(biddingApi.dashboard).toHaveBeenCalledWith('ws-1', { name:'', ownerId:'', stage:'' }, expect.any(AbortSignal))
    expect(biddingApi.list).toHaveBeenCalledWith('ws-1', { name:'', ownerId:'', stage:'', page:1, pageSize:20 }, expect.any(AbortSignal))
    ;[...host!.querySelectorAll('button')].find(button=>button.textContent?.includes('New project'))!.click(); await flush()
    const inputs=host!.querySelectorAll('.el-dialog input')
    for (const [index,value] of ['Tender project','Lot A'].entries()) { const input=inputs[index] as HTMLInputElement; input.value=value; input.dispatchEvent(new Event('input',{bubbles:true})); await flush() }
    ;(host!.querySelector('.el-dialog .el-select') as HTMLElement).click(); await flush()
    const option=[...document.querySelectorAll('.el-select-dropdown__item')].find(node=>node.textContent?.includes('Lin')) as HTMLElement
    expect(option).toBeTruthy(); option.click(); await flush()
    ;([...host!.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Create') as HTMLButtonElement).click(); await flush()
    expect(biddingApi.create).toHaveBeenCalledWith('ws-1',expect.objectContaining({ ownerId:'90071992547409999', name:'Tender project', lotName:'Lot A' }))
    expect(push).toHaveBeenCalledWith('/bidding/project-1')
  })

  it('does not apply a late response from a different workspace', () => {
    expect(isCurrentRequest('ws-1', 'project-1', 'ws-2', 'project-1')).toBe(false)
    expect(isCurrentRequest('ws-1', 'project-1', 'ws-1', 'project-1')).toBe(true)
  })

  it('keeps a read-only project list view-only', async () => {
    vi.mocked(biddingApi.capabilities).mockResolvedValue({ enabled:true, canWrite:false, canApprove:false })
    vi.mocked(biddingApi.members).mockResolvedValue([])
    vi.mocked(biddingApi.dashboard).mockResolvedValue({ inProgress:0, dueWithin7Days:0, overdueDeadlines:0, unknownDeadlines:0, pendingConfirmation:0, failedTasks:0 })
    vi.mocked(biddingApi.list).mockResolvedValue({ items:[], total:0, page:1, pageSize:20 })
    await mount()
    expect(host!.textContent).toContain('No bidding projects')
    expect([...host!.querySelectorAll('button')].some(button=>button.textContent?.includes('New project'))).toBe(false)
  })
})
