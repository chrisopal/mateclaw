import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingTaskDrawer from '../components/BiddingTaskDrawer.vue'
import BiddingOverview from '../components/BiddingOverview.vue'
import { biddingApi } from '../api/biddingApi'

vi.mock('../api/biddingApi',()=>({biddingApi:{tasks:vi.fn(),task:vi.fn(),command:vi.fn()}}))
let app:App|undefined,host:HTMLElement|undefined
const originalWindowWidth=window.innerWidth
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;Object.defineProperty(window,'innerWidth',{configurable:true,value:originalWindowWidth});vi.clearAllMocks()})
it('renders a complete compact task list at 390px and keeps retry, cancel, and attempt details operable',async()=>{
  Object.defineProperty(window,'innerWidth',{configurable:true,value:390})
  const items=[
    {taskId:'task-profile',skillId:'bidding-tender-profile',status:'FAILED',attemptCount:2},
    {taskId:'task-elimination',skillId:'bidding-elimination-analysis',status:'RUNNING',attemptCount:1},
    {taskId:'task-requirements',skillId:'bidding-requirement-analysis',status:'QUEUED',attemptCount:0},
    {taskId:'task-scoring',skillId:'bidding-scoring-analysis',status:'SUCCEEDED',attemptCount:1},
  ]
  vi.mocked(biddingApi.tasks).mockResolvedValue({items,total:items.length,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-profile',status:'FAILED',attemptCount:2,attempts:[{attemptNo:1,state:'SUCCEEDED',result:{accepted:true}},{attemptNo:2,state:'FAILED',rejection:{code:'MODEL_TIMEOUT'}}]} as never)
  vi.mocked(biddingApi.command).mockResolvedValue({status:'QUEUED'})
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':{}}})).mount(host);await flush()
  const cards=[...document.querySelectorAll('.mobile-task-card')]
  expect(cards).toHaveLength(4)
  expect(cards.map(card=>card.textContent).join(' ')).toContain('招标文件基本信息')
  expect(cards.map(card=>card.textContent).join(' ')).toContain('废标条款')
  expect(cards.map(card=>card.textContent).join(' ')).toContain('技术与商务要求')
  expect(cards.map(card=>card.textContent).join(' ')).toContain('评分标准')
  expect(cards[0]?.textContent).toContain('2')
  expect(cards[0]?.querySelector('button')?.textContent).toContain('重试')
  expect(cards[1]?.querySelector('button')?.textContent).toContain('取消')
  ;(cards[0]?.querySelector('.task-card-main') as HTMLElement).click();await flush()
  const attempts=[...document.querySelectorAll('.mobile-attempt-card')]
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.textContent).toContain('模型响应超时')
  ;(cards[0]?.querySelector('button') as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'RETRY_TASK',payload:{taskId:'task-profile'}}))
  ;(cards[1]?.querySelector('button') as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'CANCEL_TASK',payload:{taskId:'task-elimination'}}))
})

it('shows a failed attempt and sends one explicit retry command',async()=>{
  const task={taskId:'task-1',skillId:'bidding-tender-profile',status:'FAILED',attemptCount:1}
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[task],total:1,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-1',status:'FAILED',attemptCount:1,attempts:[{attemptNo:1,state:'FAILED',rejection:{code:'MODEL_TIMEOUT'}}]} as never)
  vi.mocked(biddingApi.command).mockResolvedValue({status:'QUEUED'})
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;[...document.querySelectorAll('tr')].find(row=>row.textContent?.includes('Tender profile'))?.dispatchEvent(new MouseEvent('click',{bubbles:true}));await flush()
  expect(document.body.textContent).toContain('Model response timed out')
  ;[...document.querySelectorAll('button')].find(button=>button.textContent?.includes('Retry'))!.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledTimes(1)
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'RETRY_TASK',payload:{taskId:'task-1'}}))
})


