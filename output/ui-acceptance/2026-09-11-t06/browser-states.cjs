async (page) => {
 const c=page.context().browser().contexts().at(-1);if(c===page.context())throw Error('QA context required');const q=c.pages()[0],checks=[];await q.unroute('**/draft/reason');
 for(const [status,label] of [['TIMEOUT','逻辑检查超时，请稍后重试。'],['FAILED','逻辑检查失败，请重试。'],['UNSUPPORTED','当前环境不支持逻辑检查。']]){
  await q.route('**/draft/reason',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,data:{draftVersion:2,status,consistent:null,unsatisfiableClasses:[],inputDigest:'qa',message:'Controlled QA response'}})}));
  await q.getByRole('button',{name:'运行逻辑检查',exact:true}).click();await q.getByText(label,{exact:true}).waitFor();
  if(await q.getByText('未发现逻辑矛盾',{exact:true}).isVisible())throw Error('Failure shown as success');checks.push({status,result:'PASS',mode:'controlled HTTP'});await q.unroute('**/draft/reason');
 }
 await q.route('**/draft/reason',r=>r.fulfill({status:409,contentType:'application/json',body:JSON.stringify({code:409,msg:'Draft changed',data:{code:'REASONING_STALE',fieldErrors:[]}})}));
 await q.getByRole('button',{name:'运行逻辑检查',exact:true}).click();await q.getByText('检查结果对应旧版本，已丢弃，请重新运行。',{exact:true}).waitFor();checks.push({status:'REASONING_STALE',result:'PASS',mode:'controlled HTTP'});await q.unroute('**/draft/reason');
 return checks;
}
