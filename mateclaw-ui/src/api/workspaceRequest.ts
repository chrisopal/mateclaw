import type { AxiosRequestConfig, AxiosRequestTransformer } from 'axios'
import { http } from '@/api'

/** Preserve Axios request semantics while pinning the captured Workspace at dispatch. */
export function workspaceRequest<T>(
  workspaceId: string,
  config: AxiosRequestConfig,
  signal?: AbortSignal,
): Promise<T> {
  const selected =
    config.transformRequest === undefined ? http.defaults.transformRequest : config.transformRequest
  const transforms = Array.isArray(selected) ? selected : selected ? [selected] : []
  const pinWorkspace: AxiosRequestTransformer = (data, headers) => {
    headers.set('X-Workspace-Id', workspaceId, true)
    return data
  }
  return http.request<unknown, T>({
    ...config,
    signal: signal ?? config.signal,
    transformRequest: [...transforms, pinWorkspace],
  })
}
