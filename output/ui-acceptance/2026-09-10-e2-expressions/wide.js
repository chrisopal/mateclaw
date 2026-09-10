async(page)=>{
 // Responsive emulation runs in a disposable tab, never the user's native window.
 const userPage=page;page=await userPage.context().newPage();
 try{await page.goto(userPage.url());

 const results=[];
 for(const scope of ['draft','published','list']){
  const url=scope==='list'?'http://127.0.0.1:5189/semantic/ontologies':`http://127.0.0.1:5189/semantic/ontologies/${scope==='draft'?'2097906448273219585':'2097611586143510530'}/${scope==='draft'?'edit':'versions'}`;
  await page.setViewportSize({width:1500,height:1200});await page.goto(url);await page.locator('.semantic-page').waitFor();if(scope!=='list')await page.locator('.graph-surface').waitFor();
  const samples=[];
  for(const width of [1500,1920,2400,3840,1920,2400]){
   await page.setViewportSize({width,height:1200});
   await page.waitForFunction(()=>{const e=document.querySelector('.semantic-page'),r=e.getBoundingClientRect(),p=e.parentElement.getBoundingClientRect();return Math.abs(r.width-p.width)<2&&Math.abs(r.left-p.left)<2});
   if(scope!=='list')await page.waitForFunction(()=>{const el=document.querySelector('.graph-surface'),cy=el?._cyreg?.cy;return cy&&Math.abs(cy.width()-el.clientWidth)<2});
   samples.push(await page.locator('.semantic-page').evaluate(e=>{const el=document.querySelector('.graph-surface');return {viewport:innerWidth,page:e.getBoundingClientRect().width,available:e.parentElement.getBoundingClientRect().width,canvas:el?.clientWidth??null,cy:el?._cyreg?.cy?.width()??null,horizontalOverflow:document.documentElement.scrollWidth>innerWidth}}));
  }
  if(samples.some(s=>s.horizontalOverflow))throw Error(scope+' horizontal overflow');
  await page.screenshot({path:`/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-e2-expressions/${scope}-wide.png`});
  results.push({control:scope+'-wide',check:'resize-1500-1920-2400-3840',steps:['1500 → 1920 → 2400 → 3840 → 1920 → 2400；测量页面及画布'],expected:'页面占满可用宽度；画布随容器扩展；无水平溢出',status:'PASS',actual:JSON.stringify(samples),evidence:['wide-results.json',`${scope}-wide.png`]});
 }
 return {results};

 }finally{await page.close();await userPage.bringToFront();}
}
