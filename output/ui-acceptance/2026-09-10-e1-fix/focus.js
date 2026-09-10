async(page)=>{
 const results=[];
 for(const [scope,path] of [['draft','2097694137454493697/edit'],['published','2097611586143510530/versions']]){
  await page.goto('http://127.0.0.1:5189/semantic/ontologies/'+path);await page.locator('.model-term').first().waitFor();await page.setViewportSize({width:390,height:844});
  await page.locator('.model-term').first().click();await page.getByRole('button',{name:'关闭详情',exact:true}).click();
  const nodeClose=await page.getByRole('button',{name:'查看定义详情',exact:true}).evaluate(e=>e===document.activeElement);
  if(!nodeClose)throw Error(scope+' node close focus');
  await page.getByRole('button',{name:'查看定义详情',exact:true}).press('Enter');
  const rule=page.locator('.model-inspector .model-rule').first();await rule.locator('summary').click();const rendering=await rule.locator('pre').innerText();
  await rule.getByRole('button',{name:'查看公理与来源',exact:true}).click();
  await page.waitForFunction(()=>document.activeElement.matches('[data-ontology-source-panel]'));
  if(!(await page.locator('.source-focus').innerText()).includes(rendering))throw Error('wrong source');
  const focus=await page.evaluate(()=>({active:document.activeElement.tagName,tabindex:document.activeElement.getAttribute('tabindex'),drawerHidden:getComputedStyle(document.querySelector('.model-inspector')).display==='none'}));
  await page.getByRole('button',{name:'清除公理聚焦',exact:true}).click();
  results.push({scope,nodeClose,sourceFocus:focus,clear:await page.locator('.source-focus').count()===0});
 }
 await page.setViewportSize({width:1500,height:1000});return results;
}
