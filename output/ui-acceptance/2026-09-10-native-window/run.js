async(page)=>{
 const userPage=page,results=[];
 if(userPage.viewportSize()!==null)throw Error('Acceptance must start in a native viewport tab');
 for(const width of [1500,2400,3840]){
  const testPage=await userPage.context().newPage();let simulatedFailure=false;
  try{await testPage.goto(userPage.url());await testPage.setViewportSize({width,height:1200});if(width===3840)throw Error('SIMULATED test failure');}
  catch(error){if(error.message!=='SIMULATED test failure')throw error;simulatedFailure=true;}
  finally{await testPage.close();await userPage.bringToFront();}
  const actual=await userPage.evaluate(()=>({innerWidth,innerHeight,outerWidth,outerHeight,devicePixelRatio}));
  if(userPage.viewportSize()!==null||actual.innerWidth!==actual.outerWidth)throw Error('Native window contaminated');
  results.push({emulatedWidth:width,simulatedFailure,actual});
 }
 await userPage.reload();await userPage.locator('.graph-surface').waitFor();
 const layout=await userPage.evaluate(()=>({width:innerWidth,outerWidth,documentWidth:document.documentElement.scrollWidth,panels:[...document.querySelectorAll('.semantic-page,.model-inspector,.semantic-actions')].map(e=>({class:e.className,left:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right})),buttons:[...document.querySelectorAll('button')].filter(e=>/导出 RDF|禁止新绑定|建立草稿/.test(e.textContent)).map(e=>({text:e.textContent,right:e.getBoundingClientRect().right}))}));
 if(layout.width!==layout.outerWidth||layout.documentWidth>layout.width||layout.panels.some(e=>e.right>layout.width)||layout.buttons.some(e=>e.right>layout.width))throw Error('Content clipped after native reload');
 await userPage.screenshot({path:'/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-native-window/native-window.png'});
 return {results,layout,viewport:userPage.viewportSize()};
}
