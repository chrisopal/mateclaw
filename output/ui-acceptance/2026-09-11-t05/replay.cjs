async (page) => {
  const context = page.context().browser().contexts().at(-1);
  if (context === page.context()) throw Error('Use a separate QA browser context, never the user tab');
  const qa = context.pages()[0];
  if (!qa.url().includes('/2098236751617421313/edit')) throw Error('Expected the persistent T05 QA fixture');
  const report = {};
  await qa.setViewportSize({width:1440,height:1000});
  await qa.reload();
  await qa.getByRole('button',{name:'业务规则',exact:true}).click();
  const powerRule=qa.locator('.policy-rule-row').filter({hasText:'额定功率'});
  await powerRule.waitFor();
  for(const unit of ['MW','kW']) {
    await powerRule.getByRole('button',{name:'编辑',exact:true}).click();
    const d=qa.getByRole('dialog');
    await d.getByPlaceholder('例如：kW、mm（可选）').fill(unit);
    await d.getByRole('button',{name:'应用规则'}).click();
    await d.waitFor({state:'hidden'});
    if (!await qa.getByRole('button',{name:'检查样例',exact:true}).isDisabled()) throw Error('Unsaved rules allowed sample checking');
    const saved=qa.waitForResponse(r=>r.url().endsWith('/business-policy')&&r.request().method()==='PUT');
    await qa.getByRole('button',{name:'保存业务规则',exact:true}).click();
    if((await saved).status()!==200)throw Error('Save failed');
    await powerRule.filter({hasText:unit}).waitFor();
    await qa.reload();await qa.getByRole('button',{name:'业务规则',exact:true}).click();
    await powerRule.filter({hasText:unit}).waitFor();
  }
  report.editSaveRefresh=true;
  const result=qa.locator('.sample-result[role=status]');
  const run=async(text)=>{await qa.getByRole('button',{name:'检查样例',exact:true}).click();await result.filter({hasText:text}).waitFor();return result.innerText();};
  report.missing=await run('缺少必填属性');
  await qa.getByText('部分提交',{exact:true}).click();report.partial=await run('符合业务规则');
  await qa.getByText('完整提交',{exact:true}).click();
  const code=qa.locator('.sample-property-field').filter({hasText:'设备编号'});
  const power=qa.locator('.sample-property-field').filter({hasText:'额定功率'});
  await code.locator('textarea').fill('EQ-001\nEQ-002');await power.locator('textarea').fill('10');await power.getByPlaceholder('单位（可选）').fill('W');
  report.singleAndUnit=await run('单位不匹配');
  if(!report.singleAndUnit.includes('只允许一个不同值'))throw Error('Single value not checked');
  await code.locator('textarea').fill('INVALID');await power.getByPlaceholder('单位（可选）').fill('kW');report.allowedValues=await run('不在允许值范围内');
  await code.locator('textarea').fill('EQ-001');report.valid=await run('符合业务规则');
  report.layout=[];
  for(const width of [1440,390,1920]) {
    await qa.setViewportSize({width,height:1000});
    await qa.getByRole('button',{name:'业务规则',exact:true}).scrollIntoViewIfNeeded();
    await qa.waitForFunction(()=>[...document.getAnimations()].every(a=>a.playState!=='running'));
    const size=await qa.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}));
    if(size.scrollWidth>size.width)throw Error('Page overflow');
    report.layout.push(size);
    await qa.screenshot({path:'/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-11-t05/policy-'+width+'.png',fullPage:true});
  }
  return report;
}
