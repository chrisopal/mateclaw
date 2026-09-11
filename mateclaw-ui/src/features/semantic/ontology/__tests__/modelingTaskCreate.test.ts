import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import ModelingTaskCreate from '../components/ModelingTaskCreate.vue'
import { modelingTaskApi } from '../../api/modelingTaskApi'
import { semanticRequest } from '../../api/ontologyApi'
vi.mock('vue-router', () => ({
  useRouter: () => ({
    resolve: (route: { name: string }) => ({
      href: route.name === 'Agents' ? '/agents' : '/wiki',
    }),
  }),
}))
vi.mock('../../api/ontologyApi', () => ({
  ontologyApi: {
    ensureBuilder: vi.fn().mockResolvedValue({ agentId: 'agent' }),
  },
  semanticRequest: vi.fn().mockResolvedValue({
    items: [
      {
        knowledgeBaseId: 'kb',
        sourceRef: 'doc',
        title: '设备手册',
        sourceDigest: 'digest',
        readable: true,
      },
    ],
    hasMore: false,
  }),
}))
vi.mock('../../api/modelingTaskApi', () => ({
  modelingTaskApi: {
    create: vi.fn().mockResolvedValue({ id: 'task', ontologyId: 'model' }),
  },
}))
let app: App, host: HTMLDivElement
const flush = async () => {
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})
it('starts from the selected Wiki KB and creates one durable task from checked parsed sources', async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useWorkspaceStore()
  store.currentWorkspaceId = 'workspace'
  store.accessLoaded = true
  store.currentCapabilities = new Set(['manage:ontology'])
  const props = reactive({
    modelValue: false,
    initialKnowledgeBaseId: 'kb',
    initialKnowledgeBaseName: '设备库',
  })
  const created = vi.fn()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    render: () => h(ModelingTaskCreate, { ...props, onCreated: created }),
  })
  app.use(pinia).use(ElementPlus).mount(host)
  props.modelValue = true
  await flush()
  await flush()
  expect(semanticRequest).toHaveBeenCalledWith(
    'workspace',
    expect.objectContaining({
      params: expect.objectContaining({
        knowledgeBaseId: 'kb',
        agentId: 'agent',
      }),
    }),
    expect.any(AbortSignal),
  )
  for (const [label, value] of [
    ['模型名称', '设备模型'],
    ['业务建模目标', '管理设备及其故障'],
  ]) {
    const input = document.querySelector<HTMLInputElement>(
      `[aria-label="${label}"]`,
    )!
    input.value = value!
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }
  const checkbox = document.querySelector<HTMLInputElement>(
    'input[type="checkbox"]',
  )!
  checkbox.checked = true
  checkbox.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
  expect(document.querySelector('.source-row .el-checkbox__input')?.classList.contains('is-checked')).toBe(true)
  expect(document.querySelector('.source-row')?.textContent).toContain('已选择')
  ;[...document.querySelectorAll('button')]
    .find((x) => x.textContent?.trim() === '创建任务')!
    .click()
  await flush()
  expect(modelingTaskApi.create).toHaveBeenCalledWith(
    'workspace',
    {
      operationId: expect.any(String),
      ontologyId: undefined,
      newOntology: { name: '设备模型', description: '' },
      goal: '管理设备及其故障',
      sources: [
        { knowledgeBaseId: 'kb', sourceRef: 'doc', sourceDigest: 'digest' },
      ],
    },
    expect.any(AbortSignal),
  )
  expect(created).toHaveBeenCalledWith('task', 'model')
})

it('explains source access denial in Chinese and links to employee settings', async () => {
  vi.mocked(semanticRequest).mockRejectedValueOnce({
    status: 404,
    message: 'Source or agent is unavailable or not visible',
  })
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useWorkspaceStore()
  store.currentWorkspaceId = 'workspace'
  const props = reactive({
    modelValue: false,
    initialKnowledgeBaseId: 'kb',
    initialKnowledgeBaseName: '设备库',
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(ModelingTaskCreate, props) })
  app.use(pinia).use(ElementPlus).mount(host)
  props.modelValue = true
  await flush()
  await flush()
  expect(document.body.textContent).toContain(
    '建模助手暂无此知识库的访问权限，请在员工设置中开启知识库并授予访问范围。',
  )
  expect(document.body.textContent).not.toContain(
    'Source or agent is unavailable',
  )
  const settings = [...document.querySelectorAll('a')].find((link) =>
    link.textContent?.includes('打开员工设置'),
  )!
  expect(settings.getAttribute('href')).toBe('/agents')
  expect(settings.getAttribute('target')).toBe('_blank')
  expect(modelingTaskApi.create).not.toHaveBeenCalled()
})
