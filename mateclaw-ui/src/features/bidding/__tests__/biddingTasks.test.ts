import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingTaskDrawer from '../components/BiddingTaskDrawer.vue'
import { biddingApi } from '../api/biddingApi'

vi.mock('../api/biddingApi',()=>({biddingApi:{tasks:vi.fn(),task:vi.fn(),command:vi.fn()}}))
let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
it('shows a failed attempt and sends one explicit retry command',async()=>{
  const task={taskId:'task-1',status:'FAILED',attemptCount:1}
  vi.mocked(biddingApi.tasks).mockResolvedValue({items:[task],total:1,page:1,pageSize:100})
  vi.mocked(biddingApi.task).mockResolvedValue({taskId:'task-1',status:'FAILED',attemptCount:1,attempts:[{attemptNo:1,state:'FAILED',rejection:{code:'MODEL_TIMEOUT'}}]} as never)
  vi.mocked(biddingApi.command).mockResolvedValue({status:'QUEUED'})
  const project={id:'p1',workspaceId:'ws-1',name:'Tender',lotName:'Lot',ownerId:'7',version:2,stage:'SETUP',ref:{kind:'project',id:'p1',version:2,digest:'d'},bindings:{},selectedRefs:{}}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingTaskDrawer,{modelValue:true,workspaceId:'ws-1',project})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  ;[...document.querySelectorAll('tr')].find(row=>row.textContent?.includes('FAILED'))?.dispatchEvent(new MouseEvent('click',{bubbles:true}));await flush()
  expect(document.body.textContent).toContain('MODEL_TIMEOUT')
  ;[...document.querySelectorAll('button')].find(button=>button.textContent?.includes('Retry'))!.click();await flush()
  expect(biddingApi.command).toHaveBeenCalledTimes(1)
  expect(biddingApi.command).toHaveBeenCalledWith('ws-1','p1',expect.objectContaining({action:'RETRY_TASK',payload:{taskId:'task-1'}}))
})
