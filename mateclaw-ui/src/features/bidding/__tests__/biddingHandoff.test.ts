import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingHandoffDialog from '../components/BiddingHandoffDialog.vue'
import { biddingApi } from '../api/biddingApi'
import { presalesApi } from '@/features/presales/api/presalesApi'

vi.mock('../api/biddingApi',()=>({biddingApi:{handoffOptions:vi.fn(),handoffSnapshot:vi.fn()}}))
vi.mock('@/features/presales/api/presalesApi',()=>({presalesApi:{list:vi.fn()}}))
let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
function mount(onReceive:ReturnType<typeof vi.fn>){host=document.createElement('div');document.body.append(host);app=createApp(BiddingHandoffDialog,{modelValue:true,workspaceId:'ws-1',project:{id:'bid-1',workspaceId:'ws-1',name:'Bridge',lotName:'Lot 1',ownerId:'7',version:3,stage:'SETUP',ref:{kind:'project',id:'bid-1',version:3,digest:'project-digest'},bindings:{},selectedRefs:{}},onReceive});app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return document.body}
async function chooseSelect(index:number,label:string){const select=document.querySelectorAll('.el-select')[index] as HTMLElement;select.click();await flush();const option=[...document.body.querySelectorAll('.el-select-dropdown__item')].find(item=>item.textContent?.includes(label)) as HTMLElement|undefined;expect(option).toBeTruthy();option!.click();await flush()}

it('requires a concrete release choice, keeps only the latest preview, and receives its exact digest',async()=>{
  const previewA={solution:{title:'Old solution',sections:[{title:'Scope',text:'Old scope'}]},baseline:{status:'Approved'},risksAndUnknowns:[],clarifications:[]}
  const previewB={solution:{title:'Selected solution',sections:[{title:'Scope',text:'Selected scope'}]},baseline:{status:'Approved'},risksAndUnknowns:[{text:'Schedule risk'}],clarifications:[]}
  let resolveA:(value:typeof previewA)=>void=()=>{}
  vi.mocked(presalesApi.list).mockResolvedValue({items:[{id:'presales-raw-id',name:'Bridge engagement'}],total:1,page:1,pageSize:100} as never)
  vi.mocked(biddingApi.handoffOptions).mockResolvedValue([
    {presalesProjectId:'presales-raw-id',releaseId:'release-old',digest:'digest-old',title:'Old release',publishedAt:'2026-09-01',status:'PUBLISHED'},
    {presalesProjectId:'presales-raw-id',releaseId:'release-current',digest:'digest-current',title:'Current release',publishedAt:'2026-09-20',status:'PUBLISHED'},
  ])
  vi.mocked(biddingApi.handoffSnapshot).mockImplementationOnce(()=>new Promise(resolve=>{resolveA=resolve as typeof resolveA})).mockResolvedValueOnce(previewB as never)
  const receive=vi.fn(),body=mount(receive);await flush()
  const receiveButton=[...body.querySelectorAll('button')].find(button=>button.textContent?.includes('Receive this version')) as HTMLButtonElement
  expect(receiveButton.disabled).toBe(true)
  await chooseSelect(1,'Old release')
  await chooseSelect(1,'Current release')
  resolveA(previewA);await flush()
  expect(body.textContent).toContain('Selected solution')
  expect(body.textContent).not.toContain('Old solution')
  expect(receiveButton.disabled).toBe(false)
  receiveButton.click();await flush()
  expect(receive).toHaveBeenCalledWith({presalesProjectId:'presales-raw-id',releaseId:'release-current',expectedDigest:'digest-current',receivedNotes:[]})
  expect(body.textContent).not.toContain('presales-raw-id')
})

it('keeps historical releases without a frozen snapshot visible but unavailable',async()=>{
  vi.mocked(presalesApi.list).mockResolvedValue({items:[{id:'ps1',name:'Engagement'}],total:1,page:1,pageSize:100} as never)
  vi.mocked(biddingApi.handoffOptions).mockResolvedValue([{presalesProjectId:'ps1',releaseId:'r1',digest:'',title:'Historical release',status:'PUBLISHED',available:false,unavailableReason:'No verifiable frozen snapshot'}])
  const body=mount(vi.fn());await flush()
  await chooseSelect(1,'Historical release')
  expect(body.textContent).toContain('No verifiable frozen snapshot')
  expect(biddingApi.handoffSnapshot).not.toHaveBeenCalled()
  expect(([...body.querySelectorAll('button')].find(button=>button.textContent?.includes('Receive this version')) as HTMLButtonElement).disabled).toBe(true)
})
