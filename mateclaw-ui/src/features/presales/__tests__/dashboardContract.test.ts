import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import Dashboard from '../components/PresalesDashboard.vue'
import type { PresalesProjectSummary } from '../api/presalesApi'

let app: App | undefined
let root: HTMLDivElement | undefined
afterEach(() => {
  app?.unmount()
  root?.remove()
  app = undefined
  root = undefined
})

const summary: PresalesProjectSummary = {
  id: '9223372036854775800',
  workspaceId: '9223372036854775801',
  version: 7,
  name: 'Factory',
  customer: 'Customer',
  ownerId: 'owner',
  status: 'ACTIVE',
  stage: 'RELEASE',
  openClarificationCount: 3,
  latestSolutionVersion: 2,
}
function mount(extra: { loading?: boolean; error?: boolean } = {}) {
  root = document.createElement('div')
  document.body.append(root)
  const open = vi.fn(),
    refresh = vi.fn(),
    stage = vi.fn()
  const translations = createI18n({ legacy: false, locale: 'en-US', messages: {} })
  app = createApp(Dashboard, {
    projects: [summary],
    selectedStage: 'RELEASE',
    loading: false,
    error: false,
    ...extra,
    onOpen: open,
    onRefresh: refresh,
    onStage: stage,
  })
  app.use(translations).use(ElementPlus).mount(root)
  return { translations, open, refresh, stage }
}

describe('summary dashboard rendering and host locale', () => {
  it('renders sparse summary and switches all labels through host i18n', async () => {
    const { translations, open, refresh, stage } = mount()
    expect(root?.querySelector('section')?.getAttribute('aria-label')).toBe('Presales overview')
    for (const text of [
      'Refresh overview',
      'Active projects',
      'Open questions',
      'Projects with solutions',
      'Projects in release',
      'Project pipeline',
      'Needs attention',
      'Discovery',
      'Requirements',
      'Baseline',
      'Solution',
      'Release',
      'Factory',
      'Customer',
    ])
      expect(root?.textContent).toContain(text)
    root?.querySelector<HTMLButtonElement>('.attention-row')?.click()
    expect(open).toHaveBeenCalledWith('9223372036854775800')
    root?.querySelector<HTMLButtonElement>('.text-action')?.click()
    expect(refresh).toHaveBeenCalledOnce()
    root?.querySelector<HTMLButtonElement>('.pipeline-stage:last-child')?.click()
    expect(stage).toHaveBeenCalledWith('')
    translations.global.locale.value = 'zh-CN'
    await nextTick()
    expect(root?.querySelector('section')?.getAttribute('aria-label')).toBe('售前推进总览')
    for (const text of [
      '刷新统计',
      '进行中项目',
      '待澄清事项',
      '已有方案项目',
      '进入成果阶段',
      '项目推进',
      '优先跟进',
      '项目理解',
      '需求梳理',
      '需求基线',
      '方案设计',
      '成果准备',
    ])
      expect(root?.textContent).toContain(text)
    expect(root?.textContent).not.toContain('presales.dashboard_')
    expect(summary).not.toHaveProperty('materials')
  })
  it('keeps loading controls disabled without displaying stale summary metrics', () => {
    mount({ loading: true })
    expect(root?.textContent).toContain('Updating…')
    expect(root?.textContent).toContain('Loading projects…')
    expect(root?.querySelector<HTMLButtonElement>('.text-action')?.disabled).toBe(true)
    expect(root?.querySelectorAll('.attention-row').length).toBe(0)
    expect(root?.textContent).not.toContain('Factory')
  })
  it('retains warning text and hides metrics when the portfolio read fails', async () => {
    const { translations } = mount({ error: true })
    expect(root?.textContent).toContain(
      'Overview unavailable. Refresh to retry; the project list remains available.',
    )
    expect(root?.querySelector('.metric-strip')).toBeNull()
    translations.global.locale.value = 'zh-CN'
    await nextTick()
    expect(root?.textContent).toContain('统计暂不可用，请刷新重试。项目台账仍可使用。')
  })
})
