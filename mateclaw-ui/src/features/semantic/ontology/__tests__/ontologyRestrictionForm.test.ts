import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import type { AxiomDescriptor } from '../../api/types'
import type { DisplayExpression, DisplayProjection } from '../standardProjection'
import Form from '../components/OntologyRestrictionForm.vue'

let app: App | undefined
let host: HTMLDivElement | undefined

const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
}

const sourceAxiom = (id = 'local-rule', rendering = 'SubClassOf(<urn:A> ObjectSomeValuesFrom(<urn:p> <urn:B>))'): AxiomDescriptor => ({
  axiomId: id,
  axiomType: 'SubClassOf',
  rendering,
  signatureIris: ['urn:A', 'urn:p', 'urn:B'],
  annotations: [],
  logical: true,
})

function makeProjection(operator: string = 'ObjectSomeValuesFrom'): DisplayProjection {
  const rootId = 'draft:rule'
  const nestedId = rootId + ':axiom/superClass'
  const cardinality = operator.endsWith('Cardinality')
  const nested: DisplayExpression = {
    id: nestedId,
    axiomId: rootId,
    path: 'axiom/superClass',
    operator,
    operands: [
      { role: 'property', position: 0, targetId: 'objectProperty:urn:p' },
      ...(cardinality ? [{ role: 'cardinality', position: 1, value: '0' }] : []),
      { role: 'filler', position: cardinality ? 2 : 1, targetId: 'class:urn:B' },
    ],
  }
  return {
    schemaVersion: 'ontology-display-v1',
    documentDigest: 'digest-a',
    importLockDigest: 'imports-a',
    nodes: [
      { id: 'class:urn:A', iri: 'urn:A', kind: 'class', labels: [], axiomIds: ['local-rule'], features: [], imported: false },
      { id: 'class:urn:B', iri: 'urn:B', kind: 'class', labels: [], axiomIds: [], features: [], imported: false },
      { id: 'objectProperty:urn:p', iri: 'urn:p', kind: 'objectProperty', labels: [], axiomIds: [], features: [], imported: false },
    ],
    edges: [],
    expressions: [
      {
        id: rootId,
        axiomId: rootId,
        path: 'axiom',
        operator: 'SubClassOf',
        operands: [
          { role: 'subClass', position: 0, targetId: 'class:urn:A' },
          { role: 'superClass', position: 1, targetId: nestedId },
        ],
      },
      nested,
    ],
    axiomRefs: [{
      id: rootId,
      artifactId: 'draft',
      axiomId: 'local-rule',
      imported: false,
      rendering: 'opaque',
      axiomType: 'SubClassOf',
      status: 'FULL',
      reason: '',
    }],
    coverage: { total: 1, returned: 1, truncated: false, dependencyScope: 'ROOT', lockedImportCount: 0 },
  }
}

function mountForm(overrides: Record<string, unknown> = {}) {
  const apply = vi.fn().mockResolvedValue(false)
  const close = vi.fn()
  const props = reactive({
    node: { id: 'class:urn:A', iri: 'urn:A', label: 'A', kind: 'class' as const, axiomIds: ['local-rule'] },
    nodes: [
      { id: 'class:urn:A', iri: 'urn:A', label: 'A', kind: 'class' as const, axiomIds: ['local-rule'] },
      { id: 'class:urn:B', iri: 'urn:B', label: 'B', kind: 'class' as const, axiomIds: [] },
      { id: 'objectProperty:urn:p', iri: 'urn:p', label: 'p', kind: 'objectProperty' as const, axiomIds: [] },
    ],
    axioms: [sourceAxiom()],
    projection: makeProjection(),
    disabled: false,
    pending: false,
    retry: undefined as (() => Promise<boolean>) | undefined,
    reload: undefined as (() => Promise<boolean>) | undefined,
    failureMessage: '',
    apply,
    ...overrides,
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(Form, { ...props, onClose: close }) })
    .use(ElementPlus)
    .use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} }))
  app.mount(host)
  return { props, apply, close }
}

const button = (text: string) => [...document.body.querySelectorAll('button')].find(item => item.textContent?.trim() === text)!
const select = (label: string) => document.body.querySelector<HTMLSelectElement>('select[aria-label="' + label + '"]')!
const input = (label: string) => document.body.querySelector<HTMLInputElement>('input[aria-label="' + label + '"]')!
const choose = async (element: HTMLSelectElement, value: string) => {
  element.value = value
  element.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  app = undefined
  host = undefined
  document.body.innerHTML = ''
})

