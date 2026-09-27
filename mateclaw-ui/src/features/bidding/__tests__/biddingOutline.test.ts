import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingOutline from '../components/BiddingOutline.vue'

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})
const projectRef={kind:'project',id:'bid1',version:5,digest:'project5'}
const baselineRef={kind:'analysis-baseline',id:'baseline1',version:2,digest:'baseline2'}
const outlineRef={kind:'outline',id:'outline1',version:3,digest:'outline3'}
const editExpectedRef={kind:'outline',id:'outline1',version:4,digest:'outline4'}
function mount(view:Record<string,unknown>,onCommand=vi.fn()){host=document.createElement('div');document.body.append(host);app=createApp(BiddingOutline,{view,projectRef,canWrite:true,canApprove:true,canDispatch:true,onCommand});app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host);return {root:host,onCommand}}
function mountReactive(view:Record<string,unknown>,onCommand=vi.fn()){
  const props=reactive({view,projectRef,canWrite:true,canApprove:true,canDispatch:true,savedRef:undefined as typeof outlineRef|undefined,clearToken:0})
  let surface:{isDirty:()=>boolean;clearSensitiveDraft:()=>void}|undefined
  host=document.createElement('div');document.body.append(host)
  app=createApp(defineComponent({setup(){return()=>h(BiddingOutline as never,{...props,onCommand,ref:(value:unknown)=>{surface=value as typeof surface}})}}))
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en',messages:{en:{}}})).mount(host)
  return {root:host,onCommand,props,get surface(){return surface}}
}

it('blocks outline generation and confirmation until a confirmed analysis baseline exists',async()=>{
  const {root}=mount({candidates:[]});await flush()
  const buttons=[...root.querySelectorAll('button')]
  expect((buttons.find(button=>button.textContent?.includes('Generate outline')) as HTMLButtonElement).disabled).toBe(true)
  expect((buttons.find(button=>button.textContent?.includes('Confirm outline')) as HTMLButtonElement).disabled).toBe(true)
  expect(root.textContent).toContain('Confirm the analysis baseline first.')
})

it('starts clean when a project has no outline candidates so switching tabs does not prompt to discard edits',async()=>{
  const {surface}=mountReactive({candidates:[]});await flush()
  expect(surface?.isDirty()).toBe(false)
})

it('keeps saving a candidate separate from confirming it and uses server refs for both commands',async()=>{
  const {root,onCommand}=mount({baselineRef,editExpectedRef,candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[{id:'ch1',parentId:null,order:1,title:'Implementation plan',instructions:'Describe delivery.',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]}],unmappedItems:[{text:'Safety plan'}],warnings:[]}}]});await flush()
  const buttons=()=>[...root.querySelectorAll('button')]
  ;(buttons().find(button=>button.textContent?.includes('Confirm outline')) as HTMLButtonElement).click();await flush()
  expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({action:'CONFIRM_OUTLINE',expected:editExpectedRef,payload:{outlineRef}}))
  const title=root.querySelector('.outline-detail input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(title,'Revised implementation plan');title.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Revised'}));await flush()
  expect((buttons().find(button=>button.textContent?.includes('Confirm outline')) as HTMLButtonElement).disabled).toBe(true)
  ;(buttons().find(button=>button.textContent?.includes('Save outline')) as HTMLButtonElement).click();await flush()
  expect(onCommand).toHaveBeenLastCalledWith(expect.objectContaining({action:'SAVE_OUTLINE',expected:editExpectedRef,payload:{payload:{schemaVersion:'1',chapters:[expect.objectContaining({id:'ch1',title:'Revised implementation plan',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]})],unmappedItems:[{text:'Safety plan'}],warnings:[]}}}))
  expect(root.textContent).toContain('Safety plan')
})

it('dispatches against the confirmed baseline and removes a full subtree without losing sibling refs',async()=>{
  const chapters=[
    {id:'root-a',parentId:null,order:1,title:'A',instructions:'',mandatoryOutlineRefs:['m1'],requirementRefs:['r1'],scoringRefs:[],materialRefs:[]},
    {id:'child-a',parentId:'root-a',order:1,title:'A.1',instructions:'',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]},
    {id:'grandchild-a',parentId:'child-a',order:1,title:'A.1.1',instructions:'',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]},
    {id:'root-b',parentId:null,order:2,title:'B',instructions:'',mandatoryOutlineRefs:['m2'],requirementRefs:['r2'],scoringRefs:['s2'],materialRefs:[]},
  ]
  const {root,onCommand}=mount({baselineRef,editExpectedRef,candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters,unmappedItems:[],warnings:[]}}]});await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Remove chapter')) as HTMLButtonElement).click();await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Save outline')) as HTMLButtonElement).click();await flush()
  expect(onCommand).toHaveBeenLastCalledWith(expect.objectContaining({action:'SAVE_OUTLINE',expected:editExpectedRef,payload:{payload:{schemaVersion:'1',chapters:[expect.objectContaining({id:'root-b',order:2,mandatoryOutlineRefs:['m2'],requirementRefs:['r2'],scoringRefs:['s2'],materialRefs:[]})],unmappedItems:[],warnings:[]}}}))
})

