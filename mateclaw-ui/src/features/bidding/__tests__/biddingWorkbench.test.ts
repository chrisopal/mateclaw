import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingSources from '../components/BiddingSources.vue'
import BiddingWorkbench from '../pages/BiddingWorkbench.vue'
import { biddingApi } from '../api/biddingApi'

const harness=vi.hoisted(()=>({projectId:'p1',setProjectId:undefined as undefined|((id:string)=>void),currentWorkspaceId:'ws-1',setWorkspaceId:undefined as undefined|((id:string)=>void),push:vi.fn(),workspaceGuard:undefined as undefined|((id:string)=>boolean|Promise<boolean>),routeLeaveGuard:undefined as undefined|(()=>boolean|Promise<boolean>)}))
vi.mock('vue-router',async()=>{const {reactive}=await vi.importActual<typeof import('vue')>('vue');const params=reactive({id:harness.projectId});harness.setProjectId=(id:string)=>{params.id=id};return {useRoute:()=>({params}),useRouter:()=>({push:harness.push}),onBeforeRouteLeave:(guard:()=>boolean|Promise<boolean>)=>{harness.routeLeaveGuard=guard},onBeforeRouteUpdate:vi.fn()}})
vi.mock('@/stores/useWorkspaceStore',async()=>{const {reactive}=await vi.importActual<typeof import('vue')>('vue');const state=reactive({id:harness.currentWorkspaceId});harness.setWorkspaceId=(id:string)=>{state.id=id};return {useWorkspaceStore:()=>({get currentWorkspaceId(){return state.id},registerBeforeSwitch:(guard:(id:string)=>boolean|Promise<boolean>)=>{harness.workspaceGuard=guard;return ()=>{harness.workspaceGuard=undefined}}})}})
vi.mock('../api/biddingApi',()=>({biddingApi:{command:vi.fn(),upload:vi.fn(),capabilities:vi.fn(),get:vi.fn(),members:vi.fn(),employees:vi.fn(),sources:vi.fn(),sourceSetHead:vi.fn(),analysis:vi.fn(),materials:vi.fn().mockResolvedValue({items:[]}),outline:vi.fn().mockResolvedValue({candidates:[]}),writing:vi.fn().mockResolvedValue({chapters:[]}),changeImpact:vi.fn().mockResolvedValue({events:[],formalBlocked:false}),review:vi.fn().mockResolvedValue({status:'NOT_ASSEMBLED',tasks:[],findings:[],humanTodos:[]}),templates:vi.fn().mockResolvedValue([]),artifact:vi.fn(),approvalContext:vi.fn(),download:vi.fn(),handoffOptions:vi.fn(),handoffSnapshot:vi.fn(),knowledgeBases:vi.fn(),knowledgePages:vi.fn(),knowledgePage:vi.fn(),tasks:vi.fn().mockResolvedValue({items:[]}),task:vi.fn(),evidence:vi.fn(),content:vi.fn(),revision:vi.fn()}}))

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
function installReviewScopeFixtures({prepared=false,withArtifact=false}:{prepared?:boolean;withArtifact?:boolean}={}){
  const project=(workspaceId:string,name:string)=>({id:'p1',workspaceId,name,lotName:'Lot A',ownerId:'7',version:2,stage:'WRITING',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:`${workspaceId}-d2`},bindings:{},selectedRefs:{}})
  const first=project('ws-1','Original workspace project'),next=project('ws-2','New workspace project')
  const manuscriptRef={kind:'manuscript',id:'ms1',version:4,digest:'ms4'},templateRef={kind:'template',id:'template1',version:2,digest:'t2'},formatRef={kind:'format',id:'format1',version:1,digest:'fmt1'},artifactRef={kind:'artifact',id:'artifact1',version:1,digest:'file-digest'},reviewRef={kind:'reviewSnapshot',id:'proof',version:1,digest:'review-proof'}
  const metadata={artifactId:'artifact1',filename:'Technical proposal.docx',mode:'candidate',status:'CANDIDATE',digest:'file-digest',byteSize:4096,manifest:{artifactId:'artifact1',sha256:'file-digest',byte_size:4096},checks:{current:true},formalAvailable:false}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockImplementation(async ws=>(ws==='ws-1'?first:next) as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({chapters:[],manuscript:{ref:manuscriptRef,status:'DRAFT_PENDING_REVIEW',payload:{chapters:[]}}} as never)
  vi.mocked(biddingApi.changeImpact).mockResolvedValue({events:[],formalBlocked:false} as never)
  vi.mocked(biddingApi.review).mockResolvedValue({status:'REVIEWED',manuscriptRef,tasks:[],findings:[],humanTodos:[]} as never)
  vi.mocked(biddingApi.templates).mockResolvedValue(prepared?[{name:'Technical v1',format:'docx',status:'PREPARED',ref:templateRef,formatRef}] as never:[{name:'Technical v1',format:'docx',status:'UNPREPARED'}] as never)
  vi.mocked(biddingApi.tasks).mockResolvedValue(withArtifact?{items:[{taskId:'export-task',skillId:'bidding-document-export',status:'SUCCEEDED',attemptCount:1}],total:1,page:1,pageSize:100} as never:{items:[],total:0,page:1,pageSize:100} as never)
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'export-task',status:'SUCCEEDED',attemptCount:1,result:{payload:{artifactId:'artifact1'}}} as never)
  vi.mocked(biddingApi.artifact).mockResolvedValue(metadata as never)
  vi.mocked(biddingApi.approvalContext).mockResolvedValue({...metadata,status:'READY',artifactRef,reviewRef,manuscriptRef,templateRef,formatRef} as never)
  return {first,next,metadata,artifactRef,reviewRef,manuscriptRef,templateRef,formatRef}
}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;harness.setProjectId?.('p1');harness.projectId='p1';harness.currentWorkspaceId='ws-1';harness.setWorkspaceId?.('ws-1');harness.workspaceGuard=undefined;harness.routeLeaveGuard=undefined;vi.restoreAllMocks();vi.clearAllMocks()})
it('opens the sixth tab with current review and export surfaces',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.review).mockResolvedValue({status:'NOT_ASSEMBLED',tasks:[],findings:[],humanTodos:[]} as never)
  vi.mocked(biddingApi.templates).mockResolvedValue([])
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[]} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  expect(host.textContent).toContain('Whole-book review')
  expect(host.textContent).toContain('Deliverables')
  expect(host.textContent).toContain('No generated files yet')
  expect(biddingApi.review).toHaveBeenCalledWith('ws-1','p1',expect.any(AbortSignal))
})

