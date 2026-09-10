async (userPage) => {
  const page = await userPage.context().newPage(); const commands = [];
  try {
    await page.setViewportSize({width:1440,height:1000});
    await page.goto('http://127.0.0.1:5189/semantic/ontologies/2097924016782405634/edit');
    await page.locator('.model-term').first().waitFor();
    const id = await page.evaluate(async () => {
      const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');
      const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');
      const ws=w().currentWorkspaceId;
      const o=await a.create(ws,{name:'设备运维业务模型验收 '+Date.now(),description:'Synthetic business model acceptance'});
      await a.createDraft(ws,o.id); return o.id;
    });
    await page.goto(`http://127.0.0.1:5189/semantic/ontologies/${id}/edit`);
    await page.getByRole('button',{name:'＋ 业务对象',exact:true}).waitFor();
    page.on('request',r=>{if(r.url().endsWith('/model-edits'))commands.push(r.postDataJSON())});
    const dialog=()=>page.getByRole('dialog').filter({visible:true});
    const save=async()=>{
      await dialog().getByRole('button',{name:'预览变更',exact:true}).click();
      const response=page.waitForResponse(r=>r.url().endsWith('/model-edits')&&r.request().method()==='POST');
      await dialog().getByRole('button',{name:'确认保存',exact:true}).click();
      const r=await response;if(!r.ok())throw Error(await r.text());
      await dialog().waitFor({state:'hidden'});
    };
    for(const name of ['设备','传感器']){
      await page.getByRole('button',{name:'＋ 业务对象',exact:true}).click();
      await dialog().getByLabel('名称',{exact:true}).fill(name);
      await save();await page.locator('.model-term').filter({hasText:name}).waitFor();
    }
    await page.getByRole('button',{name:'＋ 关系',exact:true}).click();
    await dialog().getByLabel('名称',{exact:true}).fill('安装传感器');
    await dialog().getByLabel('所属对象',{exact:true}).selectOption({label:'设备'});
    await dialog().getByLabel('关联对象',{exact:true}).selectOption({label:'传感器'});
    await dialog().getByLabel('名称',{exact:true}).click();
    await save();await page.locator('.model-term').filter({hasText:'安装传感器'}).waitFor();
    await page.getByRole('button',{name:'＋ 属性',exact:true}).click();
    await dialog().getByLabel('名称',{exact:true}).fill('温度');
    await dialog().getByLabel('所属对象',{exact:true}).selectOption({label:'设备'});
    await dialog().getByLabel('内容类型',{exact:true}).selectOption({label:'数字'});await save();
    await page.locator('.model-term').filter({hasText:'设备'}).click();
    await page.getByRole('button',{name:'编辑业务规则',exact:true}).click();
    await dialog().getByLabel('对象关系',{exact:true}).selectOption({label:'安装传感器'});
    await dialog().getByLabel('关联要求',{exact:true}).selectOption('MIN');
    await dialog().getByLabel('数量',{exact:true}).fill('0');
    await dialog().getByLabel('关联对象',{exact:true}).selectOption({label:'传感器'});await save();
    await page.reload();await page.locator('.model-term').filter({hasText:'设备'}).click();
    await page.getByRole('button',{name:'编辑信息',exact:true}).click();
    const names=await dialog().getByLabel('编辑内容',{exact:true}).locator('option').allTextContents();
    await dialog().getByLabel('编辑内容',{exact:true}).selectOption({label:names.find(n=>n.includes('名称'))});
    await dialog().getByLabel('内容',{exact:true}).fill('生产设备');await save();
    await page.locator('.model-term').filter({hasText:'生产设备'}).waitFor();
    await page.getByRole('button',{name:'＋ 业务对象',exact:true}).click();
    await dialog().getByLabel('名称',{exact:true}).fill('取消对象');
    await dialog().getByRole('button',{name:'重置',exact:true}).click();
    if(await dialog().getByLabel('名称',{exact:true}).inputValue())throw Error('reset failed');
    await dialog().getByRole('button',{name:'取消',exact:true}).click();
    await page.getByRole('button',{name:'＋ 业务对象',exact:true}).click();
    if(await dialog().getByLabel('名称',{exact:true}).inputValue())throw Error('cancel retained stale name');
    await dialog().getByRole('button',{name:'取消',exact:true}).click();
    await page.screenshot({path:'/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-editor-ia/business-model.png'});
    const draft=await page.evaluate(async id=>{
      const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');
      const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');return a.getDraft(w().currentWorkspaceId,id);
    },id);
    const text=draft.document.source.documentText;
    if(!text.includes('生产设备')||!text.includes('ObjectMinCardinality(0')||!text.includes('DataPropertyRange'))throw Error('persistence mismatch');
    if(commands.some(c=>JSON.stringify(c).includes('functionalSyntax')))throw Error('frontend generated OWL');
    return {id,commands,draftVersion:draft.draftVersion,axioms:draft.document.axioms,documentDigest:draft.document.documentDigest,nativeViewport:userPage.viewportSize()};
  } finally {await page.close();if(!userPage.isClosed())await userPage.bringToFront();}
}
