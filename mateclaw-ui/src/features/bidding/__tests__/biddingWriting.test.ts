import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingWriting from '../components/BiddingWriting.vue'
import { canAdopt } from '../shared/state'
import type { WritingView } from '../api/types'

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
const outlineRef={kind:'outline',id:'ol1',version:2,digest:'outline2'}
const currentRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter3'}
const candidateRef={kind:'chapter',id:'candidate-1',version:4,digest:'candidate4'}
const editExpectedRef={kind:'chapter',id:'chapter-1',version:3,digest:'chapter3'}
function mount(view:WritingView,onCommand=vi.fn(),canWrite=true){host=document.createElement('div');document.body.append(host);const onAssemble=vi.fn();app=createApp(BiddingWriting,{view,canWrite,canDispatch:canWrite,onCommand,onDispatch:vi.fn(),onRetryTask:vi.fn(),onAssemble});app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return {root:host,onCommand,onAssemble}}

it('prevents batch writing until a confirmed outline is available',async()=>{
  const {root}=mount({chapters:[]});await flush()
  expect(root.textContent).toContain('Chapter writing is unavailable until the outline is confirmed.')
  expect(([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Write selected chapters')) as HTMLButtonElement).disabled).toBe(true)
})

it('shows the persisted six-chapter manuscript with readable chapter text and structured tables',async()=>{
  const chapters=Array.from({length:6},(_,index)=>({chapterId:`private-chapter-${index+1}`,title:`Section ${index+1}`,candidates:[]}))
  const manuscriptChapters=chapters.map((chapter,index)=>({chapterId:chapter.chapterId,chapter:{chapterId:chapter.chapterId,title:chapter.title,blocks:index===0?[{type:'heading',level:2,text:'Execution plan'},{type:'paragraph',text:'Persisted chapter text'},{type:'table',columns:['Phase','Owner'],rows:[['Plan','Project lead'],['Build','Delivery team']]}]:[{type:'paragraph',text:`Persisted body ${index+1}`}]}}))
  const selectedRefs=chapters.map((chapter,index)=>({kind:'chapter',id:chapter.chapterId,version:5,digest:`chapter-${index+1}`}))
  const currentChapters=chapters.map((chapter,index)=>({...chapter,selected:{ref:selectedRefs[index]!,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:chapter.chapterId,blocks:[]},missingMaterials:index===0?['Current drawing pending']:[],unresolvedItems:index===0?['Confirm site access']:[]}}}))
  const view:WritingView={outlineRef,chapters:currentChapters,manuscript:{ref:{kind:'manuscript',id:'private-manuscript-id',version:7,digest:'manuscript-digest'},status:'DRAFT_PENDING_REVIEW',inputRefs:[outlineRef,...selectedRefs],payload:{schemaVersion:'1',status:'DRAFT_PENDING_REVIEW',chapters:manuscriptChapters}}}
  const {root}=mount(view);await flush()
  expect(root.querySelector('.manuscript-summary')?.textContent).toContain('Assembled technical draft')
  expect(root.querySelector('.manuscript-summary')?.textContent).toContain('Pending review')
  expect(root.querySelector('.manuscript-summary')?.textContent).toContain('V7 · 6 chapters')
  expect(root.querySelector('.manuscript-summary')?.textContent).not.toContain('Needs reassembly')
  expect(root.textContent).toContain('Section 6')
  expect(root.textContent).toContain('Persisted chapter text')
  expect(root.textContent).toContain('Materials and confirmations pending')
  expect(root.textContent).toContain('Current drawing pending')
  expect(root.textContent).toContain('Confirm site access')
  expect(root.querySelectorAll('.manuscript-summary table')).toHaveLength(1)
  expect([...root.querySelectorAll('.manuscript-summary th')].map(cell=>cell.textContent)).toEqual(['Phase','Owner'])
  expect([...root.querySelectorAll('.manuscript-summary tbody tr')].map(row=>[...row.querySelectorAll('td')].map(cell=>cell.textContent?.trim()).join('|'))).toEqual(['Plan|Project lead','Build|Delivery team'])
  expect(root.textContent).not.toContain('private-chapter-1')
  expect(root.textContent).not.toContain('private-manuscript-id')
  expect([...root.querySelectorAll('button')].some(button=>/review|export/i.test(button.textContent||''))).toBe(false)
})

it('marks an assembled manuscript historical when a current selected chapter head has advanced',async()=>{
  const changedSelected={ref:{kind:'chapter',id:'chapter-1',version:4,digest:'chapter-4'},status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[]}}}
  const view:WritingView={outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',selected:changedSelected,candidates:[]}],manuscript:{ref:{kind:'manuscript',id:'manuscript-1',version:2,digest:'manuscript-2'},status:'DRAFT_PENDING_REVIEW',inputRefs:[outlineRef,currentRef],payload:{chapters:[{chapterId:'chapter-1',chapter:{blocks:[{type:'paragraph',text:'Previous assembled version'}]}}]}}}
  const {root}=mount(view);await flush()
  expect(root.querySelector('.manuscript-summary')?.textContent).toContain('Needs reassembly')
  expect(root.textContent).toContain('Previous assembled version')
})