it('generates with pinned refs and submits approval from the server approval context',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'WRITING',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'p2'},bindings:{reviewer:{agentId:'reviewer',skillPins:['review-skill']},writer:{agentId:'writer',skillPins:['write-skill']}},selectedRefs:{}}
  const manuscriptRef={kind:'manuscript',id:'ms1',version:4,digest:'ms4'},templateRef={kind:'template',id:'template1',version:2,digest:'t2'},formatRef={kind:'format',id:'format1',version:1,digest:'fmt1'},artifactRef={kind:'artifact',id:'artifact1',version:1,digest:'file-digest'},reviewRef={kind:'reviewSnapshot',id:'proof',version:1,digest:'review-proof'}
  const metadata={artifactId:'artifact1',filename:'Technical proposal.docx',mode:'candidate',status:'CANDIDATE',digest:'file-digest',byteSize:4096,manuscriptRef,templateRef,formatRef,formalAvailable:false}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({outlineRef:{kind:'outline',id:'ol1',version:2,digest:'ol2'},chapters:[],manuscript:{ref:manuscriptRef,status:'DRAFT_PENDING_REVIEW',payload:{chapters:[]}}} as never)
  vi.mocked(biddingApi.review).mockResolvedValue({status:'REVIEWED',manuscriptRef,tasks:[],findings:[],humanTodos:[],coverage:{chapterIds:[],requirementIds:[],expectedChapterIds:[],expectedRequirementIds:[],crossChapterReviewed:true}} as never)
  vi.mocked(biddingApi.templates).mockResolvedValue([{name:'Technical v1',format:'docx',status:'PREPARED',ref:templateRef,formatRef}] as never)
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[{taskId:'export-task',skillId:'bidding-document-export',status:'SUCCEEDED',attemptCount:1}]} as never)
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'export-task',status:'SUCCEEDED',attemptCount:1,result:{payload:{artifactId:'artifact1'}}} as never)
  vi.mocked(biddingApi.artifact).mockResolvedValue(metadata as never)
  vi.mocked(biddingApi.approvalContext).mockResolvedValue({...metadata,status:'READY',artifactRef,reviewRef} as never)
  vi.mocked(biddingApi.command).mockResolvedValue({} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Generate candidate')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'DISPATCH_EXPORT',expected:project.ref,payload:{manuscriptRef,templateRef,formatRef,mode:'candidate'}}))
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Approve this file')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.approvalContext).toHaveBeenCalledWith('ws-1','p1','artifact1',expect.any(AbortSignal))
  const checks=[...host.querySelectorAll('.el-dialog .el-checkbox')];checks.forEach(item=>(item as HTMLElement).click());await flush()
  const reason=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(reason,'Opened the saved candidate and checked its layout')
  reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Checked layout'}));await flush()
  ;([...host.querySelectorAll('.el-dialog__footer button')].find(button=>button.textContent?.includes('Approve this file')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'APPROVE_ARTIFACT',expected:artifactRef,payload:expect.objectContaining({artifactId:'artifact1',digest:'file-digest',manuscriptRef,templateRef,formatRef,reviewRef,inspection:{opened:true,layoutChecked:true,reason:'Opened the saved candidate and checked its layout'}})}))
})

