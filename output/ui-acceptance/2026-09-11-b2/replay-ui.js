async (page) => {
  // Run with an authenticated page; never copy credentials into artifacts.
  const context = await page.context().browser().newContext({ storageState: await page.context().storageState(), viewport: { width: 1440, height: 1000 } });
  const qa = await context.newPage();
  const output = '/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-11-b2/';
  const results = [];
  const verify = (check, passed, actual) => { results.push({ check, passed, actual }); if (!passed) throw new Error(check + ': ' + JSON.stringify(actual)); };
  try {
    await qa.goto('http://127.0.0.1:5189/semantic/ontologies/2098228271250644993/edit?taskId=c5e4cce8-aba3-48f2-814c-9b473b571cd5');
    await qa.getByText('本批已确认，可继续建模 · 已选 2 份资料', { exact: true }).waitFor();
    verify('task_recovery', await qa.getByText('已采用', { exact: true }).isVisible(), 'Accepted proposal and task restored');
    verify('goal_collapsed', await qa.getByText('查看建模需求', { exact: true }).evaluate(e => !e.closest('details').open), 'Requirement disclosure defaults closed');
    await qa.getByRole('button', { name: '业务模型', exact: true }).focus();
    await qa.getByRole('button', { name: '业务模型', exact: true }).press('Enter');
    await qa.getByPlaceholder('搜索对象或关系').waitFor();
    const alignment = await qa.evaluate(() => ({ headerRight: document.querySelector('.editor-actions').getBoundingClientRect().right, modelRight: document.querySelector('.model-actions').getBoundingClientRect().right }));
    verify('action_alignment', Math.abs(alignment.headerRight - alignment.modelRight) <= 1, alignment);
    for (const width of [1440, 1920, 2560, 768, 390]) {
      await qa.setViewportSize({ width, height: 1000 });
      await qa.waitForTimeout(400);
      const size = await qa.evaluate(() => { const root = document.querySelector('.ontology-business-editor'), r = root.getBoundingClientRect(); return { width: innerWidth, scrollWidth: document.documentElement.scrollWidth, right: r.right, contentScroll: root.scrollWidth, contentWidth: root.clientWidth }; });
      verify('width_' + width, size.scrollWidth === width && Math.abs(size.right - width) <= 1 && size.contentScroll <= size.contentWidth + 1, size);
      await qa.screenshot({ path: output + 'replay-' + width + '.png' });
    }
    verify('catalog_collapsed', await qa.getByRole('button', { name: '展开目录 · 6', exact: true }).isVisible(), 'Mobile catalog collapsed with search available');
    const zoomBoxes = await qa.locator('.graph-zoom-controls>*').evaluateAll(es => es.map(e => { const r = e.getBoundingClientRect(); return { top: r.top, bottom: r.bottom }; }));
    verify('zoom_group', Math.max(...zoomBoxes.map(r => r.top)) < Math.min(...zoomBoxes.map(r => r.bottom)), zoomBoxes);
    const before = parseInt(await qa.locator('.graph-zoom').innerText());
    await qa.getByRole('button', { name: '放大', exact: true }).click();
    const enlarged = parseInt(await qa.locator('.graph-zoom').innerText());
    verify('zoom_in', enlarged > before, { before, enlarged });
    await qa.getByRole('button', { name: '适应画布', exact: true }).click();
    await qa.getByPlaceholder('搜索对象或关系').fill('温度');
    await qa.getByPlaceholder('搜索对象或关系').press('Tab');
    verify('search_blur', await qa.getByRole('button', { name: '收起目录 · 1', exact: true }).isVisible(), 'Search opens matching directory and survives blur');
    await qa.getByPlaceholder('搜索对象或关系').fill('');
    await qa.setViewportSize({ width: 1440, height: 1000 });
    await qa.getByRole('button', { name: '开始建模', exact: true }).click();
    verify('empty_create_blocked', await qa.getByRole('dialog').getByRole('button', { name: '创建任务', exact: true }).isDisabled(), 'Empty required goal cannot create');
    await qa.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
    await qa.getByRole('dialog').waitFor({ state: 'hidden' });
    verify('create_cancel', await qa.getByRole('dialog').count() === 0, 'Cancel closes intake without mutation');
    return results;
  } finally { await context.close(); }
}
