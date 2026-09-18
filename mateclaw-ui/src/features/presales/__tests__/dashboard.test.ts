import { describe, expect, it, vi } from 'vitest'
import { loadPortfolio, summarizeProjects } from '../shared/dashboard'
import type { PresalesProject, ProjectPage } from '../api/presalesApi'
function project(id: string, extra = {}): PresalesProject { return { id, name: id, customer: 'Customer', workspaceId: 'ws', ownerId: 'u', status: 'ACTIVE', version: 1, materials: [], requirements: [], clarifications: [], baselines: [], fitGaps: [], solutions: [], reviews: [], releases: [], ...extra } }
describe('portfolio statistics', () => {
  it('excludes archived records and separates questions, solutions and release stage', () => {
    const summary = summarizeProjects([project('a', { stage: 'REQUIREMENTS', openClarificationCount: 3 }), project('b', { stage: 'RELEASE', latestSolutionVersion: 2, openClarificationCount: 1 }), project('c', { status: 'ARCHIVED', openClarificationCount: 99, latestSolutionVersion: 4 })])
    expect(summary).toMatchObject({ active: 2, questions: 4, solutions: 1, releases: 1 })
    expect(summary.stages.reduce((sum, s) => sum + s.count, 0)).toBe(2)
    expect(summary.attention.map(p => p.id)).toEqual(['a', 'b'])
  })
  it('loads all pages and accepts serialized long totals without converting IDs', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ items: [project('90071992547409993')], total: '2' }).mockResolvedValueOnce({ items: [project('90071992547409994')], total: '2' })
    expect((await loadPortfolio(fetch, new AbortController().signal)).map(p => p.id)).toEqual(['90071992547409993', '90071992547409994'])
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('fails closed when pagination changes or repeats an item', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ items: [project('a')], total: 2 }).mockResolvedValueOnce({ items: [project('a')], total: 2 })
    await expect(loadPortfolio(fetch, new AbortController().signal)).rejects.toThrow('changed')
  })
  it('does not publish partial results after a later page fails', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ items: [project('a')], total: 2 }).mockRejectedValueOnce(new Error('Denied'))
    await expect(loadPortfolio(fetch, new AbortController().signal)).rejects.toThrow('Denied')
  })
  it('does not fetch for an aborted workspace request', async () => {
    const controller = new AbortController(); controller.abort()
    const fetch = vi.fn<() => Promise<ProjectPage>>()
    await expect(loadPortfolio(fetch, controller.signal)).rejects.toThrow('Aborted')
    expect(fetch).not.toHaveBeenCalled()
  })
})