it('classifies with the flat evidence contract, then refreshes the todo ref after 409 without dropping the draft',async()=>{
  const ref=(kind:string,id:string,version:number)=>({kind,id,version,digest:`${id}-${version}`})
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'WRITING',capabilities:{canApprove:true},ref:ref('project','p1',2),bindings:{},selectedRefs:{}}
  const oldTodo={ref:ref('HUMAN_TODO','todo-1',1),title:'Confirm warranty period',status:'OPEN',impactClassification:'UNCLASSIFIED'}
  const currentTodo={...oldTodo,ref:ref('HUMAN_TODO','todo-1',2)}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([{sourceId:'source-1',version:3,kind:'DOCX',filename:'Tender conditions.docx',digest:'source-digest',readStatus:'READABLE',quality:'HIGH',problems:[],blocks:[{id:'block-1',locator:'Page 2',text:'Warranty period is five years.',kind:'paragraph',quality:'HIGH'}]}] as never)
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.review).mockResolvedValueOnce({status:'REVIEWED',tasks:[],findings:[],humanTodos:[oldTodo]} as never).mockResolvedValue({status:'REVIEWED',tasks:[],findings:[],humanTodos:[currentTodo]} as never)
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('Conflict'),{response:{status:409}})).mockResolvedValueOnce({} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('.todo-card button')].find(button=>button.textContent?.includes('Classify impact')) as HTMLButtonElement).click();await flush()
  ;(host.querySelector('.el-dialog .el-form-item:nth-child(2) .el-select input') as HTMLInputElement).click();await flush()
  ;([...document.querySelectorAll('.el-select-dropdown__item')].find(item=>item.textContent?.includes('Tender conditions.docx')) as HTMLElement).click();await flush()
  ;(host.querySelector('.el-dialog .el-form-item:nth-child(3) .el-select input') as HTMLInputElement).click();await flush()
  ;([...document.querySelectorAll('.el-select-dropdown__item')].find(item=>item.textContent?.includes('Warranty period is five years.')) as HTMLElement).click();await flush()
  const reason=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(reason,'The signed terms confirm this duration.')
  reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Confirm'}));await flush()
  ;([...host.querySelectorAll('.el-dialog__footer button')].find(button=>button.textContent?.includes('Submit')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'CLASSIFY_HUMAN_TODO',expected:oldTodo.ref,payload:expect.objectContaining({todoRef:oldTodo.ref,evidenceRefs:[{sourceId:'source-1',version:3,blockId:'block-1',quote:'Warranty period is five years.'}]})}))
  expect(host.querySelector('.el-dialog textarea')).not.toBeNull()
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toBe('The signed terms confirm this duration.')
  ;([...host.querySelectorAll('.el-dialog__footer button')].find(button=>button.textContent?.includes('Submit')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'CLASSIFY_HUMAN_TODO',expected:currentTodo.ref,payload:expect.objectContaining({todoRef:currentTodo.ref,evidenceRefs:[{sourceId:'source-1',version:3,blockId:'block-1',quote:'Warranty period is five years.'}]})}))
})

it.each([403,409])('ignores a late %s approval response after switching workspace with the same project id',async status=>{
  installReviewScopeFixtures({withArtifact:true})
  let rejectCommand!:(reason:unknown)=>void
  vi.mocked(biddingApi.command).mockImplementation(()=>new Promise((_,reject)=>{rejectCommand=reject}) as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Approve this file')) as HTMLButtonElement).click();await flush()
  const checks=[...document.querySelectorAll('.el-dialog .el-checkbox')];checks.forEach(item=>(item as HTMLElement).click());await flush()
  const reason=document.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(reason,'Opened and checked the candidate')
  reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Checked'}));await flush()
  ;([...document.querySelectorAll('.el-dialog__footer button')].find(button=>button.textContent?.includes('Approve this file')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'APPROVE_ARTIFACT'}))
  harness.setWorkspaceId?.('ws-2');await flush()
  expect(host.textContent).toContain('New workspace project')
  rejectCommand(Object.assign(new Error('late response'),{response:{status}}));await flush();await flush()
  expect(host.textContent).toContain('New workspace project')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Project settings'))).toBe(true)
  expect(biddingApi.approvalContext).toHaveBeenCalledTimes(1)
})

it.each([{action:'prepare',outcome:'resolve'},{action:'prepare',outcome:'reject'},{action:'generate',outcome:'resolve'},{action:'generate',outcome:'reject'}] as const)('keeps the new workspace intact after delayed export $action $outcome',async({action,outcome})=>{
  installReviewScopeFixtures({prepared:action==='generate'})
  let resolveCommand!:()=>void,rejectCommand!:(reason:unknown)=>void
  vi.mocked(biddingApi.command).mockImplementation(()=>new Promise<void>((resolve,reject)=>{resolveCommand=resolve;rejectCommand=reject}) as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  const label=action==='prepare'?'Prepare export':'Generate candidate'
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes(label)) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:action==='prepare'?'PREPARE_EXPORT':'DISPATCH_EXPORT'}))
  harness.setWorkspaceId?.('ws-2');await flush()
  expect(host.textContent).toContain('New workspace project')
  if(outcome==='resolve')resolveCommand()
  else rejectCommand(Object.assign(new Error('late access revoked'),{response:{status:403}}))
  await flush();await flush()
  expect(host.textContent).toContain('New workspace project')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Project settings'))).toBe(true)
  expect(biddingApi.get).toHaveBeenCalledTimes(2)
})

