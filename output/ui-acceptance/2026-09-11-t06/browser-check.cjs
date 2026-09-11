async (page) => {
 const out='/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-11-t06';
 const c=page.context().browser().contexts().at(-1); if(c===page.context())throw Error('Independent QA context required');
 const q=c.pages()[0], checks=[];
 const cases=[['consistent','2098245288666046466','未发现逻辑矛盾'],['unsatisfiable','2098245288758321154','存在无法成立的对象类型'],['inconsistent','2098245288838012930','模型存在矛盾']];
 for(const [key,id,label] of cases){
  await q.goto('http://127.0.0.1:5189/semantic/ontologies/'+id+'/edit');
  await q.getByText('检查发布',{exact:true}).click();
  await q.getByRole('button',{name:'运行逻辑检查',exact:true}).click();
  await q.getByText(label,{exact:true}).waitFor({timeout:45000});
  const panel=q.locator('.logical-check-panel');
  if(key==='unsatisfiable') {await panel.getByText('传感器',{exact:true}).waitFor(); if((await panel.innerText()).includes('urn:t06'))throw Error('Raw IRI visible');}
  await panel.locator('summary').click(); await panel.locator('details[open]').waitFor(); await panel.locator('summary').click();
  checks.push({case:key,status:'PASS',text:await panel.innerText()});
 }
 await q.setViewportSize({width:1440,height:1000});
 await q.waitForFunction(()=>[...document.getAnimations()].every(a=>a.playState!=='running'));
 await q.screenshot({path:out+'/desktop.png',fullPage:true});
 for(const width of [390,768,2560]){
  await q.setViewportSize({width,height:1000}); await q.waitForFunction(()=>[...document.getAnimations()].every(a=>a.playState!=='running'));
  const dimensions=await q.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
  if(dimensions.scroll>width+1)throw Error('Horizontal overflow '+width);
  await q.locator('.logical-check-panel').scrollIntoViewIfNeeded();
  await q.screenshot({path:out+'/viewport-'+width+'.png',fullPage:true});checks.push({case:'viewport-'+width,status:'PASS',dimensions});
 }
 await q.setViewportSize({width:1440,height:1000});
 let release;const wait=new Promise(r=>release=r);
 await q.route('**/draft/reason',async route=>{await wait;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,data:{draftVersion:2,status:'CONSISTENT',consistent:true,unsatisfiableClasses:[],inputDigest:'qa',message:''}})}).catch(()=>{});});
 await q.getByRole('button',{name:'运行逻辑检查',exact:true}).click();
 await q.getByRole('button',{name:'停止等待',exact:true}).click();
 await q.getByText('已停止等待；服务器可能仍在处理本次检查。',{exact:true}).waitFor();release();
 checks.push({case:'stop-waiting',status:'PASS',mode:'controlled delayed HTTP'});
 await q.unroute('**/draft/reason');
 return checks;
}
