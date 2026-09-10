import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import BusinessModelForm from '../components/BusinessModelForm.vue'

let app: App, host: HTMLDivElement
const flush = async () => { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)) }
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })
function mount(termKind: 'OBJECT' | 'RELATION' | 'ATTRIBUTE' = 'OBJECT') {
  const apply = vi.fn().mockResolvedValue(true)
  const props = reactive({ mode: 'create' as const, termKind, nodes: [
    { id: 'class:urn:equipment', iri: 'urn:equipment', label: '设备', kind: 'class' as const, axiomIds: [] },
    { id: 'class:urn:sensor', iri: 'urn:sensor', label: '传感器', kind: 'class' as const, axiomIds: [] },
  ], axioms: [], disabled: false, apply })
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(BusinessModelForm, props) })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} })).mount(host)
  return { props, apply }
}
async function input(label: string, value: string) {
  const element = document.querySelector<HTMLInputElement | HTMLSelectElement>(`[aria-label="${label}"]`)!
  element.value = value
  element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  await flush()
}
async function click(text: string) {
  const button = [...document.querySelectorAll('button')].find(button => button.textContent?.trim() === text)!
  button.click(); await flush()
}
it('creates a business object with only a name and delegates identifier and OWL generation', async () => {
  const { apply } = mount(); await flush()
  expect(document.querySelectorAll('.business-form input')).toHaveLength(1)
  await input('名称', '设备'); await click('预览变更'); await click('确认保存')
  expect(apply).toHaveBeenCalledWith([{ kind: 'CREATE_TERM', termKind: 'OBJECT', name: '设备' }])
})
it('uses named object choices and sends a structured relation command', async () => {
  const { apply } = mount('RELATION'); await flush()
  await input('名称', '安装传感器'); await input('所属对象', 'urn:equipment'); await input('关联对象', 'urn:sensor')
  await click('预览变更'); await click('确认保存')
  expect(apply).toHaveBeenCalledWith([{ kind: 'CREATE_TERM', termKind: 'RELATION', name: '安装传感器', domainId: 'urn:equipment', rangeId: 'urn:sensor' }])
})
it('does not apply a preview when the model changes or writes become disabled', async () => {
  const { props, apply } = mount(); await flush()
  await input('名称', '设备'); await click('预览变更')
  props.disabled = true; await flush(); await click('确认保存')
  expect(apply).not.toHaveBeenCalled()
  props.disabled = false; await flush()
  props.axioms = [{}] as never; await flush(); await click('确认保存')
  expect(apply).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('模型已更新，请重新预览')
})
