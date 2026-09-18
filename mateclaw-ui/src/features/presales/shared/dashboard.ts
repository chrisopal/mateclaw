import type { PresalesProject, ProjectPage } from '../api/presalesApi'
export const pipelineStages = ['DISCOVERY', 'REQUIREMENTS', 'BASELINED', 'SOLUTION', 'RELEASE'] as const
export function summarizeProjects(projects: PresalesProject[]) {
  const active = projects.filter(p => p.status !== 'ARCHIVED')
  return {
    active: active.length,
    questions: active.reduce((sum, p) => sum + Number(p.openClarificationCount || 0), 0),
    solutions: active.filter(p => Number(p.latestSolutionVersion) > 0).length,
    releases: active.filter(p => p.stage === 'RELEASE').length,
    stages: pipelineStages.map(key => ({ key, count: active.filter(p => (p.stage || 'DISCOVERY') === key).length })),
    attention: active.filter(p => Number(p.openClarificationCount) > 0).sort((a, b) => Number(b.openClarificationCount) - Number(a.openClarificationCount)).slice(0, 4),
  }
}
export async function loadPortfolio(fetchPage: (page: number) => Promise<ProjectPage>, signal: AbortSignal) {
  const rows: PresalesProject[] = []
  let total = -1
  for (let page = 1; page <= 50; page++) {
    if (signal.aborted) throw new Error('Aborted')
    const result = await fetchPage(page)
    if (total !== -1 && total !== Number(result.total)) throw new Error('Project list changed; refresh the overview.')
    total = Number(result.total)
    rows.push(...result.items)
    if (rows.length >= total) {
      if (new Set(rows.map(p => p.id)).size !== total) throw new Error('Project list changed; refresh the overview.')
      return rows
    }
    if (!result.items.length) break
  }
  throw new Error('Overview is too large to load. Narrow the workspace scope.')
}
