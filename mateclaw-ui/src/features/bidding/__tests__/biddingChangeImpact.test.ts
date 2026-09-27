import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingChangeImpact from '../components/BiddingChangeImpact.vue'
import type { ChangeImpactView } from '../api/types'

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
function mount(view:ChangeImpactView,onRefresh=vi.fn(),error='',onConfirm=vi.fn()){host=document.createElement('div');document.body.append(host);app=createApp(BiddingChangeImpact,{view,error,onRefresh,onConfirm,canConfirm:true});app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return {root:host,onRefresh,onConfirm}}

it('shows every pending event by business label without exposing refs or offering unproved confirmation',async()=>{
  const sourceRef={kind:'source',id:'source-secret-1',version:'2',digest:'source-digest'}
  const chapterRef={kind:'chapter',id:'chapter-secret-1',version:'4',digest:'chapter-digest'}
  const replacementRef={kind:'source',id:'source-secret-1',version:'3',digest:'replacement-digest'}
  const view:ChangeImpactView={formalBlocked:true,events:[
    {eventId:'event-secret-1',changedRef:sourceRef,replacementRef,status:'PENDING',impact:{affectedRefs:[chapterRef],unaffectedRefs:[],unknownRefs:[chapterRef],formalBlocked:true,refLabels:[{ref:sourceRef,title:'招标文件：技术规范.docx'},{ref:replacementRef,title:'招标文件：技术规范修订.docx'},{ref:chapterRef,title:'章节：施工组织设计'}]}},
    {eventId:'event-secret-2',changedRef:{kind:'outline',id:'outline-secret',version:5,digest:'outline-digest'},status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[],formalBlocked:true},refLabels:[{ref:{kind:'outline',id:'outline-secret',version:5,digest:'outline-digest'},title:'技术目录'}]},
  ]}
  const {root,onRefresh}=mount(view);await flush()
  expect(root.textContent).toContain('技术规范.docx')
  expect(root.textContent).toContain('技术规范修订.docx')
  expect(root.textContent).toContain('章节：施工组织设计')
  expect(root.textContent).toContain('技术目录')
  expect(root.textContent).not.toContain('source-secret-1')
  expect(root.textContent).not.toContain('chapter-secret-1')
  expect(root.textContent).not.toContain('event-secret-1')
  expect(root.querySelectorAll('.impact-event')).toHaveLength(2)
  expect([...root.querySelectorAll('button')].some(button=>button.textContent?.includes('Confirm'))).toBe(false)
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Refresh impact')) as HTMLButtonElement).click();await flush()
  expect(onRefresh).toHaveBeenCalledTimes(1)
})

it('fails closed when event impact or labels are incomplete',async()=>{
  const {root}=mount({formalBlocked:true,events:[{eventId:'evt',changedRef:{kind:'chapter',id:'secret-id',version:1,digest:'d'},status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[],formalBlocked:true}}]});await flush()
  expect(root.textContent).toContain('Formal progress is blocked')
  expect(root.textContent).toContain('Technical chapter · V1')
  expect(root.textContent).not.toContain('secret-id')
  expect(root.textContent).toContain('confirmation is unavailable')
})

it('offers confirmation only for a server-ready payload with no unknown refs, even when affected refs remain',async()=>{
  const changedRef={kind:'source',id:'s1',version:2,digest:'source-2'}
  const affectedRef={kind:'chapter',id:'c1',version:4,digest:'chapter-4'}
  const payload={eventId:'evt-server',changedRef,unchangedRefs:[{kind:'chapter',id:'c2',version:4,digest:'chapter-4'}],resolutions:[]}
  const event={eventId:'evt-server',changedRef,status:'PENDING',impact:{affectedRefs:[affectedRef],unaffectedRefs:payload.unchangedRefs,unknownRefs:[],formalBlocked:true},confirmation:{ready:true,payload}}
  const {root,onConfirm}=mount({formalBlocked:true,events:[event]});await flush()
  const button=[...root.querySelectorAll('button')].find(item=>item.textContent?.includes('Confirm impact')) as HTMLButtonElement
  expect(button).toBeTruthy()
  button.click();await flush()
  expect(onConfirm).toHaveBeenCalledExactlyOnceWith(payload)
})

it('keeps confirmation unavailable when the server reports unknown refs even with a ready envelope',async()=>{
  const changedRef={kind:'source',id:'s1',version:2,digest:'source-2'}
  const payload={eventId:'evt-server',changedRef,unchangedRefs:[],resolutions:[]}
  const {root}=mount({formalBlocked:true,events:[{eventId:'evt-server',changedRef,status:'PENDING',impact:{affectedRefs:[],unaffectedRefs:[],unknownRefs:[{kind:'chapter',id:'c1',version:4,digest:'chapter-4'}],formalBlocked:true},confirmation:{ready:true,payload}}]});await flush()
  expect([...root.querySelectorAll('button')].some(item=>item.textContent?.includes('Confirm impact'))).toBe(false)
})

it('shows a scoped read failure while withholding previously authorized labels',async()=>{
  const {root}=mount({formalBlocked:true,events:[]},vi.fn(),'Change impact data is unavailable and was cleared.');await flush()
  expect(root.textContent).toContain('Change impact data is unavailable and was cleared.')
  expect(root.textContent).not.toContain('source-secret')
})
