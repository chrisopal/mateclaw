async(userPage)=>{
 const p=await userPage.context().newPage();const root='/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-editor-ia/';const measurements=[];let originalTheme;
 try{
  await p.setViewportSize({width:1440,height:1000});await p.goto('http://127.0.0.1:5189/semantic/ontologies/2097935363607769090/edit');await p.locator('.model-term').first().waitFor();
  originalTheme=await p.locator('.theme-btn.active').getAttribute('title');
  for(const width of [1366,1440,1920,390]){
   await p.setViewportSize({width,height:width===390?844:1000});
   await p.reload();await p.locator('.model-term').first().waitFor();
   await p.getByRole('button',{name:'业务模型',exact:true}).click();
   await p.getByRole('button',{name:'适应画布',exact:true}).click();
   await p.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   const geometry=await p.evaluate(()=>({inner:innerWidth,scroll:document.documentElement.scrollWidth,main:document.querySelector('.ontology-business-editor').getBoundingClientRect().toJSON(),canvas:document.querySelector('.model-canvas-panel').getBoundingClientRect().toJSON()}));
   if(geometry.scroll>width+1||geometry.main.right>width+1)throw Error('horizontal overflow '+JSON.stringify(geometry));
   await p.waitForFunction(()=>!document.querySelector('#main-sidebar').getAnimations().some(a=>a.playState==='running'),null,{timeout:5000});
   await p.screenshot({path:root+`layout-${width}.png`});measurements.push({width,...geometry});
   if(width===390){await p.getByRole('button',{name:'＋ 业务对象',exact:true}).click();const d=p.getByRole('dialog').filter({visible:true});await d.getByLabel('名称',{exact:true}).fill('窄屏输入');await d.getByRole('button',{name:'取消',exact:true}).click();}
  }
  await p.setViewportSize({width:1440,height:1000});
  const themes=await p.locator('.theme-btn').evaluateAll(es=>es.map(e=>e.title));
  await p.getByTitle(themes[1],{exact:true}).click();await p.locator('html.dark').waitFor();
  await p.getByRole('button',{name:'适应画布',exact:true}).click();await p.waitForFunction(()=>!document.querySelector('#main-sidebar').getAnimations().some(a=>a.playState==='running'),null,{timeout:5000});await p.screenshot({path:root+'layout-dark.png'});
  const entry=p.getByRole('button',{name:'＋ 业务对象',exact:true});await entry.focus();await p.keyboard.press('Enter');
  const d=p.getByRole('dialog').filter({visible:true});await d.getByLabel('名称',{exact:true}).fill('键盘输入');await p.keyboard.press('Escape');await d.waitFor({state:'hidden'});
  const focus=await p.evaluate(()=>({text:document.activeElement?.textContent,visible:!!document.activeElement?.getClientRects().length}));
  if(!focus.visible||!focus.text.includes('业务对象'))throw Error('dialog focus lost '+JSON.stringify(focus));
  await p.getByRole('button',{name:'参考资料',exact:true}).click();await p.getByTestId('add-reference').waitFor();await p.getByRole('button',{name:'检查发布',exact:true}).click();await p.getByRole('heading',{name:'发布前检查'}).waitFor();await p.getByRole('button',{name:'业务模型',exact:true}).click();await p.locator('.model-term').first().waitFor();
  return {measurements,dark:true,focus,navigation:true,nativeViewport:userPage.viewportSize()};
 }finally{if(originalTheme&&!p.isClosed()){await p.setViewportSize({width:1440,height:1000});await p.getByTitle(originalTheme,{exact:true}).click();}await p.close();if(!userPage.isClosed())await userPage.bringToFront();}
}