it('discards a pending approval-context response from the previous workspace',async()=>{
  installReviewScopeFixtures({withArtifact:true})
  let resolveContext!:(value:unknown)=>void
  vi.mocked(biddingApi.approvalContext).mockImplementation(()=>new Promise(resolve=>{resolveContext=resolve}) as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Approve this file')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.approvalContext).toHaveBeenCalledOnce()
  harness.setWorkspaceId?.('ws-2');await flush()
  resolveContext({artifactId:'artifact1',status:'READY',artifactRef:{kind:'artifact',id:'a',version:1,digest:'d'}});await flush()
  expect(host.textContent).toContain('New workspace project')
  expect(document.body.textContent).not.toContain('Approve candidate file')
})

it('passes the active scope abort signal to candidate downloads',async()=>{
  installReviewScopeFixtures({withArtifact:true})
  let resolveDownload!:(value:Blob)=>void
  vi.mocked(biddingApi.download).mockImplementation(()=>new Promise(resolve=>{resolveDownload=resolve}) as never)
  const createUrl=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:late-artifact')
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Review')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Download and inspect')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.download).toHaveBeenCalledWith('ws-1','p1','artifact1','candidate',expect.any(AbortSignal))
  const signal=vi.mocked(biddingApi.download).mock.calls[0]?.[4]
  harness.setWorkspaceId?.('ws-2');await flush()
  expect(signal?.aborted).toBe(true)
  resolveDownload(new Blob(['docx']));await flush()
  expect(createUrl).not.toHaveBeenCalled()
})
it('retains project settings through a real 409 and refreshes the expected revision before retry',async()=>{
  const project=(version:number,name:string)=>({id:'p1',workspaceId:'ws-1',name,lotName:'Lot A',ownerId:'7',version,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version,digest:`d${version}`},bindings:{},selectedRefs:{}})
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValueOnce(project(2,'Tender')).mockResolvedValueOnce(project(3,'Server update')).mockResolvedValue(project(4,'Edited in dialog'))
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  const conflict=Object.assign(new Error('Conflict'),{response:{status:409}})
  vi.mocked(biddingApi.command).mockRejectedValueOnce(conflict).mockResolvedValueOnce({} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Project settings')) as HTMLButtonElement).click();await flush()
  const name=host.querySelector('.el-dialog .el-form-item:nth-child(1) input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(name,'Edited in dialog');name.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Edited in dialog'}));await flush()
  const lot=host.querySelector('.el-dialog .el-form-item:nth-child(2) input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(lot,'Lot B');lot.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Lot B'}));await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Save') as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({expected:expect.objectContaining({version:2}),payload:expect.objectContaining({name:'Edited in dialog',lotName:'Lot B'})}))
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Reload') as HTMLButtonElement).click();await flush()
  expect((host.querySelector('.el-dialog .el-form-item:nth-child(1) input') as HTMLInputElement).value).toBe('Edited in dialog')
  expect((host.querySelector('.el-dialog .el-form-item:nth-child(2) input') as HTMLInputElement).value).toBe('Lot B')
  expect(host.textContent).toContain('your project settings draft is retained')
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Save') as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledTimes(2)
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({expected:expect.objectContaining({version:3}),payload:expect.objectContaining({name:'Edited in dialog',lotName:'Lot B'})}))
})

it.each([{actor:'project owner',canApprove:true},{actor:'another member',canApprove:false},{actor:'viewer',canApprove:false}])('uses the server project capability for $actor approval controls',async({actor,canApprove})=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:actor!=='viewer',canApprove:false})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const settings=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Project settings')) as HTMLButtonElement|undefined
  expect(!!settings).toBe(canApprove)
  if(canApprove){
    settings!.click();await flush()
    ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Save') as HTMLButtonElement).click();await flush()
    expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'UPDATE_PROJECT'}))
  }else expect(biddingApi.command).not.toHaveBeenCalled()
})

it('keeps change-impact confirmation owner-only when a project member can write',async()=>{
  const changedRef={kind:'source',id:'source-1',version:2,digest:'source-2'}
  const payload={eventId:'event-1',changedRef,unchangedRefs:[],resolutions:[]}
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:false},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:false})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.changeImpact).mockResolvedValue({formalBlocked:true,events:[{eventId:'event-1',changedRef,status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[],formalBlocked:true},confirmation:{ready:true,payload}}]} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Confirm impact'))).toBe(false)
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Refresh impact'))).toBe(true)
})

it.each([401,403,404,410])('hides stale project and analysis data after a %s reload response',async status=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValueOnce(project).mockRejectedValueOnce(Object.assign(new Error('restricted'),{response:{status}}))
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[{taskGroupId:'g1',status:'SUCCEEDED',complete:true,conflicts:[],skills:{'bidding-tender-profile':{basicInfo:{project:'Protected analysis marker'}}}}]} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  expect(host.textContent).toContain('Protected analysis marker')
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Refresh') as HTMLButtonElement).click();await flush()
  expect(host.textContent).not.toContain('Tender')
  expect(host.textContent).not.toContain('Protected analysis marker')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Project settings'))).toBe(false)
})

