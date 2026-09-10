import { semanticRequest } from './ontologyApi'

export interface SourceKnowledgeBase {
  id: string | number
  name: string
}

export interface SourceMaterialSummary {
  id: string | number
  kbId?: string | number
  title: string
}

export interface SourceMaterial {
  knowledgeBaseId: string
  sourceRef: string
  sourceTitle: string
  sourceText: string
  sourceDigest: string
}

const ontologyPath = (id: string) => `/semantic/ontologies/${encodeURIComponent(id)}`

/** Convert the first exact occurrence into the server's Unicode code-point range. */
export function exactQuoteRange(sourceText: string, exactQuote: string) {
  const quote = exactQuote.trim()
  if (!quote) return null
  const start = sourceText.indexOf(quote)
  if (start < 0) return null
  const startCodePoint = [...sourceText.slice(0, start)].length
  return { startCodePoint, endCodePoint: startCodePoint + [...quote].length, exactQuote: quote }
}

/** Read-only source picker endpoints. Every request is pinned to the active workspace. */
export const sourceSelectionApi = {
  knowledgeBases: (ws: string, signal?: AbortSignal) =>
    semanticRequest<SourceKnowledgeBase[]>(ws, { url: '/wiki/knowledge-bases' }, signal),
  materials: (ws: string, knowledgeBaseId: string, signal?: AbortSignal) =>
    semanticRequest<SourceMaterialSummary[]>(
      ws,
      { url: `/wiki/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/raw` },
      signal,
    ),
  material: (ws: string, ontologyId: string, knowledgeBaseId: string, sourceRef: string, signal?: AbortSignal) =>
    semanticRequest<SourceMaterial>(
      ws,
      {
        url: `${ontologyPath(ontologyId)}/source-material`,
        params: { knowledgeBaseId, sourceRef },
      },
      signal,
    ),
}

export type SourceSelectionApi = typeof sourceSelectionApi
