import { onScopeDispose, ref, watch } from 'vue'
import { ontologyApi } from '../api/ontologyApi'
import type { ProjectionView } from './standardProjection'

export interface ProjectionScope { workspaceId: string; ontologyId: string; revisionId: string; draftVersion?: number; documentDigest?: string }
type ProjectionApi=Pick<typeof ontologyApi,'draftProjection'|'revisionProjection'>
export function useOntologyProjection(scope:()=>ProjectionScope,api:ProjectionApi=ontologyApi) {
  const data=ref<ProjectionView|null>(null), loading=ref(false), error=ref('')
  let controller:AbortController|undefined, generation=0
  async function reload() {
    controller?.abort();const run=++generation;const current={...scope()};data.value=null;error.value='';loading.value=false
    if(!current.workspaceId||!current.ontologyId||!current.revisionId)return
    controller=new AbortController();loading.value=true
    try {
      const response=current.draftVersion===undefined
        ?await api.revisionProjection(current.workspaceId,current.ontologyId,current.revisionId,controller.signal)
        :await api.draftProjection(current.workspaceId,current.ontologyId,current.draftVersion,controller.signal)
      if(run!==generation || JSON.stringify(scope())!==JSON.stringify(current))return
      const s=response.snapshot,p=response.projection
      if(p.schemaVersion!=='ontology-display-v1'||s.ontologyId!==current.ontologyId||s.revisionId!==current.revisionId||s.draftVersion!==(current.draftVersion??null)||s.documentDigest!==p.documentDigest||s.importLockDigest!==p.importLockDigest||(current.documentDigest&&s.documentDigest!==current.documentDigest))throw Error('投影快照与当前文档不一致，请重新载入文档。')
      data.value=response
    }catch(e){if(run===generation && JSON.stringify(scope())===JSON.stringify(current))error.value=(e as Error).message||'投影加载失败'}finally{if(run===generation)loading.value=false}
  }
  watch(scope,reload,{immediate:true,deep:true})
  onScopeDispose(()=>{generation++;controller?.abort()})
  return {data,loading,error,reload}
}
