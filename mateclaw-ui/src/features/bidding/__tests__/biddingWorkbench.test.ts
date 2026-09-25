import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingAnalysis from '../components/BiddingAnalysis.vue'
import BiddingSources from '../components/BiddingSources.vue'
import { biddingApi } from '../api/biddingApi'

vi.mock('../api/biddingApi',()=>({biddingApi:{command:vi.fn(),upload:vi.fn()}}))

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
it('retains the edited JSON when the command reports a version conflict',async()=>{
  const group={taskGroupId:'group-1',status:'SUCCEEDED',skills:{'bidding-elimination-analysis':{schemaVersion:'1',items:[],coverage:{processedBlockIds:['b1'],unprocessedBlockIds:[]},warnings:[]}},conflicts:[],complete:true}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingAnalysis,{analysis:{groups:[group]},canWrite:true,canApprove:true,onEdit:()=>{}})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit result'))!.click();await flush()
  const textarea=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  textarea.value='{"schemaVersion":"1","items":[],"coverage":{"processedBlockIds":["b1"],"unprocessedBlockIds":[]},"warnings":[]}'
  textarea.dispatchEvent(new Event('input',{bubbles:true}));await flush()
  ;([...host.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  expect(host.querySelector('.el-dialog textarea')).toBeTruthy()
  expect((host.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toContain('processedBlockIds')
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
  ;(host.querySelector('.el-checkbox input[type="checkbox"]') as HTMLInputElement).click();await flush()
  const reason=host.querySelector('.exclusion input[type="text"]') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(reason,'Page intentionally blank');reason.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Page intentionally blank'}));await flush()
  expect(confirm.disabled).toBe(false)
  confirm.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'CONFIRM_SOURCE_SET',payload:expect.objectContaining({exclusions:[{sourceRef:{kind:'source',id:'s1',version:1,digest:'sha'},blockId:'b-empty',reason:'Page intentionally blank'}]})}))
})

it('shows an actionable empty state when no source files exist',async()=>{
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingSources,{sources:[],project,sourceSet:null,loading:false,canWrite:true,canApprove:true,analystReady:true,activeAnalysis:false,dispatching:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('No files uploaded')
  expect(([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm sources')) as HTMLButtonElement).disabled).toBe(true)
})