it('keeps the analysis revision draft open after an edit conflict and failed reload',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValueOnce(project).mockRejectedValueOnce(Object.assign(new Error('network'),{response:{status:503}})).mockResolvedValueOnce({...project,version:3,ref:{kind:'project',id:'p1',version:3,digest:'d3'}})
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[{taskGroupId:'g1',status:'SUCCEEDED',complete:true,conflicts:[],skills:{'bidding-tender-profile':{basicInfo:{project:'Tender'}}}}]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('Conflict'),{response:{status:409}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit result')) as HTMLButtonElement).click();await flush()
  const reason=host.querySelector('.el-dialog input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(reason,'Check the source clause');reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Check the source clause'}));await flush()
  const content=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(content,'{\n  "basicInfo": { "project": "Locally revised" }\n}');content.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Locally revised'}));await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'EDIT_ANALYSIS_ITEM',expected:project.ref}))
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Reload') as HTMLButtonElement).click();await flush()
  expect(host.querySelector('.el-dialog textarea')).not.toBeNull()
  expect((host.querySelector('.el-dialog input') as HTMLInputElement).value).toBe('Check the source clause')
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toContain('Locally revised')
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Refresh') as HTMLButtonElement).click();await flush()
  expect((host.querySelector('.el-dialog input') as HTMLInputElement).value).toBe('Check the source clause')
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toContain('Locally revised')
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'EDIT_ANALYSIS_ITEM',expected:expect.objectContaining({version:3,digest:'d3'})}))
})

it('retains a chapter editing draft after a 409 without replacing it from the server',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const outlineRef={kind:'outline',id:'ol1',version:2,digest:'outline2'}
  const chapterRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter3'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({confirmed:{ref:outlineRef,status:'CONFIRMED',inputRefs:[],payload:{chapters:[{id:'chapter-1',title:'Delivery plan',order:1}],unmappedItems:[],warnings:[]}},candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef:chapterRef,selected:{ref:chapterRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{blocks:[{type:'paragraph',text:'Server saved text'}]}}},candidates:[],tasks:[]}]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('Conflict'),{response:{status:409}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Writing')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  const editor=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(editor,'My unsaved chapter draft')
  editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'My unsaved chapter draft'}));await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'EDIT_CHAPTER',expected:chapterRef,payload:expect.objectContaining({chapterId:'chapter-1',blocks:[{type:'paragraph',text:'My unsaved chapter draft'}]})}))
  expect(host.querySelector('.el-dialog textarea')).not.toBeNull()
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toBe('My unsaved chapter draft')
  expect(host.textContent).toContain('Your draft is retained')
})

it('acknowledges a saved outline by its returned ref and confirms it against the refreshed edit head',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{writer:{agentId:'agent-1',skillPins:['skill-1']}},selectedRefs:{}}
  const baselineRef={kind:'analysis-baseline',id:'baseline-1',version:1,digest:'baseline-1'}
  const oldHead={kind:'outline',id:'current',version:2,digest:'outline-head-2'}
  const savedRef={kind:'outline',id:'current',version:3,digest:'outline-3'}
  const refreshedHead={kind:'outline',id:'current',version:3,digest:'outline-head-3'}
  const chapter={id:'chapter-1',parentId:null,order:1,title:'Original title',instructions:'',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]}
  const savedChapter={...chapter,title:'Saved title'}
  const candidate=(ref:typeof oldHead,status='CANDIDATE',contentChapter=chapter)=>({ref,status,inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[contentChapter],unmappedItems:[],warnings:[]}})
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({baseline:{ref:baselineRef,status:'CONFIRMED',payload:{schemaVersion:'1',taskGroupId:'g1',analyses:{},conflicts:[]}},groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValueOnce({baselineRef,editExpectedRef:oldHead,candidates:[candidate(oldHead)]} as never).mockResolvedValueOnce({baselineRef,editExpectedRef:refreshedHead,candidates:[candidate(oldHead),candidate(savedRef,'CANDIDATE',savedChapter)]} as never)
  vi.mocked(biddingApi.command).mockResolvedValueOnce({ref:savedRef} as never).mockResolvedValueOnce({ref:savedRef} as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Outline')) as HTMLElement).click();await flush()
  const title=host.querySelector('.outline-detail input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(title,'Saved title');title.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Saved title'}));await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Save outline')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'SAVE_OUTLINE',expected:oldHead,payload:{payload:{schemaVersion:'1',chapters:[expect.objectContaining({id:'chapter-1',title:'Saved title',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]})],unmappedItems:[],warnings:[]}}}))
  expect(host.textContent).toContain('Outline candidate V3')
  const confirm=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm outline')) as HTMLButtonElement
  expect(confirm.disabled).toBe(false)
  confirm.click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'CONFIRM_OUTLINE',expected:refreshedHead,payload:{outlineRef:savedRef}}))
})

