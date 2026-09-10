import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import Workbench from '../components/OntologyModelWorkbench.vue'
import { projectOntology } from '../ontologyProjection'
import type { DisplayProjection } from '../standardProjection'
import type { AxiomDescriptor } from '../../api/types'
vi.mock('../components/OntologyGraphCanvas.vue', () => ({ default: { methods: { focus() {} }, template: '<div class="test-canvas" />' } }))
let app:App, host:HTMLDivElement
const axiom=(id:string,rendering:string,signatureIris:string[]):AxiomDescriptor=>({axiomId:id,axiomType:rendering.split('(')[0]!,rendering,signatureIris,annotations:[],logical:true})
const axioms=[axiom('a','Declaration(Class(<urn:test:CMM>))',['urn:test:CMM']),axiom('b','Declaration(Class(<urn:test:Probe>))',['urn:test:Probe']),axiom('r','ObjectPropertyDomain(<urn:test:uses> <urn:test:CMM>)',['urn:test:uses','urn:test:CMM'])]
// Historical graph fixtures remain test-only; production consumes server projection.
const legacy=projectOntology(axioms)
const projection:DisplayProjection={schemaVersion:'ontology-display-v1',documentDigest:'d',importLockDigest:'i',nodes:legacy.nodes.map(n=>({...n,labels:[],features:[],imported:false})),edges:legacy.edges,axiomRefs:axioms.map(a=>({...a,id:a.axiomId,artifactId:'draft',imported:false,status:'FULL',reason:''})),coverage:{total:3,returned:3,truncated:false,dependencyScope:'ROOT',lockedImportCount:0}}
const expressionProjection:DisplayProjection={...projection,axiomRefs:[{...projection.axiomRefs[0]!,id:'qualified:a',axiomId:'a'},{...projection.axiomRefs[1]!,id:'qualified:b',axiomId:'b'}],expressions:[
 {id:'root-a',axiomId:'qualified:a',path:'root',operator:'ObjectAllValuesFrom',operands:[{role:'property',position:0,targetId:'class:urn:test:uses',value:null},{role:'filler',position:1,targetId:'nested-a',value:null}]},
 {id:'nested-a',axiomId:'qualified:a',path:'root/1',operator:'ObjectSomeValuesFrom',operands:[{role:'filler',position:0,targetId:'class:urn:test:Probe',value:null}]},
 {id:'root-b',axiomId:'qualified:b',path:'root',operator:'ObjectPropertyChain',operands:[{role:'chain',position:1,targetId:'class:urn:test:Probe',value:null},{role:'chain',position:0,targetId:'class:urn:test:CMM',value:null}]},
]}
const flush=async()=>{await nextTick();await new Promise(r=>setTimeout(r,0))}
afterEach(()=>{app?.unmount();host?.remove();document.body.innerHTML=''})
function mount(editable=true,data:DisplayProjection|null=projection,inspect=vi.fn()){const apply=vi.fn().mockResolvedValue(true);const props=reactive({axioms,projection:data,projectionError:'',editable,disabled:false,apply,onInspectAxiom:inspect});host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Workbench,props)});app.use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{}})).mount(host);return {apply,props,inspect}}
import { h } from 'vue'
it('selects node details and exposes explicit graph semantics',async()=>{mount();await flush();host.querySelector<HTMLButtonElement>('.model-term')!.click();await flush();expect(host.querySelector('.model-inspector')?.textContent).toContain('CMM');expect(host.querySelectorAll('.model-edge')).toHaveLength(1);expect(host.textContent).toContain('业务模型')})
it('saves through the apply boundary and blocks writes while disabled',async()=>{const {apply,props}=mount();await flush();[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('＋ 业务对象'))!.click();await flush();const form=document.body.querySelector('.el-dialog form')!;const inputs=form.querySelectorAll('input');inputs[0]!.value='测针';inputs[0]!.dispatchEvent(new Event('input',{bubbles:true}));inputs[1]!.value='urn:test:NewProbe';inputs[1]!.dispatchEvent(new Event('input',{bubbles:true}));await flush();props.disabled=true;await flush();form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush();expect(apply).not.toHaveBeenCalled();props.disabled=false;await flush();form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush();expect(apply).toHaveBeenCalledTimes(1);expect(apply.mock.calls[0]![0][0].functionalSyntax).toBe('Declaration(Class(<urn:test:NewProbe>))')})
it('does not expose add actions for a published readonly document',async()=>{mount(false);await flush();expect(host.querySelector('.model-actions')).toBeNull()})

