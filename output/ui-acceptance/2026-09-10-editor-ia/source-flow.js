async(userPage)=>{
 const p=await userPage.context().newPage();const id='2097935363607769090';
 try{
  await p.setViewportSize({width:1440,height:1000});await p.goto(`http://127.0.0.1:5189/semantic/ontologies/${id}/edit`);await p.locator('.model-term').first().waitFor();
  const fixture=await p.evaluate(async()=>{const {semanticRequest:r}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;const kb=await r(ws,{url:'/wiki/knowledge-bases',method:'POST',data:{name:'设备运维验收资料 '+Date.now(),description:'Synthetic source fixture'}});const raw=await r(ws,{url:`/wiki/knowledge-bases/${kb.id}/raw/text`,method:'POST',data:{title:'设备传感器配置规范',content:'测试资料。设备可安装温度传感器，用于记录运行温度。'}});return {kbId:kb.id,kbName:kb.name,raw};});
  await p.getByRole('button',{name:'参考资料',exact:true}).click();await p.getByTestId('add-reference').click();
  const dialog=p.getByRole('dialog').filter({visible:true});
  await dialog.getByTestId('reference-rule-select').click();
  const rules=p.getByRole('option').filter({visible:true});await rules.first().click();
  await dialog.getByTestId('reference-kb-select').click();await p.getByRole('option',{name:fixture.kbName,exact:true}).click();
  await dialog.getByTestId('reference-document-select').click();await p.getByRole('option',{name:'设备传感器配置规范',exact:true}).click();
  await dialog.locator('summary').click();
  if(!(await dialog.textContent()).includes('设备可安装温度传感器'))throw Error('No source body');
  await dialog.getByTestId('reference-excerpt').fill('设备可安装温度传感器，用于记录运行温度。');
  await dialog.getByText('专家确认',{exact:true}).click();
  const response=p.waitForResponse(r=>r.url().includes('/axiom-sources')&&r.request().method()==='POST');
  await dialog.getByTestId('save-reference').click();const r=await response;if(!r.ok())throw Error(await r.text());
  const responseBody=await r.json();const requestBody=r.request().postDataJSON();await dialog.waitFor({state:'hidden'});await p.reload();await p.getByRole('button',{name:'参考资料',exact:true}).click();
  await p.locator('.reference-table').getByText('设备传感器配置规范',{exact:true}).first().waitFor();
  await p.screenshot({path:'/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-editor-ia/references.png'});
  return {fixture,response:responseBody,request:requestBody,visible:await p.locator('.reference-table').innerText()};
 }finally{await p.close();if(!userPage.isClosed())await userPage.bringToFront();}
}
