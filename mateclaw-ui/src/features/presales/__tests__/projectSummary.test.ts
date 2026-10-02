import { afterEach, beforeEach, describe, expect, expectTypeOf, it } from 'vitest'
import { http } from '@/api'
import {
  presalesApi,
  type PresalesProject,
  type PresalesProjectSummary,
  type ProjectPage,
} from '../api/presalesApi'
import { loadPortfolio, summarizeProjects } from '../shared/dashboard'

const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})

const summary = {
  id: '9223372036854775800',
  workspaceId: '9223372036854775801',
  version: 7,
  name: 'Factory',
  customer: 'Customer',
  ownerId: '9223372036854775802',
  status: 'ACTIVE',
  stage: 'RELEASE',
  openClarificationCount: 3,
  latestSolutionVersion: 2,
  historicalExtension: { notes: ['preserved'] },
}
const collections = [
  'materials',
  'requirements',
  'clarifications',
  'baselines',
  'fitGaps',
  'cases',
  'solutions',
  'reviews',
  'reviewDrafts',
  'releases',
  'tasks',
  'contextCards',
]

describe('project summary consumer contract', () => {
  it('retains sparse list bytes and scoped paths without fabricating detail collections', async () => {
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe(summary.workspaceId)
      expect(config.url).toBe('/presales/projects')
      expect(config.params).toEqual({ page: 1, pageSize: 100 })
      return {
        data: { data: { items: [summary], total: 1, page: 1, pageSize: 100 } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    localStorage.setItem('mc-workspace-id', 'another-workspace')
    const rows = await loadPortfolio(
      (page) => presalesApi.list(summary.workspaceId, { page, pageSize: 100 }),
      new AbortController().signal,
    )
    expect(rows).toEqual([summary])
    expect(rows[0].id).toBe('9223372036854775800')
    for (const key of collections) expect(Object.hasOwn(rows[0], key)).toBe(false)
    expect(summarizeProjects(rows)).toMatchObject({
      active: 1,
      questions: 3,
      solutions: 1,
      releases: 1,
      attention: [summary],
    })
  })
  it('keeps detail collections only on the separate project read', async () => {
    const detail = {
      ...summary,
      ...Object.fromEntries(collections.map((key) => [key, []])),
    }
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe(summary.workspaceId)
      expect(config.url).toBe('/presales/projects/p%2F1')
      return {
        data: { data: detail },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    expect(await presalesApi.get(summary.workspaceId, 'p/1')).toEqual(detail)
    expect(summary).not.toHaveProperty('materials')
  })
})

it('does not let list summaries satisfy the complete detail contract', () => {
  expectTypeOf<ProjectPage['items'][number]>().toEqualTypeOf<PresalesProjectSummary>()
  expectTypeOf<PresalesProjectSummary>().not.toExtend<PresalesProject>()
  expectTypeOf<PresalesProject>().toExtend<PresalesProjectSummary>()
  expectTypeOf<
    ReturnType<typeof summarizeProjects>['attention'][number]
  >().toEqualTypeOf<PresalesProjectSummary>()
  expectTypeOf<
    Awaited<ReturnType<typeof loadPortfolio>>[number]
  >().toEqualTypeOf<PresalesProjectSummary>()
})
