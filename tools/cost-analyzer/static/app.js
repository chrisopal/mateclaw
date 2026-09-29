(() => {
  'use strict';

  const STORAGE_KEY = 'cost-analyzer:last-report';
  const MAX_FILE_BYTES = 10 * 1024 * 1024;
  const state = { report: null, product: '', month: '', offline: false, busy: false };
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const finite = (value) => value === null || value === undefined || (typeof value === 'string' && value.trim() === '') || !Number.isFinite(Number(value)) ? null : Number(value);
  const number = (value, digits = 2) => { const parsed = finite(value); return parsed === null ? '—' : parsed.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits }); };
  const signed = (value, digits = 2) => { const parsed = finite(value); return parsed === null ? '—' : `${parsed >= 0 ? '+' : ''}${number(parsed, digits)}`; };
  const pct = (value) => { const parsed = finite(value); return parsed === null ? '—' : `${parsed >= 0 ? '+' : ''}${(parsed * 100).toFixed(1)}%`; };
  const rawNumber = finite;
  const byMonth = (a, b) => String(a).localeCompare(String(b));
  const productName = (report, code) => report.products?.find((item) => item.code === code)?.name || code || '全部产品';
  const productUnit = (report, code) => report.products?.find((item) => item.code === code)?.unit || '单位';
  const costUnit = (report, code) => `元 / ${productUnit(report, code)}`;
  const rowsForProduct = (report, product) => (report.monthly || []).filter((row) => !product || row.product === product);
  const monthsForProduct = (report, product) => [...new Set(rowsForProduct(report, product).map((row) => row.month).filter(Boolean))].sort(byMonth);
  const calendarMonths = (report) => {
    const observed = [...new Set((report.monthly || []).map((row) => row.month).filter((month) => /^\d{4}-\d{2}$/.test(month)))].sort(byMonth);
    if (observed.length < 2) return report.months?.length ? report.months : observed;
    const [startYear, startMonth] = observed[0].split('-').map(Number);
    const [endYear, endMonth] = observed[observed.length - 1].split('-').map(Number);
    const result = [];
    for (let year = startYear, month = startMonth; year < endYear || (year === endYear && month <= endMonth); month += 1) {
      if (month > 12) { month = 1; year += 1; }
      result.push(`${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`);
    }
    return result;
  };
  const comparisonFor = (report, product, month) => (report.comparisons || []).find((item) => item.product === product && item.month === month);
  const axisVisible = (index, count) => count <= 12 || index === 0 || index === count - 1 || index % Math.ceil(count / 12) === 0;
  const lineSegments = (points) => { const result = []; let segment = []; points.forEach((point) => { if (point) segment.push(point); else if (segment.length) { result.push(segment); segment = []; } }); if (segment.length) result.push(segment); return result; };

  function setBusy(value, label) {
    state.busy = value;
    document.body.classList.toggle('is-loading', value);
    const sample = $('sample-button');
    if (sample) { sample.disabled = value; sample.textContent = value ? '正在分析…' : '体验示例'; }
    const hint = $('drop-hint');
    if (hint && value) hint.textContent = label || '正在读取 Excel，请稍候…';
  }

  function showError(messages, title = '分析未完成') {
    const list = Array.isArray(messages) ? messages : [messages];
    $('error-title').textContent = title;
    $('error-message').innerHTML = list.map((message) => `<span>${esc(message)}</span>`).join('<br>');
    $('error-panel').hidden = false;
  }

  function hideError() { $('error-panel').hidden = true; }

  function clearActiveReport() {
    state.report = null;
    state.product = '';
    state.month = '';
    state.offline = false;
    document.body.classList.remove('is-offline');
    $('report-view').hidden = true;
    $('offline-banner').hidden = true;
    $('empty-state').hidden = false;
    $('runtime-label').textContent = '正在准备';
    try { localStorage.removeItem(STORAGE_KEY); } catch { /* storage may be disabled */ }
  }

  function beginRun(label) {
    if (state.busy) return false;
    clearActiveReport();
    hideError();
    setBusy(true, label);
    return true;
  }

  async function readJson(response) {
    let payload;
    try { payload = await response.json(); } catch { throw new Error(`服务器返回了无法识别的响应（${response.status}）`); }
    if (!response.ok) throw new Error((payload.errors || ['请求失败']).join('；'));
    return payload;
  }

  async function loadReport(report, { offline = false, persist = true } = {}) {
    if (!report || !Array.isArray(report.monthly)) throw new Error('报告数据结构不完整，无法渲染。');
    state.report = report;
    state.offline = offline;
    const products = report.products || [];
    state.product = state.product && products.some((item) => item.code === state.product) ? state.product : (products[0]?.code || '');
    const months = monthsForProduct(report, state.product);
    state.month = state.month && months.includes(state.month) ? state.month : (months[months.length - 1] || '');
    if (persist && !offline) saveSelection();
    document.body.classList.toggle('is-offline', offline);
    $('runtime-label').textContent = offline ? '离线快照' : '报告已就绪';
    $('offline-banner').hidden = !offline;
    $('report-view').hidden = false;
    $('empty-state').hidden = true;
    renderSelectors();
    renderReportMeta();
    renderAll();
  }

  function renderSelectors() {
    const report = state.report;
    const products = report.products || [];
    const months = monthsForProduct(report, state.product);
    $('product-select').innerHTML = products.map((item) => `<option value="${esc(item.code)}">${esc(item.code)} · ${esc(item.name)}</option>`).join('');
    $('month-select').innerHTML = months.map((month) => `<option value="${esc(month)}">${esc(month)}</option>`).join('');
    $('product-select').value = state.product;
    $('month-select').value = state.month;
  }

  function renderReportMeta() {
    const report = state.report;
    $('report-title').textContent = report.filename ? `${report.filename} · 成本分析` : '成本分析结果';
    const monthCount = report.months?.length || new Set((report.monthly || []).map((row) => row.month)).size;
    $('report-meta-text').textContent = `${number(report.record_count, 0)} 条明细 · ${report.products?.length || 0} 个产品 · ${monthCount} 个月`;
    const suffix = `?product=${encodeURIComponent(state.product)}&month=${encodeURIComponent(state.month)}`;
    if (report.id && !state.offline) {
      $('export-excel').href = `/api/export/${encodeURIComponent(report.id)}.xlsx${suffix}`;
      $('export-html').href = `/api/export/${encodeURIComponent(report.id)}.html${suffix}`;
    }
  }

  function renderAll() {
    renderReportMeta();
    renderKpis();
    renderInsights();
    const unit = costUnit(state.report, state.product);
    document.querySelectorAll('.chart-panel .unit-label').forEach((element, index) => { element.textContent = index === 3 ? '双轴' : `单位：${unit}`; });
    renderTrendChart();
    renderCategoryChart();
    renderDriverChart();
    renderVolumeChart();
    renderTables();
    renderWarnings();
  }

  function renderKpis() {
    const report = state.report;
    const row = rowsForProduct(report, state.product).find((item) => item.month === state.month);
    const compare = comparisonFor(report, state.product, state.month);
    const unit = costUnit(report, state.product);
    const quantityUnit = productUnit(report, state.product);
    const kpis = [
      ['当前单位成本', row?.unit_cost, unit, compare ? signed(compare.delta) : '暂无上月对比', compare && compare.delta >= 0 ? 'negative' : 'positive'],
      ['合格产量', row?.good, quantityUnit, compare ? `${pct(compare.volume_rate)} 合格产量变化` : '当前月份', compare && compare.volume_rate < 0 ? 'negative' : 'positive'],
      ['制造成本总额', row?.total, '元', `全期期间费用（不计入）${report.period_expense_total === null || report.period_expense_total === undefined ? '' : ` · ${number(report.period_expense_total)} 元`}`, 'neutral'],
      ['固定费用单耗', row?.fixed_unit, unit, '固定费用分摊', 'neutral'],
    ];
    $('kpi-grid').innerHTML = kpis.map(([label, value, unit, note, tone]) => `<article class="kpi-item"><div class="kpi-label">${esc(label)}</div><div class="kpi-value">${number(value)} <small>${esc(unit)}</small></div><div class="kpi-note ${tone}">${esc(note)}</div></article>`).join('');
    $('filter-state').textContent = `${productName(report, state.product)} · ${state.month || '未选择月份'}`;
  }

  function renderInsights() {
    const compare = comparisonFor(state.report, state.product, state.month);
    const list = compare?.summary?.length ? compare.summary : ['当前月份暂无可对比的上月数据。'];
    $('insight-list').innerHTML = list.slice(0, 4).map((item, index) => `<div class="insight-item"><span class="insight-index">0${index + 1}</span><span>${esc(item)}</span></div>`).join('');
  }

  function svgFrame(title, body) {
    return `<svg class="data-chart" viewBox="0 0 760 270" role="img" aria-label="${esc(title)}"><title>${esc(title)}</title>${body}</svg>`;
  }
  function chartLegend(items) {
    if (!items.length) return '';
    return `<div class="chart-legend" aria-label="图表图例">${items.map((item) => `<span class="legend-item" title="${esc(item.label)}"><i style="--legend-color:${esc(item.color)}"></i>${esc(item.label)}</span>`).join('')}</div>`;
  }
  function gridLines(width, height, left, top, right, bottom, max, ticks = 4, formatter = (v) => number(v, 0)) {
    const plotH = height - top - bottom;
    const plotW = width - left - right;
    return Array.from({ length: ticks + 1 }, (_, index) => {
      const value = max * (1 - index / ticks);
      const y = top + (plotH * index / ticks);
      return `<line class="chart-grid-line" x1="${left}" y1="${y}" x2="${left + plotW}" y2="${y}"/><text class="chart-axis-label" x="${left - 10}" y="${y + 4}" text-anchor="end">${esc(formatter(value))}</text>`;
    }).join('');
  }

  function renderTrendChart() {
    const report = state.report;
    const months = calendarMonths(report);
    const unit = costUnit(report, state.product);
    const quantityUnit = productUnit(report, state.product);
    const products = (report.products || []).filter((product) => (product.unit || '单位') === quantityUnit);
    const left = 58, top = 18, right = 20, bottom = 38, width = 760, height = 270;
    const productCodes = new Set(products.map((product) => product.code));
    const values = report.monthly.filter((row) => productCodes.has(row.product)).map((row) => rawNumber(row.unit_cost)).filter((v) => v !== null);
    const max = Math.max(1, ...(values.length ? values : [1])) * 1.12;
    const x = (index) => left + (months.length < 2 ? 0 : index * (width - left - right) / (months.length - 1));
    const y = (value) => top + (height - top - bottom) * (1 - value / max);
    let body = gridLines(width, height, left, top, right, bottom, max, 4, (v) => number(v, 1));
    products.forEach((product, productIndex) => {
      const rows = months.map((month) => report.monthly.find((item) => item.product === product.code && item.month === month));
      const points = rows.map((row, index) => row && rawNumber(row.unit_cost) !== null ? `${x(index)},${y(Number(row.unit_cost))}` : '');
      if (!points.some(Boolean)) return;
      const active = product.code === state.product;
      const color = `var(--chart-${(productIndex % 5) + 1})`;
      lineSegments(points).forEach((segment) => { body += `<polyline class="trend-line${active ? ' active' : ''}" stroke="${color}" points="${segment.join(' ')}"/>`; });
      rows.forEach((row, index) => { if (row && rawNumber(row.unit_cost) !== null) body += `<circle class="trend-point${active ? ' active' : ''}" cx="${x(index)}" cy="${y(Number(row.unit_cost))}" r="${active ? 4 : 3}" fill="${color}"><title>${esc(product.name)} · ${esc(months[index])}：${number(row.unit_cost)} ${esc(unit)}</title></circle>`; });
    });
    months.forEach((month, index) => { if (axisVisible(index, months.length)) body += `<text class="chart-axis-label" x="${x(index)}" y="${height - 12}" text-anchor="middle">${esc(month.slice(0, 7))}</text>`; });
    const legend = products.map((p, i) => ({ label: p.name || p.code, color: `var(--chart-${(i % 5) + 1})` }));
    $('trend-chart').innerHTML = `${svgFrame(`各产品单位成本趋势（单位：${unit}；仅显示同单位产品）`, body)}${chartLegend(legend)}`;
  }

  function renderCategoryChart() {
    const report = state.report;
    const months = calendarMonths(report);
    const rows = months.map((month) => report.monthly.find((item) => item.product === state.product && item.month === month));
    const categories = [...new Set(rows.flatMap((row) => Object.keys(row?.categories || {})))];
    const left = 58, top = 18, right = 20, bottom = 38, width = 760, height = 270;
    const max = Math.max(1, ...rows.flatMap((row) => Object.values(row?.categories || {}).map(rawNumber).filter((value) => value !== null))) * 1.15;
    const x = (index) => left + (months.length < 2 ? 0 : index * (width - left - right) / (months.length - 1));
    const y = (value) => top + (height - top - bottom) * (1 - value / max);
    let body = gridLines(width, height, left, top, right, bottom, max, 4, (v) => number(v, 1));
    categories.forEach((category, categoryIndex) => {
      const points = rows.map((row, index) => row ? `${x(index)},${y(Number(row.categories[category] ?? 0))}` : '');
      if (!points.some(Boolean)) return;
      const color = `var(--chart-${(categoryIndex % 5) + 1})`;
      lineSegments(points).forEach((segment) => { body += `<polyline class="trend-line" stroke="${color}" points="${segment.join(' ')}"/>`; });
      rows.forEach((row, index) => { if (row) body += `<circle class="trend-point" cx="${x(index)}" cy="${y(Number(row.categories[category] ?? 0))}" r="3" fill="${color}"><title>${esc(category)} · ${esc(months[index])}：${number(row.categories[category] ?? 0)} ${esc(costUnit(report, state.product))}</title></circle>`; });
    });
    months.forEach((month, index) => { if (axisVisible(index, months.length)) body += `<text class="chart-axis-label" x="${x(index)}" y="${height - 12}" text-anchor="middle">${esc(month.slice(0, 7))}</text>`; });
    const legend = categories.map((category, i) => ({ label: category, color: `var(--chart-${(i % 5) + 1})` }));
    $('category-chart').innerHTML = `${svgFrame(`${productName(report, state.product)}科目趋势（单位：${costUnit(report, state.product)}）`, body)}${chartLegend(legend)}`;
  }

  function renderDriverChart() {
    const compare = comparisonFor(state.report, state.product, state.month);
    const drivers = Object.entries(compare?.drivers || {}).filter(([, value]) => finite(value) !== null).sort((a, b) => Math.abs(finite(b[1])) - Math.abs(finite(a[1])));
    const width = 760, height = 270, left = 152, right = 32, center = left + (width - left - right) / 2, rowHeight = 30;
    const max = Math.max(0.1, ...drivers.map(([, value]) => Math.abs(Number(value)))) * 1.18;
    let body = `<line class="chart-zero" x1="${center}" y1="20" x2="${center}" y2="${24 + drivers.length * rowHeight}"/><text class="chart-axis-label" x="${center}" y="16" text-anchor="middle">0</text>`;
    drivers.forEach(([label, value], index) => {
      const amount = finite(value); const y = 30 + index * rowHeight; const bar = Math.abs(amount) / max * ((width - left - right) / 2 - 12); const x = amount >= 0 ? center : center - bar;
      body += `<text class="chart-category-label" x="${left - 10}" y="${y + 5}" text-anchor="end">${esc(label)}</text><rect class="driver-bar ${amount >= 0 ? 'positive' : 'negative'}" x="${x}" y="${y - 10}" width="${bar}" height="18" rx="2"><title>${esc(label)}：${signed(amount)} ${esc(costUnit(state.report, state.product))}</title></rect><text class="driver-value" x="${amount >= 0 ? x + bar + 7 : x - 7}" y="${y + 5}" text-anchor="${amount >= 0 ? 'start' : 'end'}">${signed(amount)}</text>`;
    });
    if (!drivers.length) body += `<text class="chart-empty" x="380" y="138" text-anchor="middle">当前月份暂无上月驱动数据</text>`;
    $('driver-chart').innerHTML = svgFrame('成本变化驱动贡献', body);
  }

  function renderVolumeChart() {
    const report = state.report;
    const months = calendarMonths(report);
    const rows = months.map((month) => report.monthly.find((item) => item.product === state.product && item.month === month));
    const width = 760, height = 270, left = 52, right = 54, top = 18, bottom = 38;
    const volumeValues = rows.map((r) => rawNumber(r?.good)).filter((v) => v !== null); const fixedValues = rows.map((r) => rawNumber(r?.fixed_unit)).filter((v) => v !== null);
    const volumeMax = Math.max(1, ...volumeValues) * 1.14; const fixedMax = Math.max(1, ...fixedValues) * 1.14;
    const x = (index) => left + (months.length < 2 ? 0 : index * (width - left - right) / (months.length - 1));
    const y = (value, max) => top + (height - top - bottom) * (1 - value / max);
    let body = gridLines(width, height, left, top, right, bottom, volumeMax, 4, (v) => number(v, 0));
    body += Array.from({ length: 5 }, (_, index) => { const value = fixedMax * (1 - index / 4); const axisY = top + (height - top - bottom) * index / 4; return `<text class="chart-axis-label" x="${width - right + 8}" y="${axisY + 4}">${number(value, 1)}</text>`; }).join('');
    const volumePoints = rows.map((row, index) => rawNumber(row?.good) !== null ? `${x(index)},${y(Number(row.good), volumeMax)}` : '');
    const fixedPoints = rows.map((row, index) => rawNumber(row?.fixed_unit) !== null ? `${x(index)},${y(Number(row.fixed_unit), fixedMax)}` : '');
    if (volumePoints.some(Boolean)) { lineSegments(volumePoints).forEach((segment) => { body += `<polyline class="volume-line" points="${segment.join(' ')}"/>`; }); rows.forEach((r, i) => { if (rawNumber(r?.good) !== null) body += `<circle class="volume-point" cx="${x(i)}" cy="${y(Number(r.good), volumeMax)}" r="4"><title>${esc(months[i])} 合格产量：${number(r.good, 0)} ${esc(productUnit(report, state.product))}</title></circle>`; }); }
    if (fixedPoints.some(Boolean)) { lineSegments(fixedPoints).forEach((segment) => { body += `<polyline class="fixed-line" points="${segment.join(' ')}"/>`; }); rows.forEach((r, i) => { if (rawNumber(r?.fixed_unit) !== null) body += `<circle class="fixed-point" cx="${x(i)}" cy="${y(Number(r.fixed_unit), fixedMax)}" r="4"><title>${esc(months[i])} 固定费用单耗：${number(r.fixed_unit)} ${esc(costUnit(report, state.product))}</title></circle>`; }); }
    months.forEach((month, index) => { if (axisVisible(index, months.length)) body += `<text class="chart-axis-label" x="${x(index)}" y="${height - 12}" text-anchor="middle">${esc(month.slice(0, 7))}</text>`; });
    const legend = [{ label: `合格产量（${productUnit(report, state.product)}）`, color: 'var(--chart-2)' }, { label: `固定费用单耗（${costUnit(report, state.product)}）`, color: 'var(--chart-4)' }];
    $('volume-chart').innerHTML = `${svgFrame(`${productName(report, state.product)}产量与固定费用（双轴）`, body)}${chartLegend(legend)}`;
  }

  function renderTables() {
    const compare = comparisonFor(state.report, state.product, state.month);
    const current = rowsForProduct(state.report, state.product).find((row) => row.month === state.month);
    const categories = Object.keys(compare?.categories || current?.categories || {});
    $('category-table-body').innerHTML = categories.length ? categories.map((category) => {
      const delta = rawNumber(compare?.categories?.[category]); const currentValue = current ? rawNumber(current.categories?.[category] ?? 0) : null; const previousValue = delta !== null && currentValue !== null ? currentValue - delta : null;
      return `<tr><td>${esc(category)}</td><td class="num">${number(previousValue)}</td><td class="num">${number(currentValue)}</td><td class="num ${delta !== null && delta > 0 ? 'negative' : 'positive'}">${signed(delta)}</td></tr>`;
    }).join('') : `<tr><td colspan="4" class="table-empty">暂无科目数据</td></tr>`;
    const subjects = compare?.subjects || [];
    $('subject-table-body').innerHTML = subjects.length ? subjects.map((item) => { const delta = finite(item.delta); const direction = delta === null ? '暂无方向' : (delta > 0 ? '增加' : '减少'); return `<tr><td>${esc(item.subject)}</td><td class="num ${delta !== null && delta > 0 ? 'negative' : 'positive'}">${signed(delta)}</td><td><span class="direction-tag ${delta !== null && delta > 0 ? 'up' : 'down'}">${direction}</span></td></tr>`; }).join('') : `<tr><td colspan="3" class="table-empty">暂无科目贡献</td></tr>`;
  }

  function renderWarnings() {
    const warnings = state.report.warnings || [];
    $('warning-list').innerHTML = warnings.length ? warnings.map((warning) => `<div class="warning-item"><span class="warning-symbol" aria-hidden="true">!</span><span>${esc(warning)}</span></div>`).join('') : `<div class="warning-item ok"><span class="warning-symbol" aria-hidden="true">✓</span><span>未发现需要关注的数据质量提醒。</span></div>`;
  }

  async function analyzeFile(file) {
    if (!file) return;
    if (!beginRun(`正在分析 ${file.name}…`)) return;
    if (file.size > MAX_FILE_BYTES) {
      showError(`文件大小为 ${number(file.size / 1024 / 1024, 1)} MB，超过 10 MB 限制。`);
      setBusy(false);
      $('file-input').value = '';
      return;
    }
    try {
      const response = await fetch('/api/analyze', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream', 'X-Filename': encodeURIComponent(file.name) }, body: file });
      await loadReport(await readJson(response));
    } catch (error) { showError(error.message || '上传失败，请检查文件后重试。'); }
    finally { setBusy(false); if ($('drop-hint')) $('drop-hint').textContent = '建议使用原始明细文件，不超过 10 MB'; $('file-input').value = ''; }
  }

  async function loadSample() {
    if (!beginRun('正在加载示例分析…')) return;
    try { await loadReport(await readJson(await fetch('/api/sample'))); }
    catch (error) { showError(error.message || '示例加载失败，请稍后重试。'); }
    finally { setBusy(false); }
  }

  async function restoreLastReport() {
    if (window.COST_REPORT) {
      state.product = window.COST_SELECTION?.product || '';
      state.month = window.COST_SELECTION?.month || '';
      await loadReport(window.COST_REPORT, { offline: true, persist: false });
      return;
    }
    let saved; try { saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null'); } catch { saved = null; }
    if (!saved?.id) return;
    try {
      const report = await readJson(await fetch(`/api/result/${encodeURIComponent(saved.id)}`));
      state.product = saved.product || ''; state.month = saved.month || ''; await loadReport(report);
    } catch { localStorage.removeItem(STORAGE_KEY); }
  }

  function bind() {
    $('sample-button').addEventListener('click', loadSample);
    $('template-button').addEventListener('click', () => { window.location.href = '/api/template'; });
    $('sample-file-button').addEventListener('click', () => { window.location.href = '/api/sample-file'; });
    $('error-dismiss').addEventListener('click', hideError);
    $('file-input').addEventListener('change', (event) => analyzeFile(event.target.files?.[0]));
    const drop = $('drop-zone');
    ['dragenter', 'dragover'].forEach((eventName) => drop.addEventListener(eventName, (event) => { event.preventDefault(); drop.classList.add('is-dragging'); }));
    ['dragleave', 'drop'].forEach((eventName) => drop.addEventListener(eventName, (event) => { event.preventDefault(); drop.classList.remove('is-dragging'); }));
    drop.addEventListener('drop', (event) => analyzeFile(event.dataTransfer.files?.[0]));
    drop.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); $('file-input').click(); } });
    $('product-select').addEventListener('change', (event) => { state.product = event.target.value; const months = monthsForProduct(state.report, state.product); state.month = months.includes(state.month) ? state.month : (months[months.length - 1] || ''); renderSelectors(); updateSelection(); });
    $('month-select').addEventListener('change', (event) => { state.month = event.target.value; updateSelection(); });
  }

  function updateSelection() {
    if (!state.offline && state.report?.id) saveSelection();
    renderAll();
  }

  function saveSelection() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ id: state.report.id, product: state.product, month: state.month })); } catch { /* storage may be disabled in private/offline contexts */ }
  }

  bind();
  restoreLastReport().catch((error) => showError(error.message || '报告读取失败。'));
})();