it('does not allow a read-only viewer to assemble selected chapter heads',async()=>{
  const selected={ref:currentRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Body'}]}}}
  const {root,onAssemble}=mount({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',selected,candidates:[]}]},vi.fn(),false);await flush()
  const assemble=[...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Assemble technical draft')) as HTMLButtonElement
  expect(assemble.disabled).toBe(true)
  assemble.click();await flush()
  expect(onAssemble).not.toHaveBeenCalled()
})

it('keeps object-shaped material gaps readable before and after candidate adoption',async()=>{
  const gaps={missingMaterials:[{title:'Current site drawing required',id:'internal-material-1',ref:{kind:'material',id:'secret-ref',version:1,digest:'secret'}},{id:'only-id'}],unresolvedItems:[{requirement:'Confirm accessible route',reason:'No approved route evidence',ref:'private-requirement-ref'}]}
  const candidate={ref:candidateRef,status:'CANDIDATE',headGuard:editExpectedRef,inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Candidate body'}]},...gaps}}
  const before=mount({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,selected:{ref:currentRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[]}}},candidates:[candidate]}]});await flush()
  expect(before.root.textContent).toContain('Current site drawing required')
  expect(before.root.textContent).toContain('Confirm accessible route')
  expect(before.root.textContent).toContain('No approved route evidence')
  expect(before.root.textContent).not.toContain('secret-ref')
  const adopt=[...before.root.querySelectorAll('button')].find(button=>button.textContent?.includes('Adopt candidate')) as HTMLButtonElement
  expect(adopt.disabled).toBe(false)
  adopt.click();await flush()
  expect(before.onCommand).toHaveBeenCalledWith({action:'ADOPT_CHAPTER',expected:editExpectedRef,payload:{chapterId:'chapter-1',candidateRef}})
  const acceptedRef={kind:'chapter',id:'chapter-1',version:4,digest:'chapter-4'}
  await app?.unmount();app=undefined;host?.remove();host=undefined
  const after=mount({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef:acceptedRef,selected:{ref:acceptedRef,status:'SELECTED',inputRefs:[outlineRef],payload:candidate.payload},candidates:[]}]});await flush()
  expect(after.root.textContent).toContain('Current site drawing required')
  expect(after.root.textContent).toContain('Confirm accessible route')
  expect(after.root.textContent).toContain('No approved route evidence')
  expect(after.root.textContent).toContain('Details to be confirmed')
  expect(after.root.textContent).not.toContain('secret-ref')
  expect(after.root.textContent).not.toContain('internal-material-1')
  expect(after.root.textContent).not.toContain('private-requirement-ref')
  expect(after.root.textContent).not.toContain('"requirement"')
})

it('disables candidate adoption for read-only viewers even when the head guard matches',async()=>{
  const candidate={ref:candidateRef,status:'CANDIDATE',headGuard:editExpectedRef,inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Ready candidate'}]}}}
  const {root,onCommand}=mount({outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,candidates:[candidate]}]},vi.fn(),false);await flush()
  const adopt=[...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Adopt candidate')) as HTMLButtonElement
  expect(adopt.disabled).toBe(true)
  adopt.click();await flush()
  expect(onCommand).not.toHaveBeenCalled()
})

