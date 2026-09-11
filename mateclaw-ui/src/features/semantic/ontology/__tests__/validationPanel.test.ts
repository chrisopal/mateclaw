import { it, expect } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import ValidationPanel from '../components/ValidationPanel.vue'

it('translates known validation codes and retains unknown server messages', async () => {
  const host = document.createElement('div')
  document.body.append(host)
  let located = ''
  const app = createApp(ValidationPanel, {
    dirty: false,
    report: {
      draftVersion: 1,
      valid: false,
      violations: [
        { code: 'EMPTY_TYPES', path: 'types', message: 'at least one entity type is required' },
        { code: 'FUTURE_CODE', path: '$', message: 'Future server detail' },
      ],
    },
    onLocate: (path: string) => {
      located = path
    },
  })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } }))
  app.mount(host)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
  try {
    expect(host.textContent).not.toContain('at least one entity type is required')
    expect(host.textContent).toContain('Future server detail')
    host.querySelector('button')!.click()
    expect(located).toBe('types')
  } finally {
    app.unmount()
    host.remove()
  }
})

it('does not label a legacy structural success as publication ready', async () => {
 const host=document.createElement('div');document.body.append(host)
 const app=createApp(ValidationPanel,{dirty:false,report:{draftVersion:1,valid:true,violations:[]}})
 app.use(ElementPlus).use(createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':zh}})).mount(host)
 await nextTick()
 try {expect(host.textContent).not.toContain('检查通过，可以发布');expect(host.textContent).toContain('模型结构');expect(host.textContent).toContain('模型逻辑');expect(host.textContent).toContain('参考资料');expect(host.textContent).toContain('未完成')}
 finally {app.unmount();host.remove()}
})
