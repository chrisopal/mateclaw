import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import Workbench from '../components/OntologyModelWorkbench.vue'
import type { AxiomDescriptor } from '../../api/types'
let app:App, host:HTMLDivElement
const axiom=(id:string,rendering:string,signatureIris:string[]):AxiomDescriptor=>({axiomId:id,axiomType:rendering.split('(')[0]!,rendering,signatureIris,annotations:[],logical:true})
const axioms=[axiom('a','Declaration(Class(<urn:test:CMM>))',['urn:test:CMM']),axiom('b','Declaration(Class(<urn:test:Probe>))',['urn:test:Probe']),axiom('r','ObjectPropertyDomain(<urn:test:uses> <urn:test:CMM>)',['urn:test:uses','urn:test:CMM'])]
const flush=async()=>{await nextTick();await new Promise(r=>setTimeout(r,0))}
afterEach(()=>{app?.unmount();host?.remove();document.body.innerHTML=''})
function mount(editable=true){const apply=vi.fn().mockResolvedValue(true);const props=reactive({axioms,editable,disabled:false,apply});host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Workbench,props)});app.use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{}})).mount(host);return {apply,props}}
import { h } from 'vue'
it('selects node details and exposes explicit graph semantics',async()=>{mount();await flush();host.querySelector<HTMLButtonElement>('.model-term')!.click();await flush();expect(host.querySelector('.model-inspector')?.textContent).toContain('CMM');expect(host.querySelectorAll('.model-edge')).toHaveLength(1);expect(host.textContent).toContain('定义域 / 值域不表示必填要求')})
it('saves through the apply boundary and blocks writes while disabled',async()=>{const {apply,props}=mount();await flush();[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('＋ 概念'))!.click();await flush();const form=document.body.querySelector('.el-dialog form')!;const inputs=form.querySelectorAll('input');inputs[0]!.value='测针';inputs[0]!.dispatchEvent(new Event('input',{bubbles:true}));inputs[1]!.value='urn:test:NewProbe';inputs[1]!.dispatchEvent(new Event('input',{bubbles:true}));await flush();props.disabled=true;await flush();form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush();expect(apply).not.toHaveBeenCalled();props.disabled=false;await flush();form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush();expect(apply).toHaveBeenCalledTimes(1);expect(apply.mock.calls[0]![0][0].functionalSyntax).toBe('Declaration(Class(<urn:test:NewProbe>))')})
it('does not expose add actions for a published readonly document',async()=>{mount(false);await flush();expect(host.querySelector('.model-actions')).toBeNull()})