it('rejects stale candidates and adopts a current candidate with the server chapter ref',async()=>{
  expect(canAdopt('STALE',3,2)).toBe(false)
  expect(canAdopt('CANDIDATE',3,2)).toBe(false)
  expect(canAdopt('CANDIDATE',3,3)).toBe(true)
  expect(canAdopt('CANDIDATE',0,'0')).toBe(true)
  expect(canAdopt('CANDIDATE',3,'3')).toBe(true)
  const view:WritingView={outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,selected:{ref:currentRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Current draft'}]}}},candidates:[{ref:candidateRef,status:'CANDIDATE',headGuard:{kind:'chapter',id:'chapter-1',version:2,digest:'chapter2'},inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Older run'}]},missingMaterials:['A current drawing']}}]}]}
  const {root,onCommand}=mount(view);await flush()
  const adopt=(target:HTMLElement)=>[...target.querySelectorAll('button')].find(button=>button.textContent?.includes('Adopt candidate')) as HTMLButtonElement
  expect(adopt(root).disabled).toBe(true)
  expect(root.textContent).toContain('A current drawing')
  const currentCandidate={...view.chapters[0]!.candidates[0]!,headGuard:{...currentRef,version:'3'},inputRefs:[outlineRef]}
  await app?.unmount();app=undefined;host?.remove();host=undefined
  const next=mount({...view,chapters:[{...view.chapters[0]!,candidates:[currentCandidate]}]},onCommand);await flush()
  expect(adopt(next.root).disabled).toBe(false)
  adopt(next.root).click();await flush()
  expect(next.onCommand).toHaveBeenCalledWith({action:'ADOPT_CHAPTER',expected:editExpectedRef,payload:{chapterId:'chapter-1',candidateRef}})
})

it('edits one paragraph while preserving mixed block structure and the approved evidence fields',async()=>{
  const blocks=[
    {type:'heading',level:2,text:'Delivery approach',source:'outline'},
    {type:'paragraph',text:'Old paragraph',reviewed:false},
    {type:'list',ordered:true,items:['First step',{text:'Second step',source:'author'}],marker:'retain-list-meta'},
    {type:'table',columns:['Phase','Owner'],rows:[['Plan','PM'],['Build','Lead']],caption:'Responsibility table'},
    {type:'image',authorizedRef:{kind:'source',id:'img-material',version:2,digest:'image-digest'},alt:'Site plan',caption:'Existing site plan',crop:{x:1,y:2},status:'AUTHORIZED'},
  ]
  const payload={chapter:{chapterId:'chapter-1',blocks,sourceRefs:['source-a']},responses:[{requirementRef:'req-1',text:'Response'}],citations:[{sourceRef:'source-a',locator:'page:2'}],missingMaterials:['Survey'],unresolvedItems:['Confirm access route'],generationMeta:{preserve:true}}
  const view:WritingView={outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,selected:{ref:currentRef,status:'SELECTED',inputRefs:[outlineRef],payload},candidates:[],tasks:[]}]}
  const {root,onCommand}=mount(view);await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  expect(root.textContent).toContain('authorized image reference will be preserved')
  const paragraph=root.querySelector('[data-block-editor-type="paragraph"] textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(paragraph,'Revised paragraph only')
  paragraph.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Revised paragraph only'}));await flush()
  ;([...root.querySelectorAll('.el-dialog button')].find(button=>button.textContent?.includes('Save revision')) as HTMLButtonElement).click();await flush()
  const command=onCommand.mock.calls[0]?.[0]
  expect(command).toEqual({action:'EDIT_CHAPTER',expected:editExpectedRef,payload:{chapterId:'chapter-1',blocks:[blocks[0],{...blocks[1],text:'Revised paragraph only'},...blocks.slice(2)],responses:payload.responses,citations:payload.citations,missingMaterials:payload.missingMaterials,unresolvedItems:payload.unresolvedItems}})
  expect(root.querySelector('.el-dialog textarea')).not.toBeNull()
  expect((root.querySelector('.el-dialog textarea') as HTMLTextAreaElement).value).toBe('Revised paragraph only')
})

it('clears the open chapter editor and its sensitive draft when access is revoked',async()=>{
  const view:WritingView={outlineRef,chapters:[{chapterId:'chapter-1',title:'Delivery plan',editExpectedRef,selected:{ref:currentRef,status:'SELECTED',inputRefs:[outlineRef],payload:{chapter:{chapterId:'chapter-1',blocks:[{type:'paragraph',text:'Protected chapter text'}]}}},candidates:[]}]}
  const {root}=mount(view);await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Edit chapter')) as HTMLButtonElement).click();await flush()
  const textarea=root.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(textarea,'Private unsaved change')
  textarea.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Private unsaved change'}));await flush()
  const instance=(app as unknown as {_instance?:{exposed?:{clearSensitiveDraft?:()=>void}}})._instance
  instance?.exposed?.clearSensitiveDraft?.();await flush()
  expect(root.textContent).not.toContain('Private unsaved change')
  expect(root.querySelector('.el-dialog textarea')).toBeNull()
})
