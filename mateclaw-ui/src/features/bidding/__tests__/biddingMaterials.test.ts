import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingMaterials from '../components/BiddingMaterials.vue'
import { biddingApi } from '../api/biddingApi'

vi.mock('../api/biddingApi',()=>({biddingApi:{knowledgeBases:vi.fn(),knowledgePages:vi.fn(),knowledgePage:vi.fn()}}))
let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
function mount(){host=document.createElement('div');document.body.append(host);app=createApp(BiddingMaterials,{workspaceId:'ws-1',project:{id:'bid-1',workspaceId:'ws-1',name:'Bridge',lotName:'Lot 1',ownerId:'7',version:4,stage:'SETUP',ref:{kind:'project',id:'bid-1',version:4,digest:'project4'},bindings:{},selectedRefs:{}},items:[{ref:{kind:'material',id:'release-material',version:1,digest:'handoff-digest'},source:'PRESALES_RELEASE',title:'Bridge delivery solution',version:1,digest:'handoff-digest',validity:'VALID',applicability:'Frozen presales handoff',solutionVersion:7,publishedAt:'2026-09-24',receivedAt:'2026-09-25T10:00:00Z'}],loading:false,canWrite:true});app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return host}
function mountReactive(){const props=reactive({workspaceId:'ws-1',project:{id:'bid-1',workspaceId:'ws-1',name:'Bridge',lotName:'Lot 1',ownerId:'7',version:4,stage:'SETUP',ref:{kind:'project',id:'bid-1',version:4,digest:'project4'},bindings:{},selectedRefs:{}},items:[],loading:false,canWrite:true}),onBind=vi.fn();host=document.createElement('div');document.body.append(host);app=createApp(defineComponent({setup(){return()=>h(BiddingMaterials as never,{...props,onBind})}}));app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return {root:host,props,onBind}}
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(reason?:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
async function choose(index:number,label:string){const select=document.querySelectorAll('.el-select')[index] as HTMLElement;select.click();await flush();const option=[...document.body.querySelectorAll('.el-select-dropdown__item')].find(item=>item.textContent?.includes(label)) as HTMLElement;option.click();await flush()}

it('renders frozen presales materials as a distinct authorized source and clears revoked page content',async()=>{
  vi.mocked(biddingApi.knowledgeBases).mockResolvedValue([{id:'kb1',name:'Engineering'}] as never)
  vi.mocked(biddingApi.knowledgePages).mockResolvedValue([{id:'12',slug:'safety',title:'Safety standard'},{id:'13',slug:'restricted',title:'Restricted standard'}] as never)
  vi.mocked(biddingApi.knowledgePage).mockResolvedValueOnce({id:'12',slug:'safety',title:'Safety standard',content:'Restricted page marker'} as never).mockRejectedValueOnce(Object.assign(new Error('denied'),{response:{status:403}}))
  const root=mount();await flush()
  expect(root.textContent).toContain('Presales release')
  expect(root.textContent).toContain('Bridge delivery solution')
  expect(root.textContent).toContain('Frozen presales handoff')
  expect(root.textContent).toContain('Received 2026-09-25T10:00:00Z')
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Select material')) as HTMLButtonElement).click();await flush()
  await choose(0,'Engineering');await choose(1,'Safety standard')
  expect(root.textContent).toContain('Restricted page marker')
  await choose(1,'Restricted standard')
  expect(root.textContent).not.toContain('Restricted page marker')
  expect(root.textContent).toContain('Knowledge page access changed. Content was cleared.')
})

it('ignores a late page response after the user selects a different knowledge page and preserves the exact string id',async()=>{
  const late=deferred<{id:string;slug:string;title:string;content:string}>()
  vi.mocked(biddingApi.knowledgeBases).mockResolvedValue([{id:'kb-1',name:'Engineering'}] as never)
  vi.mocked(biddingApi.knowledgePages).mockResolvedValue([{id:'9007199254740993',slug:'older',title:'Older page'},{id:'9007199254740994',slug:'newer',title:'Newer page'}] as never)
  vi.mocked(biddingApi.knowledgePage).mockImplementation((_ws,_kb,slug)=>slug==='older'?late.promise:Promise.resolve({id:'9007199254740994',slug:'newer',title:'Newer page',content:'Current page content'} as never))
  const {root,onBind}=mountReactive();await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Select material')) as HTMLButtonElement).click();await flush()
  await choose(0,'Engineering');await choose(1,'Older page')
  await choose(1,'Newer page')
  expect(root.textContent).toContain('Current page content')
  late.resolve({id:'9007199254740993',slug:'older',title:'Older page',content:'Stale page content'});await flush()
  expect(root.textContent).toContain('Current page content')
  expect(root.textContent).not.toContain('Stale page content')
  const applicability=root.querySelector('.page-preview + .el-form-item input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(applicability,'Applies to implementation')
  applicability.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Applies to implementation'}));await flush()
  ;([...root.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Bind material')) as HTMLButtonElement).click();await flush()
  expect(onBind).toHaveBeenCalledWith(expect.objectContaining({pageId:'9007199254740994',knowledgeBaseId:'kb-1'}))
})

it.each(['workspace','project'])('clears an in-flight knowledge page when the %s scope changes',async scope=>{
  const late=deferred<{id:string;slug:string;title:string;content:string}>()
  vi.mocked(biddingApi.knowledgeBases).mockResolvedValue([{id:'kb-1',name:'Engineering'}] as never)
  vi.mocked(biddingApi.knowledgePages).mockResolvedValue([{id:'12',slug:'sensitive',title:'Sensitive page'}] as never)
  vi.mocked(biddingApi.knowledgePage).mockReturnValue(late.promise as never)
  const {root,props}=mountReactive();await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Select material')) as HTMLButtonElement).click();await flush()
  await choose(0,'Engineering');await choose(1,'Sensitive page')
  if(scope==='workspace'){props.workspaceId='ws-2';props.project={...props.project,workspaceId:'ws-2'}}else props.project={...props.project,id:'bid-2'}
  await flush()
  late.resolve({id:'12',slug:'sensitive',title:'Sensitive page',content:'Old scoped content'});await flush()
  expect(root.textContent).not.toContain('Old scoped content')
  expect(root.querySelector('.el-dialog')).toBeNull()
})

it('does not refill the knowledge-page preview when the picker closes during a request',async()=>{
  const late=deferred<{id:string;slug:string;title:string;content:string}>()
  vi.mocked(biddingApi.knowledgeBases).mockResolvedValue([{id:'kb-1',name:'Engineering'}] as never)
  vi.mocked(biddingApi.knowledgePages).mockResolvedValue([{id:'12',slug:'sensitive',title:'Sensitive page'}] as never)
  vi.mocked(biddingApi.knowledgePage).mockReturnValue(late.promise as never)
  const {root}=mountReactive();await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Select material')) as HTMLButtonElement).click();await flush()
  await choose(0,'Engineering');await choose(1,'Sensitive page')
  ;([...root.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.trim()==='Cancel') as HTMLButtonElement).click();await flush()
  late.resolve({id:'12',slug:'sensitive',title:'Sensitive page',content:'Closed picker content'});await flush()
  expect(root.textContent).not.toContain('Closed picker content')
  expect(root.querySelector('.el-dialog')).toBeNull()
})
