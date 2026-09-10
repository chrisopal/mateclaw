async(page)=>page.evaluate(async id=>{
 const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;
 const d=await a.getDraft(ws,id);const revisions=await a.revisions(ws,id);
 const draft=await a.draftProjection(ws,id,d.draftVersion);const published=await a.revisionProjection(ws,id,revisions[0].id);
 return {ontologyId:id,draft,published};
},page.url().split('/')[5])
