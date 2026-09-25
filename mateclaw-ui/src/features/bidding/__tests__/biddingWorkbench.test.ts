import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingSources from '../components/BiddingSources.vue'
import BiddingWorkbench from '../pages/BiddingWorkbench.vue'
import { biddingApi } from '../api/biddingApi'

const harness=vi.hoisted(()=>({projectId:'p1',push:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>({params:{id:harness.projectId}}),useRouter:()=>({push:harness.push})}))
vi.mock('@/stores/useWorkspaceStore',()=>({useWorkspaceStore:()=>({currentWorkspaceId:'ws-1',registerBeforeSwitch:()=>()=>{}})}))
vi.mock('../api/biddingApi',()=>({biddingApi:{command:vi.fn(),upload:vi.fn(),capabilities:vi.fn(),get:vi.fn(),members:vi.fn(),employees:vi.fn(),sources:vi.fn(),sourceSetHead:vi.fn(),analysis:vi.fn(),tasks:vi.fn(),task:vi.fn(),evidence:vi.fn(),content:vi.fn(),revision:vi.fn()}}))

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
it('retains project settings through a real 409 and refreshes the expected revision before retry',async()=>{
  const project=(version:number,name:string)=>({id:'p1',workspaceId:'ws-1',name,lotName:'Lot A',ownerId:'7',version,stage:'SETUP',ref:{kind:'project',id:'p1',version,digest:`d${version}`},bindings:{},selectedRefs:{}})
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
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.trim()==='Reload') as HTMLButtonElement).click();await flush()
  expect((host.querySelector('.el-dialog input') as HTMLInputElement).value).toBe('Check the source clause')
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toContain('Locally revised')
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  expect(biddingApi.command).toHaveBeenLastCalledWith('ws-1','p1',expect.objectContaining({action:'EDIT_ANALYSIS_ITEM',expected:expect.objectContaining({version:3,digest:'d3'})}))
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
