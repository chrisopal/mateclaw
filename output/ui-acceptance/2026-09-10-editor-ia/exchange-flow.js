async(userPage)=>{
 const p=await userPage.context().newPage();const id='2097935363607769090';const modelName='设备运维业务模型 '+Date.now();const base='/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-editor-ia/';
 try{
  await p.setViewportSize({width:1440,height:1000});await p.goto(`http://127.0.0.1:5189/semantic/ontologies/${id}/edit`);await p.locator('.model-term').first().waitFor();
  await p.getByRole('button',{name:'更多 ▾',exact:true}).click();await p.getByRole('menuitem',{name:'基本信息',exact:true}).click();
  await p.locator('#ontology-name').fill(modelName);await p.getByRole('button',{name:'完成',exact:true}).click();
  await p.getByRole('button',{name:'保存草稿',exact:true}).click();await p.getByRole('button',{name:'检查模型',exact:true}).waitFor();await p.reload();await p.getByRole('heading',{name:modelName,exact:true}).waitFor();
  await p.getByRole('button',{name:'更多 ▾',exact:true}).click();await p.getByRole('menuitem',{name:'OWL 导入与导出',exact:true}).click();
  let functional;const exports=[];
  for(const [name,ext] of [['OWL Functional','ofn'],['OWL RDF/XML','rdf']]){
   const download=p.waitForEvent('download');const response=p.waitForResponse(r=>r.url().includes('/draft/document?'));
   await p.getByRole('button',{name,exact:true}).click();const r=await response;const bytes=await r.body();const d=await download;await d.saveAs(base+'business-model.'+ext);exports.push({syntax:name,bytes:bytes.length});if(ext==='ofn')functional=bytes;
  }
  await p.locator('input[type=file]').setInputFiles(base+'import-model.owl');
  await p.getByRole('button',{name:'保存草稿',exact:true}).click();await p.getByRole('button',{name:'检查模型',exact:true}).waitFor();
  const before=await p.evaluate(async id=>{const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');return a.getDraft(w().currentWorkspaceId,id)},id);
  if(before.document.source.syntax!=='FUNCTIONAL'||before.document.axioms.length!==13)throw Error('OWL roundtrip mismatch');
  await p.getByRole('button',{name:'检查模型',exact:true}).click();await p.getByText('检查通过，可以发布',{exact:true}).waitFor();
  await p.locator('.editor-actions').getByRole('button',{name:'发布版本',exact:true}).click();
  const dialog=p.getByRole('dialog').filter({visible:true});await dialog.locator('textarea').fill('业务模型编辑与 OWL 往返验收');
  await dialog.getByRole('button',{name:'确认发布',exact:true}).click();await p.waitForURL('**/versions?*');
  const versions=await p.evaluate(async id=>{const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');return a.revisions(w().currentWorkspaceId,id)},id);
  if(!versions.length)throw Error('published revision missing');
  return {id,metadata:before.name,exports,syntax:before.document.source.syntax,axiomCount:before.document.axioms.length,versions,url:p.url()};
 }finally{await p.close();if(!userPage.isClosed())await userPage.bringToFront();}
}
