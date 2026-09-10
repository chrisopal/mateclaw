import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import Form from '../components/OntologyDefinitionForm.vue'
let app:App,host:HTMLDivElement
const flush=async()=>{await nextTick();await new Promise(r=>setTimeout(r,0))}
afterEach(()=>{app?.unmount();host?.remove();document.body.innerHTML=''})
function mount(){const apply=vi.fn().mockResolvedValue(false);const close=vi.fn();const props=reactive({node:{id:'class:urn:p',iri:'urn:p',label:'P',kind:'class' as const,axiomIds:[]},nodes:[],axioms:[],disabled:false,apply,onClose:close});host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Form,props)}).use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{}}));app.mount(host);return {props,apply,close}}
const button=(text:string)=>[...document.body.querySelectorAll('button')].find(b=>b.textContent?.trim()===text)!
it('previews before submission and retains input after a rejected write',async()=>{
 const {apply,close}=mount();await flush();const input=document.body.querySelector<HTMLInputElement>('input[aria-label="内容"]')!;input.value='中文';input.dispatchEvent(new Event('input',{bubbles:true}));await flush();button('预览变更').click();await flush();expect(apply).not.toHaveBeenCalled();expect(document.body.textContent).toContain('AnnotationAssertion');button('确认保存定义').click();await flush();expect(apply).toHaveBeenCalledOnce();expect(input.value).toBe('中文');expect(close).not.toHaveBeenCalled()
})
it('does not submit disabled writes',async()=>{
 const {props,apply}=mount();await flush();const input=document.body.querySelector<HTMLInputElement>('input[aria-label="内容"]')!;input.value='P';input.dispatchEvent(new Event('input',{bubbles:true}));await flush();button('预览变更').click();await flush();props.disabled=true;await flush();button('确认保存定义').click();await flush();expect(apply).not.toHaveBeenCalled()
})
