import { expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { displayModel, expressionOperatorLabel, expressionTrees, type ProjectionView } from '../standardProjection'
import { useOntologyProjection, type ProjectionScope } from '../useOntologyProjection'
const response=():ProjectionView=>({snapshot:{ontologyId:'o',revisionId:'d',draftVersion:1,documentDigest:'digest',importLockDigest:'imports'},projection:{schemaVersion:'ontology-display-v1',documentDigest:'digest',importLockDigest:'imports',nodes:[{id:'class:urn:p',iri:'urn:p',kind:'class',labels:[{value:'English',language:'en',axiomId:'label-en'},{value:'中文',language:'zh',axiomId:'label-zh'}],axiomIds:['a'],features:[],imported:false}],edges:[],axiomRefs:[{id:'a',artifactId:'d',axiomId:'a',imported:false,rendering:'unsupported original',axiomType:'HasKey',status:'NOT_RENDERED',reason:'UNSUPPORTED'}],coverage:{total:1,returned:1,truncated:false,dependencyScope:'ROOT',lockedImportCount:0}}})
const flush=async()=>{await nextTick();await new Promise(r=>setTimeout(r,0))}
it('uses standard typed nodes, language fallback and explicit coverage without reading rendering',()=>{
 const data=response().projection
 expect(displayModel(data,'zh-CN').nodes[0]?.label).toBe('中文')
 expect(displayModel(data,'en').nodes[0]?.id).toBe('class:urn:p')
 expect(displayModel(data,'en').unprojectedAxiomIds).toEqual(['a'])
 expect(displayModel(null,'zh').nodes).toEqual([])
})
it('rejects mismatched snapshot/schema and retains an explicit error',async()=>{
 const scope=effectScope(),api={draftProjection:vi.fn().mockResolvedValue({...response(),snapshot:{...response().snapshot,draftVersion:2}}),revisionProjection:vi.fn()}
 const state=scope.run(()=>useOntologyProjection(()=>({workspaceId:'ws',ontologyId:'o',revisionId:'d',draftVersion:1}),api))!
 await flush();expect(state.data.value).toBeNull();expect(state.error.value).toContain('快照');scope.stop()
})
it('ignores a late response after switching scope and uses the revision read endpoint',async()=>{
 let resolve!:(value:ProjectionView)=>void
 const api={draftProjection:vi.fn().mockReturnValue(new Promise<ProjectionView>(r=>resolve=r)),revisionProjection:vi.fn().mockResolvedValue({...response(),snapshot:{...response().snapshot,revisionId:'published',draftVersion:null}})}
 const selection=ref<ProjectionScope>({workspaceId:'ws',ontologyId:'o',revisionId:'d',draftVersion:1}),scope=effectScope()
 const state=scope.run(()=>useOntologyProjection(()=>selection.value,api))!;await flush()
 selection.value={workspaceId:'ws',ontologyId:'o',revisionId:'published'};await flush();resolve(response());await flush()
 expect(state.data.value?.snapshot.revisionId).toBe('published');expect(api.revisionProjection).toHaveBeenCalledOnce();scope.stop()
})
it('clears stale data on a failed refresh and permits an explicit retry',async()=>{
 const api={draftProjection:vi.fn().mockResolvedValue(response()),revisionProjection:vi.fn()},scope=effectScope()
 const state=scope.run(()=>useOntologyProjection(()=>({workspaceId:'ws',ontologyId:'o',revisionId:'d',draftVersion:1}),api))!;await flush()
 api.draftProjection.mockRejectedValueOnce(Error('offline'));await state.reload();expect(state.data.value).toBeNull();expect(state.error.value).toBe('offline')
 await state.reload();expect(state.data.value?.snapshot.draftVersion).toBe(1);scope.stop()
})
it('builds ordered recursive expression trees and resolves named targets without parsing rendering',()=>{
 const data=response().projection
 const projection={...data,nodes:[...data.nodes,{id:'class:urn:q',iri:'urn:q',kind:'class' as const,labels:[],axiomIds:['a'],features:[],imported:false}],axiomRefs:[{...data.axiomRefs[0]!,axiomId:'b'},{...data.axiomRefs[0]!,axiomId:'a'}],expressions:[
   {id:'root-b',axiomId:'b',path:'root',operator:'ObjectPropertyChain',operands:[{role:'chain',position:2,targetId:'class:urn:q',value:null},{role:'chain',position:0,targetId:'class:urn:p',value:null}]},
   {id:'root-a',axiomId:'a',path:'root',operator:'ObjectAllValuesFrom',operands:[{role:'property',position:0,targetId:'class:urn:p',value:null},{role:'filler',position:1,targetId:'nested-a',value:null}]},
   {id:'nested-a',axiomId:'a',path:'root/1',operator:'ObjectSomeValuesFrom',operands:[{role:'property',position:0,targetId:'class:urn:q',value:null}]},
 ]}
 const roots=expressionTrees(projection)
 expect(roots.map(root=>root.expression.id)).toEqual(['root-b','root-a'])
 expect(roots[0]!.operands.map(item=>item.label)).toEqual(['English','q'])
 expect(roots[0]!.operands[1]!.target?.iri).toBe('urn:q')
 expect(roots[1]!.operands[1]!.expression?.expression.operator).toBe('ObjectSomeValuesFrom')
 expect(roots[1]!.operands[0]!.target?.id).toBe('class:urn:p')
 expect(expressionOperatorLabel('ObjectAllValuesFrom','zh-CN')).toContain('全称限制')
 expect(expressionTrees(data)).toEqual([])
})
