// Execute with the existing browser_run_code tool on an authenticated admin page.
// Prerequisite: select workspace from sample-fixture.json in the workspace menu.
async (page) => {
  const root = '/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-11-t09/';
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('http://127.0.0.1:5189/semantic/graphs/2098322037907009537');
  await page.getByRole('tab', { name: '建模样例', exact: true }).click();
  const form = page.locator('.sample-review');
  const field = label => form.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: label }) });
  const choose = async (label, option) => {
    await field(label).locator('.el-select__wrapper').click();
    await page.getByRole('option', { name: option, exact: true }).click();
    await form.getByRole('heading').click();
    await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden' });
  };
  await choose(/^建模样例$/, '1 · T09 合成样例：同名设备A');
  await choose('业务对象', '同名设备 · 2098322037969924098');
  await choose('业务对象', '同名设备 · 2098322037940563969');
  const results = [];
  for (const name of ['对象关系', '文本属性', '是同一对象', '是不同对象', '对象类型']) {
    await choose('核对内容', name);
    if (!await form.getByRole('button', { name: '提交审核' }).isDisabled()) throw Error('Incomplete claim enabled');
    results.push({ claim: name, incompleteDisabled: true });
  }
  await choose(/^类型$/, 'Equipment');
  await field('来源快照').locator('.el-select__wrapper').click();
  await page.getByRole('option').filter({ hasText: 'T09 合成建模样例：同句不同上下文' }).click();
  await form.locator('textarea').fill('同名设备属于设备。');
  await form.getByRole('heading').click();
    await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden' });
  if (!await form.getByRole('button', { name: '提交审核' }).isDisabled()) throw Error('Ambiguous quote enabled');
  await form.getByRole('alert').filter({ hasText: '这段原文出现多次' }).waitFor();
  await form.locator('textarea').fill('车间甲记录：同名设备属于设备。');
  if (await form.getByRole('button', { name: '提交审核' }).isDisabled()) throw Error('Unique quote disabled');
  await page.screenshot({ path: root + 'sample-1440.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.sample-review').getBoundingClientRect().right <= innerWidth && !document.getAnimations().some(a => a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity));
  await page.screenshot({ path: root + 'sample-390.png', fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw Error('Narrow overflow');
  // Read back the statement created through UI earlier, with its exact evidence.
  const readback = await page.evaluate(async () => {
    const headers = { Authorization: 'Bearer ' + localStorage.getItem('token'), 'X-Workspace-Id': localStorage.getItem('mc-workspace-id') };
    const get = async suffix => { const r = await fetch('/api/v1/semantic/graphs/2098322037907009537/' + suffix, { headers }); if (!r.ok) throw Error('Readback failed'); return (await r.json()).data; };
    return { history: await get('statements/2098323105969745921/revisions'), evidence: await get('evidence/2098323105923608578') };
  });
  if (readback.history.revisions[0].subjectId !== '2098322037940563969' || readback.evidence.exactQuote !== '车间甲记录：同名设备属于设备。') throw Error('Identity or evidence drift');
  await page.getByRole('tab', { name: '知识记录', exact: true }).click();
  await page.getByRole('row').filter({ hasText: '类型：Equipment' }).click();
  const drawer = page.getByRole('dialog', { name: '知识审核', exact: true });
  await drawer.getByText('r1 · 待确认 · 尚未审核 · 同名设备 · 类型：Equipment', { exact: true }).waitFor();
  await page.waitForFunction(() => { const b = document.querySelector('.el-drawer[aria-label="知识审核"]').getBoundingClientRect(); return b.width <= 390 && Math.abs(b.right - innerWidth) < 1; });
  const box = await drawer.boundingBox();
  await page.screenshot({ path: root + 'history-390.png' });
  await drawer.getByRole('button', { name: '证据 · 2098323105923608578', exact: true }).click();
  const evidence = page.getByRole('dialog', { name: '证据', exact: true });
  await evidence.getByText('车间甲记录：同名设备属于设备。', { exact: true }).waitFor();
  await evidence.getByRole('button', { name: '关闭此对话框' }).click();
  await drawer.getByRole('button', { name: '关闭此对话框' }).click();
  await page.setViewportSize({ width: 1440, height: 960 });
  return { pass: true, profile: await page.locator('html').getAttribute('data-ui-profile'), claims: results, ambiguousBlocked: true, uniqueAllowed: true, narrowDrawer: box, readback };
}
