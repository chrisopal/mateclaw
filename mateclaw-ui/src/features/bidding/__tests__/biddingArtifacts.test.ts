import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingArtifacts from '../components/BiddingArtifacts.vue'
import BiddingApprovalDialog from '../components/BiddingApprovalDialog.vue'
import type { ArtifactApprovalContext, ArtifactMetadata, Ref } from '../api/types'

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
const ref=(kind:string,id:string):Ref=>({kind,id,version:3,digest:`${id}-digest`})
const artifact:ArtifactMetadata={artifactId:'artifact-1',filename:'Technical proposal.docx',mode:'candidate',status:'CANDIDATE',digest:'sha256-digest',byteSize:2048,manuscriptRef:ref('manuscript','ms-3'),templateRef:ref('template','template-3'),formatRef:ref('format','format-3'),formalAvailable:false}

it('requires PREPARE_EXPORT before candidate generation and separates preview from candidate',async()=>{
  const prepare=vi.fn(),generate=vi.fn()
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingArtifacts,{templates:[{name:'Technical template',status:'UNPREPARED',format:'docx'}],artifacts:[],loading:false,busy:false,canWrite:true,canApprove:true,onPrepare:prepare,onGenerate:generate})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const candidate=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Generate candidate')) as HTMLButtonElement
  expect(candidate.disabled).toBe(true)
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Prepare export')) as HTMLButtonElement).click();await flush()
  expect(prepare).toHaveBeenCalledOnce()
})

it('does not expose candidate approval to a project member without approval capability',async()=>{
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingArtifacts,{templates:[{name:'Technical template',status:'PREPARED',format:'docx',ref:ref('template','t'),formatRef:ref('format','f')}],artifacts:[artifact],loading:false,busy:false,canWrite:true,canApprove:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Approve this file'))).toBe(false)
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Download and inspect'))).toBe(true)
})

it('downloads preview mode without exposing approval',async()=>{
  const download=vi.fn()
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingArtifacts,{templates:[{name:'Technical template',status:'PREPARED',format:'docx',ref:ref('template','t'),formatRef:ref('format','f')}],artifacts:[{...artifact,mode:'preview',status:'PREVIEW'}],loading:false,busy:false,canWrite:true,canApprove:true,onDownload:download})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('Preview')
  ;([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Download preview')) as HTMLButtonElement).click();await flush()
  expect(download).toHaveBeenCalledWith(expect.objectContaining({artifactId:'artifact-1'}),'preview')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Approve this file'))).toBe(false)
})

it('renders the real artifact metadata shape before approval context is fetched',async()=>{
  const serverMetadata:ArtifactMetadata={artifactId:'artifact-manifest-1',mode:'candidate',status:'CANDIDATE',filename:'Technical proposal.docx',digest:'sha256-manifest',byteSize:8192,manifest:{artifactId:'artifact-manifest-1',sha256:'sha256-manifest',byte_size:8192},checks:{current:true},formalAvailable:false}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingArtifacts,{templates:[],artifacts:[serverMetadata],loading:false,busy:false,canWrite:true,canApprove:true})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('Technical proposal.docx')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Download and inspect'))).toBe(true)
  expect(host.querySelector('details')?.textContent).not.toContain('undefined')
})

it('keeps previews and technical blockers from opening an approval decision',async()=>{
  const emit=vi.fn(),ctx:ArtifactApprovalContext={...artifact,manuscriptRef:ref('manuscript','ms-3'),templateRef:ref('template','template-3'),formatRef:ref('format','format-3'),status:'NOT_APPROVABLE',reasonCode:'PREVIEW_NOT_APPROVABLE'}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingApprovalDialog,{modelValue:true,context:ctx,busy:false,conflict:false,onApprove:emit})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('Preview files cannot be approved')
  const button=[...host.querySelectorAll('.el-dialog__footer button')].find(item=>item.textContent?.includes('Approve this file')) as HTMLButtonElement
  expect(button.disabled).toBe(true)
  expect(emit).not.toHaveBeenCalled()
})

it('records exact server refs only after fresh READY context and human inspection',async()=>{
  const emit=vi.fn(),ctx:ArtifactApprovalContext={...artifact,manuscriptRef:ref('manuscript','ms-3'),templateRef:ref('template','template-3'),formatRef:ref('format','format-3'),status:'READY',artifactRef:ref('artifact','artifact-1'),reviewRef:ref('review','whole-book-review')}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingApprovalDialog,{modelValue:true,context:ctx,busy:false,conflict:false,onApprove:emit})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const checkboxes=[...host.querySelectorAll('.el-dialog .el-checkbox')]
  checkboxes.forEach(input=>{(input as HTMLElement).dispatchEvent(new MouseEvent('click',{bubbles:true}))})
  await flush()
  expect(checkboxes.every(input=>input.classList.contains('is-checked'))).toBe(true)
  const note=host.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(note,'Checked headings and page breaks')
  note.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Checked headings and page breaks'}));await flush()
  const approve=[...host.querySelectorAll('.el-dialog__footer button')].find(item=>item.textContent?.includes('Approve this file')) as HTMLButtonElement
  expect(approve.disabled).toBe(false)
  approve.click();await flush()
  expect(emit).toHaveBeenCalledWith(expect.objectContaining({artifactId:'artifact-1',digest:'sha256-digest',reviewRef:ctx.reviewRef,inspection:{opened:true,layoutChecked:true,reason:'Checked headings and page breaks'}}))
})