it('keeps the canvas neighborhood during search and only hides nodes on explicit filtering', async () => {
  mount(); await flush()
  const input = host.querySelector<HTMLInputElement>('input[id^=ontology-term-search]')!
  input.value = 'Probe'; input.dispatchEvent(new Event('input', { bubbles: true })); await flush()
  expect(host.textContent).toContain('仅看匹配项')
  expect(host.querySelectorAll('.model-edge')).toHaveLength(1)
  const checkbox = [...host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')].find(el => el.closest('label')?.textContent?.includes('仅看匹配项'))!
  checkbox.click(); await flush()
  expect(host.querySelectorAll('.model-edge')).toHaveLength(0)
  expect(host.textContent).toContain('已隐藏')
})
it('offers source navigation for an explicitly selected axiom without saving a draft', async () => {
  const { apply } = mount(); await flush()
  host.querySelector<HTMLButtonElement>('.model-term')!.click(); await flush()
  const source = [...host.querySelectorAll('button')].find(b => b.textContent?.includes('查看参考资料'))
  expect(source).toBeDefined()
  source!.click(); await flush()
  expect(apply).not.toHaveBeenCalled()
})

it('returns focus to the inspector entry when details close', async () => {
  mount(); await flush()
  const open = host.querySelector<HTMLButtonElement>('.model-canvas-panel > .model-mobile-details')!
  const close = host.querySelector<HTMLButtonElement>('.model-inspector > .model-mobile-details')!
  open.click(); await flush(); close.focus(); close.click(); await flush()
  expect(host.querySelector('.model-inspector')?.classList.contains('is-open')).toBe(false)
  expect(document.activeElement).toBe(open)
})

it('shows an explicit projection failure and original axioms instead of inventing a graph',async()=>{const {props}=mount();props.projection=null;props.projectionError='offline';await flush();expect(host.querySelectorAll('.model-term')).toHaveLength(0);expect(host.textContent).toContain('模型暂时无法显示');expect(host.textContent).toContain('更多操作')})
it('retains an open definition form while the standard projection refreshes',async()=>{
 const {props}=mount();await flush();host.querySelector<HTMLButtonElement>('.model-term')!.click();await flush()
 ;[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('编辑信息'))!.click();await flush()
 const input=document.body.querySelector<HTMLInputElement>('input[aria-label="内容"]')!;input.value='保留输入';input.dispatchEvent(new Event('input',{bubbles:true}));await flush()
 props.projection=null;await flush();expect(document.body.querySelector<HTMLInputElement>('input[aria-label="内容"]')?.value).toBe('保留输入')
 props.projection=projection;await flush();expect(document.body.querySelector<HTMLInputElement>('input[aria-label="内容"]')?.value).toBe('保留输入')
})
it('renders every expression root recursively and links its source without edit controls',async()=>{
 const {apply,inspect}=mount(false,expressionProjection);await flush()
 expect(host.querySelectorAll('.model-expression-root')).toHaveLength(2)
 expect(host.querySelectorAll('.ontology-expression-node')).toHaveLength(3)
 expect(host.textContent).toContain('全称限制')
 expect(host.textContent).toContain('属性链')
 host.querySelector<HTMLButtonElement>('.model-expression-source')!.click();await flush()
 expect(inspect).toHaveBeenCalledWith('a')
 expect(host.querySelector('.model-actions')).toBeNull()
 expect(apply).not.toHaveBeenCalled()
})

it('keeps restriction inputs through projection refresh and exposes rules only for editable classes',async()=>{
 const {props}=mount();await flush();host.querySelector<HTMLButtonElement>('.model-term')!.click();await flush()
 const entry=[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('编辑业务规则'))!;expect(entry).toBeDefined();entry.click();await flush()
 const field=document.body.querySelector<HTMLInputElement>('input[aria-label="关系 IRI"]')!;field.value='urn:test:preserved';field.dispatchEvent(new Event('input',{bubbles:true}));await flush()
 props.projection=null;await flush();expect(document.body.querySelector<HTMLInputElement>('input[aria-label="关系 IRI"]')?.value).toBe('urn:test:preserved');expect(document.body.querySelector<HTMLInputElement>('input[aria-label="关系 IRI"]')?.disabled).toBe(true)
 props.projection=projection;await flush();expect(document.body.querySelector<HTMLInputElement>('input[aria-label="关系 IRI"]')?.value).toBe('urn:test:preserved')
 props.editable=false;await flush();expect(document.body.querySelector('input[aria-label="关系 IRI"]')).toBeNull()
 expect([...host.querySelectorAll('button')].some(b=>b.textContent?.includes('编辑业务规则'))).toBe(false)
})
