import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingProjects from '../pages/BiddingProjects.vue'
import { biddingApi } from '../api/biddingApi'

const push = vi.fn()
const workspaceHarness=vi.hoisted(()=>({initialWorkspace:'ws-1'}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/stores/useWorkspaceStore', async () => { const {reactive}=await import('vue'); const state=reactive({currentWorkspaceId:workspaceHarness.initialWorkspace}); (globalThis as typeof globalThis & {__biddingWorkspaceState?:typeof state}).__biddingWorkspaceState=state; return { useWorkspaceStore: () => ({ get currentWorkspaceId(){return state.currentWorkspaceId}, registerBeforeSwitch: () => () => {} }) } })
vi.mock('../api/biddingApi', () => ({ biddingApi: { capabilities: vi.fn(), members: vi.fn(), dashboard: vi.fn(), list: vi.fn(), create: vi.fn() } }))
let app: App | undefined, host: HTMLElement | undefined
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const unavailable = Object.assign(new Error('404'), { response: { status: 404 } })
const workspaceState=()=>((globalThis as typeof globalThis & {__biddingWorkspaceState?:{currentWorkspaceId:string}}).__biddingWorkspaceState!)
function deferred<T>() { let resolve!: (value:T)=>void; let reject!: (reason?:unknown)=>void; const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no}); return {promise,resolve,reject} }
afterEach(() => { app?.unmount(); host?.remove(); app=undefined; host=undefined; workspaceState().currentWorkspaceId='ws-1'; vi.clearAllMocks() })
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

  it('does not let a late same-workspace filter response replace the newer results', async () => {
    const oldResult=deferred<{items:never[];total:number;page:number;pageSize:number}>()
    vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
    vi.mocked(biddingApi.members).mockResolvedValue([])
    vi.mocked(biddingApi.dashboard).mockResolvedValue({inProgress:0,dueWithin7Days:0,overdueDeadlines:0,unknownDeadlines:0,pendingConfirmation:0,failedTasks:0})
    vi.mocked(biddingApi.list).mockImplementation((_ws,params)=>params.name==='alpha'?oldResult.promise:Promise.resolve({items:[],total:0,page:1,pageSize:20}))
    await mount()
    const input=host!.querySelector('.filters input') as HTMLInputElement
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'alpha');input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'alpha'}));await flush()
    ;[...host!.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Filter')!.click();await flush()
    const alphaCall=vi.mocked(biddingApi.list).mock.calls.find(([,params])=>params.name==='alpha')!
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'beta');input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'beta'}));await flush()
    ;[...host!.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Filter')!.click();await flush()
    oldResult.resolve({items:[{id:'old',workspaceId:'ws-1',name:'Alpha old result'}] as never[],total:1,page:1,pageSize:20});await flush()
    expect(alphaCall[2]?.aborted).toBe(true)
    expect(host!.textContent).not.toContain('Alpha old result')
  })

  it('ignores late owner-member results after a workspace switch', async () => {
    const oldMembers=deferred<{userId:string;nickname:string}[]>()
    let memberCall=0
    vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
    vi.mocked(biddingApi.members).mockImplementation(()=>memberCall++===1?oldMembers.promise:Promise.resolve([]))
    vi.mocked(biddingApi.dashboard).mockResolvedValue({inProgress:0,dueWithin7Days:0,overdueDeadlines:0,unknownDeadlines:0,pendingConfirmation:0,failedTasks:0})
    vi.mocked(biddingApi.list).mockResolvedValue({items:[],total:0,page:1,pageSize:20})
    await mount()
    ;[...host!.querySelectorAll('button')].find(button=>button.textContent?.includes('New project'))!.click();await flush()
    workspaceState().currentWorkspaceId='ws-2';await flush()
    oldMembers.resolve([{userId:'old-owner',nickname:'Old workspace member'}]);await flush()
    expect(host!.textContent).not.toContain('Old workspace member')
    expect(document.body.textContent).not.toContain('Old workspace member')
  })

  it('does not navigate to a project created in the previous workspace', async () => {
    const oldCreate=deferred<{id:string}>()
    vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
    vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}])
    vi.mocked(biddingApi.dashboard).mockResolvedValue({inProgress:0,dueWithin7Days:0,overdueDeadlines:0,unknownDeadlines:0,pendingConfirmation:0,failedTasks:0})
    vi.mocked(biddingApi.list).mockResolvedValue({items:[],total:0,page:1,pageSize:20})
    vi.mocked(biddingApi.create).mockReturnValue(oldCreate.promise as never)
    await mount()
    ;[...host!.querySelectorAll('button')].find(button=>button.textContent?.includes('New project'))!.click();await flush()
    const inputs=host!.querySelectorAll('.el-dialog input[type="text"]')
    for(const [index,value] of ['Create old','Lot old'].entries()){const input=inputs[index] as HTMLInputElement;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,value);input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));await flush()}
    ;([...host!.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Create') as HTMLButtonElement).click();await flush()
    expect(biddingApi.create).toHaveBeenCalledWith('ws-1',expect.any(Object))
    workspaceState().currentWorkspaceId='ws-3';await flush()
    oldCreate.resolve({id:'stale-project'});await flush()
    expect(push).not.toHaveBeenCalled()
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