it.each([403,404])('clears a dirty outline draft after a %s authorization response',async status=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const baselineRef={kind:'analysis-baseline',id:'baseline-1',version:1,digest:'baseline-1'}
  const outlineRef={kind:'outline',id:'current',version:2,digest:'outline-2'}
  const chapter={id:'chapter-1',parentId:null,order:1,title:'Original title',instructions:'',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValueOnce({baselineRef,editExpectedRef:outlineRef,candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{chapters:[chapter],unmappedItems:[],warnings:[]}}]} as never).mockRejectedValueOnce(Object.assign(new Error('restricted'),{response:{status}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Outline')) as HTMLElement).click();await flush()
  const title=host.querySelector('.outline-detail input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(title,'Sensitive unsaved outline');title.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Sensitive unsaved outline'}));await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Refresh') as HTMLButtonElement).click();await flush()
  expect(host.textContent).not.toContain('Sensitive unsaved outline')
})

it.each([403,404])('clears an open chapter draft after a %s authorization response',async status=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const outlineRef={kind:'outline',id:'ol1',version:2,digest:'outline-2'}
  const chapterRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter-3'}
  const editExpectedRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter-3'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValueOnce({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,selected:{ref:chapterRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Protected original'}]}}},candidates:[]}]} as never).mockRejectedValueOnce(Object.assign(new Error('restricted'),{response:{status}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Writing')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  const textarea=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(textarea,'Sensitive unsaved chapter');textarea.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Sensitive unsaved chapter'}));await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Refresh') as HTMLButtonElement).click();await flush()
  expect(host.textContent).not.toContain('Sensitive unsaved chapter')
  expect(host.querySelector('.el-dialog textarea')).toBeNull()
})

it.each([{status:401,clear:true},{status:403,clear:true},{status:404,clear:true},{status:410,clear:true},{status:409,clear:false},{status:503,clear:false}])('handles change-impact refresh status $status without retaining forbidden or losing transient data',async({status,clear})=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const sourceRef={kind:'source',id:'source-secret',version:1,digest:'source-digest'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.changeImpact).mockResolvedValueOnce({formalBlocked:true,events:[{eventId:'event-secret',changedRef:sourceRef,status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[sourceRef],formalBlocked:true,refLabels:[{ref:sourceRef,title:'Restricted source name'}]}}]} as never).mockRejectedValueOnce(Object.assign(new Error('change impact read'),{response:{status}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  expect(host.textContent).toContain('Restricted source name')
  expect(host.textContent).not.toContain('source-secret')
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Refresh') as HTMLButtonElement).click();await flush()
  if(clear){expect(host.textContent).not.toContain('Restricted source name')}else{expect(host.textContent).toContain('Restricted source name');expect(host.textContent).toContain('Could not refresh version impact.')}
})

it('sends only the server confirmation envelope with the current project head and refreshes after a version conflict',async()=>{
  const project=(version:number)=>({id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version,digest:`project-${version}`},bindings:{},selectedRefs:{}})
  const changedRef={kind:'source',id:'s1',version:2,digest:'source-2'}
  const serverPayload={eventId:'event-1',changedRef,unchangedRefs:[{kind:'chapter',id:'c2',version:4,digest:'chapter-4'}],resolutions:[]}
  const event={eventId:'event-1',changedRef,status:'PENDING',impact:{affectedRefs:[{kind:'chapter',id:'c1',version:4,digest:'chapter-4'}],unaffectedRefs:serverPayload.unchangedRefs,unknownRefs:[],formalBlocked:true,refLabels:[{ref:changedRef,title:'Changed tender file'}]},confirmation:{ready:true,payload:serverPayload}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValueOnce(project(2) as never).mockResolvedValueOnce(project(3) as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.changeImpact).mockResolvedValue({formalBlocked:true,events:[event]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('version moved'),{response:{status:409}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  const confirm=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm impact')) as HTMLButtonElement
  expect(confirm).toBeTruthy()
  confirm.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({expected:project(2).ref,action:'CONFIRM_CHANGE_IMPACT',payload:serverPayload,operationId:expect.any(String)}))
  expect(host.textContent).toContain('Changed tender file')
  expect(host.textContent).toContain('project version changed')
  expect(biddingApi.get).toHaveBeenCalledTimes(2)
})

it('clears cached change-impact labels when confirmation is denied',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'project-2'},bindings:{},selectedRefs:{}}
  const changedRef={kind:'source',id:'s1',version:2,digest:'source-2'}
  const event={eventId:'event-1',changedRef,status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[],formalBlocked:true,refLabels:[{ref:changedRef,title:'Sensitive change label'}]},confirmation:{ready:true,payload:{eventId:'event-1',changedRef,unchangedRefs:[],resolutions:[]}}}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.changeImpact).mockResolvedValue({formalBlocked:true,events:[event]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('denied'),{response:{status:403}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Analysis')) as HTMLElement).click();await flush()
  expect(host.textContent).toContain('Sensitive change label')
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm impact')) as HTMLButtonElement).click();await flush()
  expect(host.textContent).not.toContain('Sensitive change label')
  expect(host.textContent).toContain('Project or source access changed. Restricted content was cleared.')
})

it.each([{status:401,clear:true},{status:403,clear:true},{status:404,clear:true},{status:410,clear:true},{status:409,clear:false},{status:503,clear:false}])('clears scoped P2 chapter content on command response $status while preserving transient drafts',async({status,clear})=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Protected project marker',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const outlineRef={kind:'outline',id:'outline-1',version:2,digest:'outline-2'}
  const selectedRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter-3'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({confirmed:{ref:outlineRef,status:'CONFIRMED',inputRefs:[],payload:{chapters:[]}},candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({outlineRef,chapters:[{chapterId:'chapter-1',title:'Protected chapter title',editExpectedRef:selectedRef,selected:{ref:selectedRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Protected selected content'}]}}},candidates:[]}]} as never)
  vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('restricted'),{response:{status}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Writing')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  const textarea=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(textarea,'Unsaved sensitive edit')
  textarea.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Unsaved sensitive edit'}));await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  if(clear){
    expect(host.textContent).not.toContain('Protected project marker')
    expect(host.textContent).not.toContain('Protected selected content')
    expect(host.textContent).not.toContain('Unsaved sensitive edit')
    expect(host.querySelector('.el-dialog textarea')).toBeNull()
    expect(host.textContent).toContain('Restricted content was cleared.')
  }else{
    expect(host.textContent).toContain('Protected selected content')
    expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toBe('Unsaved sensitive edit')
  }
})