it('renders localized analysis skill names, task states, and readable failures with raw codes folded',async()=>{
  const items=[
    {taskId:'task-1',skillId:'bidding-tender-profile',status:'QUEUED',attemptCount:0},
    {taskId:'task-2',skillId:'bidding-elimination-analysis',status:'RUNNING',attemptCount:1},
    {taskId:'task-3',skillId:'bidding-requirement-analysis',status:'WAITING_RETRY',attemptCount:2},
    {taskId:'task-4',skillId:'bidding-scoring-analysis',status:'FAILED',attemptCount:1},
  ]
  vi.mocked(biddingApi.tasks).mockResolvedValue({items,total:items.length,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-4',status:'FAILED',attemptCount:1,snapshot:{_bidding:{skillId:'bidding-scoring-analysis'}},attempts:[{attemptNo:1,state:'FAILED',rejection:{code:'SKILL_NOT_LOADED'}}]} as never)
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const body=document.body.textContent||''
  expect(body).toContain('Tender profile')
  expect(body).toContain('Disqualification analysis')
  expect(body).toContain('Technical and commercial requirements')
  expect(body).toContain('Scoring criteria')
  expect(body).toContain('Queued');expect(body).toContain('Running');expect(body).toContain('Waiting to retry');expect(body).toContain('Failed')
  ;[...document.querySelectorAll('tr')].find(row=>row.textContent?.includes('Scoring criteria'))?.dispatchEvent(new MouseEvent('click',{bubbles:true}));await flush()
  expect(document.querySelector('.task-detail h3')?.textContent).toBe('Scoring criteria')
  expect(document.querySelector('.task-detail .el-alert')?.textContent).toContain('The configured skill could not be loaded')
  const diagnostics=document.querySelector('.task-detail details') as HTMLDetailsElement
  expect(diagnostics.open).toBe(false)
  expect(diagnostics.textContent).toContain('SKILL_NOT_LOADED')
  expect(document.querySelector('.task-detail')?.querySelector('.el-alert')?.textContent).not.toContain('SKILL_NOT_LOADED')
})

it('does not show a failure alert or reason for a successful task attempt',async()=>{
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[{taskId:'task-ok',skillId:'bidding-scoring-analysis',status:'SUCCEEDED',attemptCount:1}],total:1,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-ok',skillId:'bidding-scoring-analysis',status:'SUCCEEDED',attemptCount:1,attempts:[{attemptNo:1,state:'SUCCEEDED',result:{accepted:true}}]} as never)
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'ANALYSIS',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;[...document.querySelectorAll('tr')].find(row=>row.textContent?.includes('Scoring criteria'))?.dispatchEvent(new MouseEvent('click',{bubbles:true}));await flush()
  expect(document.querySelector('.task-detail .el-alert')).toBeNull()
  const attemptRow=document.querySelector('.task-detail .el-table__row')
  expect(attemptRow?.textContent).toContain('—')
  expect(attemptRow?.textContent).not.toContain('Task did not complete')
})

it('uses Chinese task skill, status, and failure labels for Chinese workspaces',async()=>{
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[{taskId:'task-zh',skillId:'bidding-tender-profile',status:'FAILED',attemptCount:1}],total:1,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-zh',status:'FAILED',attemptCount:1,attempts:[{attemptNo:1,state:'FAILED',rejection:{code:'SKILL_NOT_LOADED'}}]} as never)
  const project={id:'p1',workspaceId:'ws-1',name:'招标项目',lotName:'一标段',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':{}}})).mount(host);await flush()
  expect(document.body.textContent).toContain('招标文件基本信息')
  expect(document.body.textContent).toContain('失败')
  ;[...document.querySelectorAll('tr')].find(row=>row.textContent?.includes('招标文件基本信息'))?.dispatchEvent(new MouseEvent('click',{bubbles:true}));await flush()
  expect(document.querySelector('.task-detail .el-alert')?.textContent).toContain('技能包未能加载')
})

it('localizes project stages and describes unavailable later capabilities without internal phase codes',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingOverview,{project,members:[{userId:'7',nickname:'Owner'}],employees:[],canApprove:false,saving:false,error:''})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('Setup')
  expect(host.textContent).toContain('not available yet')
  expect(host.textContent).not.toContain('SETUP')
  expect(host.textContent).not.toContain('P2')
  expect(host.textContent).not.toContain('P3')
})

it('keeps retry and cancel actions unavailable to a workspace viewer',async()=>{
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[{taskId:'task-2',status:'FAILED',attemptCount:1}],total:1,page:1,pageSize:100})
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project,canWrite:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(document.body.textContent).toContain('Read-only access')
  expect([...document.querySelectorAll('button')].some(button=>['Retry','Cancel'].includes(button.textContent?.trim()||''))).toBe(false)
  expect(biddingApi.command).not.toHaveBeenCalled()
})
