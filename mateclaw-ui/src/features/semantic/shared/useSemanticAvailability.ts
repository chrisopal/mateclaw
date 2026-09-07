import { readonly, ref } from 'vue'
import { ontologyApi } from '../api/ontologyApi'
const enabled = ref(false)
let generation = 0
export function useSemanticAvailability() {
  async function refresh() {
    const run = ++generation,
      token = localStorage.getItem('token')
    enabled.value = false
    if (!token) return false
    try {
      const status = await ontologyApi.status()
      if (run === generation && token === localStorage.getItem('token'))
        enabled.value = status.enabled === true
    } catch {
      if (run === generation) enabled.value = false
    }
    return enabled.value
  }
  return { enabled: readonly(enabled), refresh }
}