it('ignores a late access-denied command response after navigation loads another project',async()=>{
  let rejectCommand!:(reason:unknown)=>void
  const lateCommand=new Promise((_,reject)=>{rejectCommand=reject})
  const project=(id:string,name:string)=>({id,workspaceId:'ws-1',name,lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id,version:2,digest:`${id}-2`},bindings:{},selectedRefs:{}})
  const outlineRef={kind:'outline',id:'outline-1',version:2,digest:'outline-2'},chapterRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter-3'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValueOnce(project('p1','Old project marker') as never).mockResolvedValue(project('p2','New project marker') as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockResolvedValue([])
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({confirmed:{ref:outlineRef,status:'CONFIRMED',inputRefs:[],payload:{chapters:[]}},candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({outlineRef,chapters:[{chapterId:'chapter-1',title:'Old project chapter',editExpectedRef:chapterRef,selected:{ref:chapterRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{blocks:[{type:'paragraph',text:'Old project content'}]}}},candidates:[]}]} as never)
  vi.mocked(biddingApi.command).mockImplementationOnce(()=>lateCommand as never)
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Writing')) as HTMLElement).click();await flush()
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  harness.setProjectId?.('p2');await flush()
  expect(host.textContent).toContain('New project marker')
  rejectCommand(Object.assign(new Error('old scope denied'),{response:{status:403}}));await flush()
  expect(host.textContent).toContain('New project marker')
  expect(host.textContent).not.toContain('Restricted content was cleared.')
})

it.each(['P2 command denial','same-scope read denial'])('aborts sibling reads before clearing project state on %s',async denialPath=>{
  let resolveSources!:(value:unknown[])=>void,capturedSignal:AbortSignal|undefined
  const pendingSources=new Promise<unknown[]>(resolve=>{resolveSources=resolve})
  const project={id:'p1',workspaceId:'ws-1',name:'Protected project marker',lotName:'Lot A',ownerId:'7',version:2,stage:'SETUP',capabilities:{canApprove:true},ref:{kind:'project',id:'p1',version:2,digest:'d2'},bindings:{},selectedRefs:{}}
  const outlineRef={kind:'outline',id:'outline-1',version:2,digest:'outline-2'},chapterRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter-3'},candidateRef={kind:'chapter',id:'candidate-1',version:4,digest:'candidate-4'}
  vi.mocked(biddingApi.capabilities).mockResolvedValue({enabled:true,canWrite:true,canApprove:true})
  vi.mocked(biddingApi.get).mockResolvedValue(project as never)
  vi.mocked(biddingApi.members).mockResolvedValue([{userId:'7',nickname:'Owner'}] as never)
  vi.mocked(biddingApi.employees).mockResolvedValue([])
  vi.mocked(biddingApi.sources).mockImplementation((_ws,_id,signal)=>{capturedSignal=signal;return pendingSources as never})
  vi.mocked(biddingApi.sourceSetHead).mockResolvedValue(null)
  vi.mocked(biddingApi.analysis).mockResolvedValue({groups:[]} as never)
  vi.mocked(biddingApi.outline).mockResolvedValue({confirmed:{ref:outlineRef,status:'CONFIRMED',inputRefs:[],payload:{chapters:[]}},candidates:[]} as never)
  vi.mocked(biddingApi.writing).mockResolvedValue({outlineRef,chapters:[{chapterId:'chapter-1',title:'Protected chapter',editExpectedRef:chapterRef,selected:{ref:chapterRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{blocks:[{type:'paragraph',text:'Protected chapter body'}]}}},candidates:[{ref:candidateRef,status:'CANDIDATE',headGuard:chapterRef,inputRefs:[outlineRef],payload:{chapter:{blocks:[{type:'paragraph',text:'Candidate body'}]}}}]}]} as never)
  if(denialPath==='P2 command denial')vi.mocked(biddingApi.command).mockRejectedValueOnce(Object.assign(new Error('restricted'),{response:{status:403}}))
  else vi.mocked(biddingApi.sourceSetHead).mockRejectedValueOnce(Object.assign(new Error('restricted origin'),{response:{status:403}}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingWorkbench);app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(capturedSignal).toBeTruthy()
  if(denialPath==='P2 command denial'){
    ;([...host.querySelectorAll('.el-tabs__item')].find(tab=>tab.textContent?.includes('Writing')) as HTMLElement).click();await flush()
    ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Adopt candidate')) as HTMLButtonElement).click();await flush()
  }else await flush()
  expect(capturedSignal?.aborted).toBe(true)
  resolveSources([{sourceId:'late-source',version:1,kind:'TENDER',filename:'Late protected source marker',digest:'late-digest',readStatus:'READY',problems:[],blocks:[]}])
  await flush()
  expect(host.textContent).not.toContain('Late protected source marker')
  expect(host.textContent).not.toContain('Protected chapter body')
  expect(host.textContent).not.toContain('Candidate body')
  expect(host.textContent).toContain(denialPath==='P2 command denial'?'Restricted content was cleared.':'You no longer have access to this project.')
})

