import { expect, expectTypeOf, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import {
  classifyStatus,
  statusLabel,
  type KnownStatus,
  type UnknownStatus,
  type PresalesStatus,
} from '../shared/status'

// Captured from the pre-refactor workbench, independent of the new translation table.
const previousLabels = [
  ['DISCOVERY', '项目理解', 'Discovery'],
  ['REQUIREMENTS', '需求梳理', 'Requirements'],
  ['BASELINED', '需求已基线', 'Baselined'],
  ['SOLUTION', '方案设计', 'Solution'],
  ['RELEASE', '成果发布', 'Release'],
  ['ARCHIVED', '已归档', 'Archived'],
  ['ACTIVE', '进行中', 'Active'],
  ['FULL', '完整响应', 'Full'],
  ['PARTIAL', '部分响应', 'Partial'],
  ['CONDITIONAL', '条件响应', 'Conditional'],
  ['EXCLUDED', '排除范围', 'Excluded'],
  ['UNHANDLED', '未处理', 'Unhandled'],
  ['HIGH', '高', 'High'],
  ['MEDIUM', '中', 'Medium'],
  ['LOW', '低', 'Low'],
  ['IN', '范围内', 'In scope'],
  ['OUT', '范围外', 'Out of scope'],
  ['UNKNOWN', '待核实', 'Unknown'],
  ['UNCONFIRMED', '未确认', 'Unconfirmed'],
  ['OPEN', '待处理', 'Open'],
  ['ANSWERED', '已答复', 'Answered'],
  ['RESOLVED', '已解决', 'Resolved'],
  ['ACCEPTED', '已接受', 'Accepted'],
  ['PENDING', '待批准', 'Awaiting approval'],
  ['APPROVED', '已批准', 'Approved'],
  ['PUBLISHED', '已发布', 'Published'],
  ['DRAFT', '草稿', 'Draft'],
  ['SUCCEEDED', '已完成', 'Succeeded'],
  ['RUNNING', '运行中', 'Running'],
  ['FAILED', '失败', 'Failed'],
  ['CANCELLED', '已停止接收', 'Result discarded'],
  ['PROJECT', '项目资料', 'Project material'],
  ['PRODUCT', '产品资料', 'Product material'],
  ['CASE', '案例资料', 'Case material'],
  ['CUSTOMER_SOURCE', '客户来源', 'Customer source'],
  ['PRODUCT_SOURCE', '产品来源', 'Product source'],
  ['INTERNAL_JUDGMENT', '内部判断', 'Internal judgment'],
  ['ASSUMPTION', '假设', 'Assumption'],
  ['AI_SUGGESTION', 'AI 建议', 'AI suggestion'],
  ['FIT', '直接满足', 'Fit'],
  ['CONFIG', '配置后满足', 'Configuration'],
  ['EXTEND', '需要开发', 'Extension'],
  ['PARTNER', '依赖合作方', 'Partner'],
  ['GAP', '暂不支持', 'Gap'],
  ['BLOCKER', '阻断', 'Blocker'],
  ['WARNING', '需关注', 'Warning'],
  ['INFO', '提示', 'Information'],
] as const

it.each(previousLabels)('preserves both original labels for %s', (raw, zh, en) => {
  const i18n = createI18n({
    legacy: false,
    locale: 'en-US',
    messages: presalesMessages,
  })
  const status = classifyStatus(raw)
  expect(status).toEqual({ kind: 'known', value: raw })
  expect(statusLabel(status, i18n.global.t)).toBe(en)
  i18n.global.locale.value = 'zh-CN'
  expect(statusLabel(status, i18n.global.t)).toBe(zh)
})

it.each([
  'LEGACY_PAUSED',
  'active',
  ' ACTIVE ',
  ' ',
  '__proto__',
  'constructor',
  'toString',
  '<b>{raw}</b>@:presales.states.ACTIVE',
])('preserves unknown state %s without normalization or message evaluation', (raw) => {
  const i18n = createI18n({
    legacy: false,
    locale: 'en-US',
    messages: presalesMessages,
  })
  const status = classifyStatus(raw)
  expect(status).toEqual({ kind: 'unknown', raw })
  expect(statusLabel(status, i18n.global.t)).toBe(`Unknown status: ${raw}`)
  i18n.global.locale.value = 'zh-CN'
  expect(statusLabel(status, i18n.global.t)).toBe(`未知状态：${raw}`)
})

it.each([undefined, ''])('keeps absent state %s separate from historical unknown text', (raw) => {
  const status = classifyStatus(raw)
  expect(status).toEqual({ kind: 'missing' })
  const i18n = createI18n({
    legacy: false,
    locale: 'en-US',
    messages: presalesMessages,
  })
  expect(statusLabel(status, i18n.global.t)).toBe('—')
})

it('keeps the domain UNKNOWN label known and provides a discriminated type instead of Known | string', () => {
  expect(classifyStatus('UNKNOWN')).toEqual({
    kind: 'known',
    value: 'UNKNOWN',
  })
  expectTypeOf<Extract<PresalesStatus, string>>().toEqualTypeOf<never>()
  expectTypeOf<Extract<PresalesStatus, { kind: 'unknown' }>>().toEqualTypeOf<UnknownStatus>()
  expectTypeOf<string>().not.toExtend<KnownStatus['value']>()
  expectTypeOf<'ACTIVE'>().toExtend<KnownStatus['value']>()
  const status = classifyStatus('LEGACY')
  if (status.kind === 'unknown') expectTypeOf(status.raw).toEqualTypeOf<string>()
})

it('has matching finite display vocabulary for both languages', () => {
  expect(Object.keys(presalesMessages['en-US'].presales.states).sort()).toEqual(
    previousLabels.map(([key]) => key).sort(),
  )
  expect(Object.keys(presalesMessages['zh-CN'].presales.states).sort()).toEqual(
    previousLabels.map(([key]) => key).sort(),
  )
})

it('classifies equal wire text by object contract, not by translated vocabulary', async () => {
  const { classifyDomainStatus, isDomainStatus } = await import('../shared/status')
  const running = classifyDomainStatus('RUNNING', 'task')
  const foreign = classifyDomainStatus('RUNNING', 'clarification')
  expect(running).toEqual({ kind: 'known', value: 'RUNNING' })
  expect(foreign).toEqual({ kind: 'unknown', raw: 'RUNNING' })
  expect(classifyDomainStatus('ANSWERED', 'task')).toEqual({ kind: 'unknown', raw: 'ANSWERED' })
  expect(isDomainStatus('clarification', 'RUNNING', 'OPEN')).toBe(false)
  if (foreign.kind === 'known') expectTypeOf(foreign.value).toEqualTypeOf<'OPEN' | 'ANSWERED'>()
  if (running.kind === 'known') {
    expectTypeOf(running.value).toEqualTypeOf<
      'DRAFT' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
    >()
  }
})

it.each([
  ['clarification', ['OPEN', 'ANSWERED']],
  ['task', ['DRAFT', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED']],
  ['release', ['PENDING', 'APPROVED', 'PUBLISHED']],
  ['fitGap', ['FIT', 'CONFIG', 'EXTEND', 'PARTNER', 'GAP', 'UNKNOWN']],
  ['reviewIssue', ['OPEN', 'RESOLVED', 'ACCEPTED']],
  ['reviewSeverity', ['BLOCKER', 'WARNING', 'INFO']],
  ['response', ['FULL', 'PARTIAL', 'CONDITIONAL', 'EXCLUDED', 'UNHANDLED']],
] as const)(
  'limits %s to its own contract and preserves unknown raw text',
  async (domain, allowed) => {
    const { classifyDomainStatus } = await import('../shared/status')
    const i18n = createI18n({ legacy: false, locale: 'en-US', messages: presalesMessages })
    for (const [raw, zh, en] of previousLabels) {
      const state = classifyDomainStatus(raw, domain)
      const known = allowed.some((value) => value === raw)
      expect(state).toEqual(known ? { kind: 'known', value: raw } : { kind: 'unknown', raw })
      i18n.global.locale.value = 'en-US'
      expect(statusLabel(state, i18n.global.t)).toBe(known ? en : `Unknown status: ${raw}`)
      i18n.global.locale.value = 'zh-CN'
      expect(statusLabel(state, i18n.global.t)).toBe(known ? zh : `未知状态：${raw}`)
    }
    for (const raw of [' OPEN ', 'open', '__proto__', '<b>unknown</b>', ' ']) {
      expect(classifyDomainStatus(raw, domain)).toEqual({ kind: 'unknown', raw })
    }
    for (const raw of ['', undefined])
      expect(classifyDomainStatus(raw, domain)).toEqual({ kind: 'missing' })
  },
)
