import { readonly, ref } from 'vue'
import { ontologyApi } from '../api/ontologyApi'
const enabled = ref(false)
let generation = 0
let pending: { token: string; result: Promise<boolean> } | undefined
export function useSemanticAvailability() {
  function refresh(): Promise<boolean> {
    const token = localStorage.getItem('token')
    if (!token) {
      generation++
      pending = undefined
      enabled.value = false
      return Promise.resolve(false)
    }
    // Navigation and the app shell can ask simultaneously; neither should invalidate the other.
    if (pending?.token === token) return pending.result
    const run = ++generation
    enabled.value = false
    const result = (async () => {
      try {
        const status = await ontologyApi.status()
        if (run === generation && token === localStorage.getItem('token')) {
          enabled.value = status.enabled === true
          return enabled.value
        }
        return false
      } catch {
        if (run === generation) enabled.value = false
        return false
      } finally {
        if (run === generation) pending = undefined
      }
    })()
    pending = { token, result }
    return result
  }
  return { enabled: readonly(enabled), refresh }
}