it('requires an explicit reason to exclude each blank page before confirming a review-needed source',async()=>{
  vi.mocked(biddingApi.command).mockResolvedValue({} as never)
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  const source={sourceId:'s1',version:1,kind:'TENDER',filename:'tender.pdf',digest:'sha',readStatus:'NEEDS_REVIEW',problems:['EMPTY_PDF_PAGE:2'],blocks:[{id:'b-empty',pdfPage:2,locator:'page:2',text:'',kind:'EMPTY_PAGE',quality:'NEEDS_REVIEW'}]}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingSources,{sources:[source],project,sourceSet:null,loading:false,canWrite:true,canApprove:true,analystReady:true,activeAnalysis:false,dispatching:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const confirm=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm sources')) as HTMLButtonElement
  expect(confirm.disabled).toBe(true)
  ;(host.querySelector('.el-table__row .el-checkbox input[type="checkbox"]') as HTMLInputElement).click();await flush()
  ;(host.querySelectorAll('.el-table__row .el-checkbox input[type="checkbox"]')[1] as HTMLInputElement).click();await flush()
  const reason=host.querySelector('.exclusion input[type="text"]') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(reason,'Page intentionally blank');reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Page intentionally blank'}));await flush()
  expect(confirm.disabled).toBe(false)
  confirm.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'CONFIRM_SOURCE_SET',payload:expect.objectContaining({exclusions:[{sourceRef:{kind:'source',id:'s1',version:1,digest:'sha'},blockId:'b-empty',reason:'Page intentionally blank'}]})}))
})

it('confirms explicitly selected ready versions without blocking on an unselected failed or unsupported version',async()=>{
  vi.mocked(biddingApi.command).mockResolvedValue({} as never)
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  const sources=[
    {sourceId:'ready',version:1,kind:'TENDER',filename:'ready.pdf',digest:'ready-digest',readStatus:'READY',problems:[],blocks:[{id:'b1',locator:'page:1',text:'Tender clause',kind:'TEXT',quality:'READY'}]},
    {sourceId:'failed',version:1,kind:'TENDER',filename:'failed.pdf',digest:'failed-digest',readStatus:'FAILED',problems:['READ_FAILED'],blocks:[]},
    {sourceId:'review',version:1,kind:'TENDER',filename:'review.pdf',digest:'review-digest',readStatus:'NEEDS_REVIEW',problems:['IMAGE_REQUIRES_REVIEW'],blocks:[{id:'image',locator:'page:1/image:0',text:'',kind:'IMAGE',quality:'NEEDS_REVIEW'}]},
  ]
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingSources,{sources,project,sourceSet:null,loading:false,canWrite:true,canApprove:true,analystReady:true,activeAnalysis:false,dispatching:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const rows=[...host.querySelectorAll('.el-table__row')]
  ;(rows[0]!.querySelector('input[type="checkbox"]') as HTMLInputElement).click();await flush()
  const confirm=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm sources')) as HTMLButtonElement
  expect(confirm.disabled).toBe(false)
  confirm.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({payload:expect.objectContaining({sourceRefs:[{kind:'source',id:'ready',version:1,digest:'ready-digest'}],exclusions:[]})}))
})

it('shows an actionable empty state when no source files exist',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingSources,{sources:[],project,sourceSet:null,loading:false,canWrite:true,canApprove:true,analystReady:true,activeAnalysis:false,dispatching:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('No files uploaded')
  expect(([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm sources')) as HTMLButtonElement).disabled).toBe(true)
})