describe('OntologyRestrictionForm', () => {
  it('recognizes an existing rule and previews a REMOVE/ADD operator change', async () => {
    const { apply } = mountForm()
    await flush()
    await choose(select('编辑规则'), 'local-rule')
    await choose(select('限制类型'), 'ObjectAllValuesFrom')
    button('预览规则变更').click()
    await flush()
    expect(apply).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('REMOVE')
    expect(document.body.textContent).toContain('ObjectAllValuesFrom')
    expect(document.body.textContent).toContain('SubClassOf(<urn:A> ObjectAllValuesFrom(<urn:p> <urn:B>))')
  })

  it('rejects a stale axiom or projection while retaining the edited inputs', async () => {
    const { props, apply } = mountForm()
    await flush()
    await choose(select('编辑规则'), 'local-rule')
    await choose(select('限制类型'), 'ObjectAllValuesFrom')
    const property = input('关系 IRI')
    property.value = 'urn:p2'
    property.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('预览规则变更').click()
    await flush()
    props.axioms = [...props.axioms, sourceAxiom('other-rule')]
    await flush()
    button('确认保存规则').click()
    await flush()
    expect(apply).not.toHaveBeenCalled()
    expect(property.value).toBe('urn:p2')
    expect(document.body.textContent).toContain('本体或投影已更新')
  })

  it('allows cardinality zero and clears it when changing back to a value restriction', async () => {
    const { apply } = mountForm({ axioms: [], projection: makeProjection(), })
    await flush()
    await choose(select('限制类型'), 'ObjectMinCardinality')
    const property = input('关系 IRI')
    const filler = input('限定概念 IRI')
    property.value = 'urn:p'
    filler.value = 'urn:B'
    property.dispatchEvent(new Event('input', { bubbles: true }))
    filler.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    const cardinality = input('基数')
    cardinality.value = '0'
    cardinality.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    await choose(select('限制类型'), 'ObjectSomeValuesFrom')
    expect(document.body.querySelector('input[aria-label="基数"]')).toBeNull()
    await choose(select('限制类型'), 'ObjectMinCardinality')
    expect(input('基数').value).toBe('')
    await choose(select('限制类型'), 'ObjectSomeValuesFrom')
    await choose(select('限制类型'), 'ObjectMinCardinality')
    input('基数').value = '0'
    input('基数').dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('预览规则变更').click()
    await flush()
    expect(apply).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('ObjectMinCardinality(0 <urn:p> <urn:B>)')
  })

  it('preserves manually typed IRI schemes and shows a chooser hint for new IRIs', async () => {
    mountForm(); await flush()
    input('关系 IRI').value = 'objectProperty:custom'
    input('关系 IRI').dispatchEvent(new Event('input', { bubbles: true }))
    input('限定概念 IRI').value = 'class:custom'
    input('限定概念 IRI').dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(input('关系 IRI').value).toBe('objectProperty:custom')
    expect(input('限定概念 IRI').value).toBe('class:custom')
    expect(select('选择已有关系').value).toBe('')
    expect(select('选择已有概念').value).toBe('')
    button('预览规则变更').click(); await flush()
    expect(document.body.textContent).toContain('ObjectSomeValuesFrom(<objectProperty:custom> <class:custom>)')
  })

  it('keeps the exact pending request available for recovery after a lost response', async () => {
    const retry = vi.fn().mockImplementation(async () => {
      // The parent composable clears pending when this exact request is retried.
      return true
    })
    const { props, apply, close } = mountForm({ retry })
    await flush()
    await choose(select('编辑规则'), 'local-rule')
    await choose(select('限制类型'), 'ObjectAllValuesFrom')
    button('预览规则变更').click()
    await flush()
    apply.mockImplementation(async () => {
      props.pending = true
      return false
    })
    button('确认保存规则').click()
    await flush()
    expect(apply).toHaveBeenCalledOnce()
    expect(document.body.textContent).toContain('保存结果尚未确定')
    expect(input('关系 IRI').value).toBe('urn:p')
    button('恢复本次保存').click()
    await flush()
    expect(retry).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
  })
})