it('dispatches against the confirmed baseline ref',async()=>{
  const {root,onCommand}=mount({baselineRef,editExpectedRef,candidates:[]});await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Generate outline')) as HTMLButtonElement).click();await flush()
  expect(onCommand).toHaveBeenCalledWith({action:'DISPATCH_OUTLINE',expected:baselineRef,payload:{}})
})

it('shows a short actionable todo without exposing its internal identifiers',async()=>{
  const {root}=mount({baselineRef,editExpectedRef,candidates:[],dispatchTodo:{status:'TODO',action:'DISPATCH_OUTLINE',reasonCode:'OUTLINE_CONFIGURATION_REQUIRED',baselineRef}});await flush()
  expect(root.textContent).toContain('Outline is ready to generate. Check the writer setup, then generate it.')
  expect(root.textContent).not.toContain('OUTLINE_CONFIGURATION_REQUIRED')
  expect(root.textContent).not.toContain('baseline1')
})

it('acknowledges only the saved server ref after refreshed candidates arrive and uses the refreshed head to confirm',async()=>{
  const oldChapter={id:'ch1',parentId:null,order:1,title:'Draft title',instructions:'Draft instructions',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]}
  const initialView={baselineRef,editExpectedRef,candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[oldChapter],unmappedItems:[],warnings:[]}}]}
  const {root,onCommand,props,surface}=mountReactive(initialView);await flush()
  const title=root.querySelector('.outline-detail input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(title,'Saved server title');title.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Saved server title'}));await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Save outline')) as HTMLButtonElement).click();await flush()
  const savedRef={kind:'outline',id:'outline1',version:5,digest:'outline5'}
  const savedHead={kind:'outline',id:'outline1',version:6,digest:'outline6'}
  const savedRevision={ref:savedRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[{...oldChapter,title:'Saved server title'}],unmappedItems:[],warnings:[]}}
  props.savedRef=savedRef
  props.view={baselineRef,editExpectedRef:savedHead,candidates:[initialView.candidates[0],savedRevision]}
  await flush()
  expect(surface?.isDirty()).toBe(false)
  expect(root.textContent).toContain('Outline candidate V5')
  const confirm=[...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Confirm outline')) as HTMLButtonElement
  expect(confirm.disabled).toBe(false)
  confirm.click();await flush()
  expect(onCommand).toHaveBeenLastCalledWith(expect.objectContaining({action:'CONFIRM_OUTLINE',expected:savedHead,payload:{outlineRef:savedRef}}))
})

it('keeps an unsaved outline draft after a failed save and later conflict refresh',async()=>{
  const chapter={id:'ch1',parentId:null,order:1,title:'Original title',instructions:'',mandatoryOutlineRefs:[],requirementRefs:[],scoringRefs:[],materialRefs:[]}
  const {root,props,surface}=mountReactive({baselineRef,editExpectedRef,candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[chapter],unmappedItems:[],warnings:[]}}]});await flush()
  const title=root.querySelector('.outline-detail input') as HTMLInputElement
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(title,'Keep this draft');title.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'Keep this draft'}));await flush()
  ;([...root.querySelectorAll('button')].find(button=>button.textContent?.includes('Save outline')) as HTMLButtonElement).click();await flush()
  props.view={baselineRef,editExpectedRef:{...editExpectedRef,version:5,digest:'head5'},candidates:[{ref:outlineRef,status:'CANDIDATE',inputRefs:[baselineRef],payload:{schemaVersion:'1',chapters:[chapter],unmappedItems:[],warnings:[]}}]}
  await flush()
  expect(surface?.isDirty()).toBe(true)
  expect((root.querySelector('.outline-detail input') as HTMLInputElement).value).toBe('Keep this draft')
  props.clearToken++
  await flush()
  expect(surface?.isDirty()).toBe(false)
  expect(root.textContent).not.toContain('Keep this draft')
})
