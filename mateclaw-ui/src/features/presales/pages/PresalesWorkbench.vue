<template>
  <main class="presales-workbench" v-loading="loading">
    <header class="page-heading">
      <div><el-button v-if="projectId" link type="primary" class="navigation-link" @click="router.push('/presales')">← {{ l('项目列表', 'Projects') }}</el-button><h1>{{ project?.name || l('售前工作台', 'Presales workbench') }}</h1></div>
      <el-button v-if="!projectId" type="primary" :disabled="!canWrite" @click="openEditor('project')">{{ l('新建项目', 'New project') }}</el-button>
      <el-button v-else :disabled="!canWrite" @click="openEditor('project', project)">{{ l('编辑档案', 'Edit project') }}</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><template #default><p v-if="conflict">{{ l('记录已被更新。当前编辑内容已保留；请关闭编辑后刷新并核对新版本，再重新提交。', 'The record changed. Your edits are retained. Close the editor, refresh and compare the latest version before resubmitting.') }}</p><el-button type="primary" size="small" @click="load">{{ l('重新加载', 'Reload') }}</el-button></template></el-alert>
    <el-alert v-if="capabilities && !capabilities.enabled" type="info" :closable="false" :title="l('售前功能未启用。历史数据不会被清空。', 'Presales is disabled. Existing data is retained.')" />
    <template v-if="capabilities?.enabled">
      <el-alert v-if="!capabilities.canWrite" type="info" :closable="false" :title="l('只读访问：您可以查看记录，不能修改或批准。', 'Read-only access: editing and approval are unavailable.')" />
      <el-alert v-if="!capabilities.semanticEnabled" type="warning" :closable="false" :title="l('事实审核未启用：需求确认、发布审批与发布暂不可用。', 'Fact review is disabled: requirement confirmation and release approval are unavailable.')" />
      <template v-if="!projectId">
        <PresalesDashboard :projects="portfolio" :loading="portfolioLoading" :error="portfolioError" :selected-stage="statusFilter" @refresh="refreshPortfolio" @stage="filterStage" @open="id => router.push(`/presales/${id}`)" />
        <div class="ledger-heading"><h2>{{ l('项目台账', 'Project register') }} <span>{{ total }}</span></h2></div>
        <form class="toolbar" @submit.prevent="search"><el-input v-model="query" clearable :aria-label="l('搜索项目或客户', 'Search projects or customers')" :placeholder="l('搜索项目或客户', 'Search projects or customers')" /><el-select v-model="ownerFilter" filterable clearable :loading="membersLoading" :aria-label="l('负责人', 'Owner')" :placeholder="l('全部负责人', 'All owners')"><el-option v-for="member in members" :key="member.userId" :value="member.userId" :label="memberName(member)" /></el-select><el-select v-model="statusFilter" clearable :aria-label="l('阶段', 'Stage')" :placeholder="l('全部阶段', 'All stages')"><el-option v-for="status in stages" :key="status" :value="status" :label="stateLabel(status)" /></el-select><el-button native-type="submit">{{ l('查询', 'Search') }}</el-button></form>
        <el-table class="project-ledger" :data="projects" @row-dblclick="row => router.push(`/presales/${row.id}`)">
          <el-table-column :label="l('项目', 'Project')" min-width="210"><template #default="{ row }"><el-button link type="primary" class="navigation-link" @click="router.push(`/presales/${row.id}`)">{{ row.name }}</el-button></template></el-table-column>
          <el-table-column prop="customer" :label="l('客户', 'Customer')" min-width="160" /><el-table-column :label="l('负责人', 'Owner')" min-width="150"><template #default="{ row }"><span>{{ ownerName(row.ownerId) }}</span></template></el-table-column><el-table-column :label="l('阶段', 'Stage')" min-width="130"><template #default="{ row }"><span class="stage-label" :data-stage="row.stage">{{ stateLabel(row.stage || row.status) }}</span></template></el-table-column><el-table-column :label="l('方案版本', 'Solution version')" width="110"><template #default="{ row }">{{ row.latestSolutionVersion ? `V${row.latestSolutionVersion}` : l('待编制', 'Not started') }}</template></el-table-column><el-table-column :label="l('待澄清', 'Open questions')" width="110"><template #default="{ row }">{{ row.openClarificationCount ?? '—' }}</template></el-table-column><el-table-column :label="l('最近活动', 'Updated')" min-width="170"><template #default="{ row }">{{ displayDate(row.updatedAt) }}</template></el-table-column>
          <template #empty>{{ error ? l('加载失败，请重试', 'Loading failed. Retry.') : l('暂无项目。创建项目开始协作。', 'No projects. Create a project to start.') }}</template>
        </el-table>
        <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </template>
      <template v-else-if="project">
        <div class="project-meta"><el-tag>{{ stateLabel(project.stage || project.status) }}</el-tag><span>{{ project.customer }}</span><span>{{ l('负责人', 'Owner') }}: {{ ownerName(project.ownerId) }}</span><span>{{ l('记录版本', 'Record version') }} {{ project.version }}</span><el-button plain size="small" type="danger" :disabled="!canWrite" @click="archive">{{ l('归档', 'Archive') }}</el-button></div>
        <el-alert v-if="project.status === 'ARCHIVED'" type="info" :closable="false" :title="l('项目已归档，当前为只读。', 'This project is archived and read-only.')" />
        <section class="employee-owner"><div><span class="muted">{{ l('负责此项目的数字员工', 'Assigned digital employee') }}</span><h2>{{ project.agentName || employees.find(e => e.id === project?.agentId)?.name || (project.agentId ? l('员工待核验', 'Employee pending verification') : l('尚未绑定售前员工', 'No presales employee assigned')) }}</h2></div><div><el-button :disabled="!canWrite" @click="openEditor('project', project)">{{ l('配置负责员工', 'Assign employee') }}</el-button><el-button type="primary" :disabled="!canWrite || !project.agentId" @click="openGeneration('S1')">{{ l('交给员工执行', 'Delegate to employee') }}</el-button></div></section>
        <nav class="project-pulse" :aria-label="l('项目统计', 'Project statistics')"><button v-for="item in projectMetrics" :key="item.tab" type="button" @click="tab = item.tab"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small v-if="item.secondary">{{ item.secondary }}</small></button></nav>
        <el-tabs v-model="tab">
          <el-tab-pane :label="l('项目概览', 'Overview')" name="overview">
            <section class="section-heading"><h2>{{ l('项目理解与未决事项', 'Context and open items') }}</h2><el-button :disabled="!canWrite" @click="openGeneration('S1')">{{ l('让员工分析项目', 'Ask employee to analyze') }}</el-button></section>
            <el-descriptions :column="isNarrow ? 1 : 2" border><el-descriptions-item :label="l('客户', 'Customer')">{{ project.customer }}</el-descriptions-item><el-descriptions-item :label="l('目标', 'Goal')">{{ project.goal || '—' }}</el-descriptions-item><el-descriptions-item :label="l('行业', 'Industry')">{{ project.industry || '—' }}</el-descriptions-item><el-descriptions-item :label="l('本轮需求确认', 'Requirement confirmation')"><details class="baseline-details"><summary>{{ project.baselines.at(-1)?.id ? l('已确认', 'Confirmed') : l('尚未确认', 'Not confirmed') }}</summary><p v-if="project.baselines.at(-1)?.id" class="muted">{{ project.baselines.at(-1)?.id }}</p></details></el-descriptions-item></el-descriptions>
            <template v-if="project.contextCards?.length || project.context"><h3>{{ l('Context 卡', 'Context card') }}</h3><pre class="safe-content">{{ printable(project.contextCards?.at(-1) || project.context) }}</pre></template><el-empty v-else :description="l('尚未生成项目理解。', 'No project context yet.')" />
            <h3>{{ l('员工执行记录', 'Employee activity') }}</h3>
            <article v-for="task in project.tasks || []" :key="task.id" class="solution-revision"><div class="task-heading"><strong>{{ skillNames[task.skill as keyof typeof skillNames] || task.skill }} · {{ stateLabel(task.status) }}</strong><div class="task-actions"><el-button v-if="task.status === 'RUNNING'" :disabled="!canWrite" @click="cancelTask(task)">{{ l('停止接收本次结果', 'Discard this run result') }}</el-button><el-button v-if="task.conversationId && task.agentId" type="primary" size="small" @click="router.push({ path: '/chat', query: { conversationId: task.conversationId, agentId: task.agentId } })">{{ l('查看执行过程', 'View employee execution') }}</el-button><el-button type="primary" size="small" @click="showEvidence(task.contextSnapshot || task)">{{ l('输入快照', 'Input snapshot') }}</el-button></div></div><el-alert v-if="task.contextSnapshot?.truncated" type="warning" :closable="false" :title="l('上下文已截断，关键依据可能不完整。', 'Context truncated; important evidence may be missing.')" /><p class="muted">{{ task.agentName || l('历史生成记录', 'Legacy generation') }}<span v-if="task.conversationId"> · {{ l('执行记录', 'Execution') }} {{ task.runId }}</span></p><p v-if="task.queueState === 'QUEUED' && task.status === 'RUNNING'" class="muted">{{ l('已受理，等待员工执行。', 'Accepted and waiting for the employee.') }}</p><p v-if="task.error">{{ employeeIssue(task.error) }}</p><div v-for="(item, index) in task.result?.items || []" :key="index"><h4>{{ item.title }} · {{ stateLabel(item.originKind) }}</h4><pre class="safe-content">{{ item.text }}</pre><el-button v-if="['S1', 'S5', 'S6'].includes(task.skill)" :disabled="!canWrite" @click="adopt(task.skill, item)">{{ l('查看并修订建议', 'Review proposal') }}</el-button></div><div v-if="task.result?.solution?.presentation" class="presentation-result"><strong>{{ l('成果草稿', 'Output draft') }} · ppt-master-plus</strong><span class="muted">{{ task.result.solution.presentation.skill }} · {{ task.result.solution.presentation.skillVersion }} · {{ task.result.solution.presentation.pageCount }} {{ l('页', 'pages') }}</span><div class="task-actions"><el-button v-for="slide in task.result.solution.presentation.slides || []" :key="slide.filename" type="primary" size="small" @click="previewPresentation(task.result.solution.presentation, slide.filename)">{{ l('预览', 'Preview') }} {{ slide.title || slide.filename }}</el-button></div></div><pre v-if="task.result" class="safe-content">{{ printable({ unknowns: task.result.unknowns, assumptions: task.result.assumptions }) }}</pre></article><el-table :data="project.tasks || []"><el-table-column :label="l('工作内容', 'Work')"><template #default="{ row }">{{ skillNames[row.skill as keyof typeof skillNames] || l('历史任务', 'Previous task') }}</template></el-table-column><el-table-column prop="agentName" :label="l('执行员工', 'Employee')" /><el-table-column :label="l('状态', 'Status')" ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column><el-table-column prop="error" :label="l('失败原因', 'Failure reason')" /></el-table>
          </el-tab-pane>
          <el-tab-pane :label="l('资料与证据', 'Materials & evidence')" name="materials">
            <section class="section-heading"><h2>{{ l('授权来源', 'Authorized sources') }}</h2><el-button :disabled="!canWrite" @click="openEditor('material')">{{ l('绑定资料', 'Bind material') }}</el-button></section>
            <el-table :data="project.materials"><el-table-column :label="l('用途', 'Role')" ><template #default="{ row }">{{ stateLabel(row.role) }}</template></el-table-column><el-table-column prop="kbId" :label="l('知识库', 'Knowledge base')" min-width="180" /><el-table-column prop="graphId" :label="l('语义图', 'Graph')" min-width="180" /><el-table-column :label="l('状态', 'Status')" ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column><el-table-column :label="l('操作', 'Actions')" min-width="224"><template #default="{ row }"><div class="table-actions"><el-button type="primary" size="small" @click="showEvidence(row)">{{ l('查看来源', 'View source') }}</el-button><el-button plain size="small" type="danger" :disabled="!canWrite || row.status === 'WITHDRAWN'" @click="command('UNBIND_MATERIAL', { id: row.id })">{{ l('撤回', 'Withdraw') }}</el-button></div></template></el-table-column></el-table>
          </el-tab-pane>
          <el-tab-pane :label="l('需求与澄清', 'Requirements & questions')" name="requirements">
            <section class="section-heading"><h2>{{ l('需求矩阵', 'Requirements') }}</h2><div><el-button :disabled="!canWrite" @click="openGeneration('S2')">{{ l('让员工梳理需求', 'Ask employee to assess requirements') }}</el-button><el-button type="primary" :disabled="!canApprove" @click="openEditor('baseline')">{{ l('确认本轮需求', 'Confirm requirements') }}</el-button></div></section>
            <details class="inline-help"><summary>{{ l('确认流程说明', 'About confirmation steps') }}</summary><p>{{ l('事实审核、本轮需求确认和客户确认是三个独立步骤。请先核对数字员工整理的需求与依据，再确认本轮范围。', 'Fact review, internal requirement confirmation and customer confirmation are separate. Review employee findings and evidence before confirming scope.') }}</p></details>
            <el-table :data="project.requirements"><el-table-column prop="title" :label="l('需求', 'Requirement')" min-width="240" /><el-table-column :label="l('来源性质', 'Origin')" min-width="130" ><template #default="{ row }">{{ stateLabel(row.originKind) }}</template></el-table-column><el-table-column :label="l('优先级', 'Priority')" ><template #default="{ row }">{{ stateLabel(row.priority) }}</template></el-table-column><el-table-column :label="l('范围', 'Scope')" ><template #default="{ row }">{{ stateLabel(row.scope) }}</template></el-table-column><el-table-column :label="l('客户确认', 'Customer confirmation')" min-width="160" ><template #default="{ row }">{{ stateLabel(row.customerConfirmationStatus) }}</template></el-table-column><el-table-column :label="l('操作', 'Actions')" min-width="224"><template #default="{ row }"><div class="table-actions"><el-button type="primary" size="small" @click="showEvidence(row)">{{ l('证据', 'Evidence') }}</el-button><el-button type="primary" size="small" :disabled="!canWrite" @click="openEditor('requirement', row)">{{ l('修订', 'Revise') }}</el-button></div></template></el-table-column></el-table>
            <section class="section-heading"><h2>{{ l('员工待补充信息', 'Information requested by employee') }}</h2><el-button :disabled="!canWrite || !project.agentId" @click="continueEmployee">{{ l('让员工继续处理', 'Continue employee work') }}</el-button></section>
            <div class="toolbar"><el-radio-group v-model="clarificationFilter" :aria-label="l('澄清状态筛选', 'Filter clarification status')"><el-radio-button value="ALL">{{ l('全部', 'All') }} {{ project.clarifications.length }}</el-radio-button><el-radio-button value="OPEN">{{ l('待处理', 'Open') }} {{ project.clarifications.filter(c => c.status !== 'ANSWERED').length }}</el-radio-button><el-radio-button value="ANSWERED">{{ l('已答复', 'Answered') }} {{ project.clarifications.filter(c => c.status === 'ANSWERED').length }}</el-radio-button></el-radio-group></div>
            <el-table :data="filteredClarifications"><el-table-column prop="question" :label="l('问题', 'Question')" min-width="240" /><el-table-column :label="l('关联需求 / 影响', 'Requirement / impact')" min-width="180"><template #default="{ row }"><span>{{ project.requirements.find(r => r.id === row.requirementId)?.title || l('项目整体', 'Project-wide') }}</span><div class="muted">{{ row.impact || '—' }}</div></template></el-table-column><el-table-column :label="l('负责人', 'Owner')"><template #default="{ row }">{{ ownerName(row.ownerId) }}</template></el-table-column><el-table-column :label="l('答复与出处', 'Answer and source')" min-width="230"><template #default="{ row }"><p class="safe-content">{{ row.answer || '—' }}</p><div class="muted">{{ row.answerSourceId || l('尚未记录出处', 'No source recorded') }}</div></template></el-table-column><el-table-column :label="l('状态', 'Status')" ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column><el-table-column :label="l('操作', 'Actions')" min-width="224"><template #default="{ row }"><div class="table-actions"><el-button type="primary" size="small" @click="showEvidence(row)">{{ l('查看记录', 'View record') }}</el-button><el-button type="primary" size="small" :disabled="!canWrite" @click="openEditor('clarification', row)">{{ row.status === 'ANSWERED' ? l('修订答复', 'Revise answer') : l('补充信息', 'Provide information') }}</el-button></div></template></el-table-column></el-table>
            <h3>{{ l('需求确认记录', 'Requirement confirmation history') }}</h3><el-table :data="project.baselines"><el-table-column prop="id" label="ID" /><el-table-column prop="reason" :label="l('批准依据', 'Decision reason')" /><el-table-column prop="createdAt" :label="l('创建时间', 'Created')" /></el-table>
          </el-tab-pane>
          <el-tab-pane :label="l('能力与案例', 'Capabilities & cases')" name="fitgap">
            <section class="section-heading"><h2>{{ l('需求与能力匹配', 'Requirement capability matching') }}</h2><div><el-button :disabled="!canWrite" @click="openGeneration('S3')">{{ l('匹配能力', 'Match capabilities') }}</el-button></div></section>
            <details class="inline-help"><summary>{{ l('能力判断说明', 'About capability assessment') }}</summary><p>{{ l('缺少依据时标记为待核实。案例相似不等于当前产品可交付。', 'Mark as unverified when evidence is missing. A similar case does not prove current deliverability.') }}</p></details>
            <el-table :data="project.fitGaps"><el-table-column prop="requirementId" :label="l('需求引用', 'Requirement')" min-width="190" /><el-table-column :label="l('满足方式', 'Fulfillment approach')"><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column><el-table-column prop="productVersion" :label="l('产品版本', 'Product version')" /><el-table-column prop="reason" :label="l('依据 / 缺口', 'Basis / gap')" min-width="250" /><el-table-column :label="l('操作', 'Actions')" min-width="224"><template #default="{ row }"><div class="table-actions"><el-button type="primary" size="small" @click="showEvidence(row)">{{ l('证据', 'Evidence') }}</el-button></div></template></el-table-column></el-table>
          </el-tab-pane>
          <el-tab-pane :label="l('方案设计', 'Solution design')" name="solution">
            <section class="section-heading"><h2>{{ l('版本化方案', 'Versioned solutions') }}</h2><div><el-button :disabled="!canWrite" @click="openGeneration('S5')">{{ l('让员工编制方案', 'Ask employee to compose') }}</el-button></div></section>
            <section v-if="project.solutions.length" class="coverage"><div class="coverage-heading"><h3>{{ l('需求响应覆盖', 'Requirement coverage') }}</h3><details class="inline-help"><summary>{{ l('查看定义', 'Definition') }}</summary><p>{{ l('章节引用表示方案已关联需求，不代表当前产品一定可交付。', 'A section reference links a requirement to the solution; it does not by itself prove deliverability.') }}</p></details></div><div class="coverage-stats"><div><span>{{ l('需求响应', 'Requirement response') }}</span><strong>{{ project.solutions.at(-1)?.coverage?.applicable ? `${project.solutions.at(-1)?.coverage.handledIn}/${project.solutions.at(-1)?.coverage.totalIn}` : l('不适用', 'Not applicable') }}</strong></div><div><span>{{ l('章节引用覆盖', 'Section references') }}</span><strong>{{ coverage }}</strong></div></div><el-table :data="responseRows"><el-table-column prop="title" :label="l('需求', 'Requirement')" min-width="200" /><el-table-column :label="l('范围', 'Scope')" ><template #default="{ row }">{{ stateLabel(row.scope) }}</template></el-table-column><el-table-column :label="l('满足方式', 'Fulfillment approach')"><template #default="{ row }">{{ stateLabel(row.fit) }}</template></el-table-column><el-table-column :label="l('响应分类', 'Response')"><template #default="{ row }">{{ stateLabel(row.response) }}</template></el-table-column><el-table-column prop="sections" :label="l('最新方案章节', 'Latest solution sections')" min-width="230" /></el-table></section>
            <div v-if="project.solutions.length > 1" class="toolbar"><el-select v-model="compareId" :placeholder="l('选择历史版本以比较', 'Compare with previous version')"><el-option v-for="solution in project.solutions.slice(0, -1)" :key="solution.id" :value="solution.id" :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select><el-select v-model="compareTargetId" :placeholder="l('比较目标（默认最新）', 'Compare target (latest by default)')"><el-option v-for="solution in project.solutions" :key="solution.id" :value="solution.id" :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></div><el-table v-if="compareId" :data="comparison"><el-table-column prop="title" :label="l('章节', 'Section')" /><el-table-column :label="l('所选历史版本', 'Selected previous version')" min-width="250"><template #default="{ row }"><pre class="safe-content">{{ row.before }}</pre></template></el-table-column><el-table-column :label="l('目标版本', 'Target version')" min-width="250"><template #default="{ row }"><pre class="safe-content">{{ row.after }}</pre></template></el-table-column></el-table>
            <el-empty v-if="!project.solutions.length" :description="l('暂无方案。先确认目录，再编写章节。', 'No solution. Confirm the outline before drafting sections.')" />
            <article v-for="solution in [...project.solutions].reverse()" :key="solution.id" class="solution-revision"><div class="solution-version-header"><div><h3>{{ solution.title }} · {{ versionLabel("solutions", solution.id) }}</h3><el-tag>{{ stateLabel(solution.status || 'DRAFT') }}</el-tag></div><el-button type="primary" size="small" :disabled="!canWrite" @click="openEditor('solution', solution)">{{ l('基于此版本修订', 'Revise this version') }}</el-button></div><div class="solution-downloads"><span>{{ l('下载草稿', 'Download draft') }}</span><el-button v-for="filename in ['solution.md', 'solution.docx', 'solution.pptx']" :key="filename" type="primary" size="small" @click="download(solution.id, filename, 'draft')">{{ filename }}</el-button></div><details class="baseline-details"><summary>{{ l('已确认需求', 'Confirmed requirements') }} · {{ solution.baselineId ? l('查看详情', 'View details') : l('未确认', 'Unconfirmed') }}</summary><p class="muted">{{ solution.baselineId || l('此版本尚未关联已确认需求。', 'This version is not linked to confirmed requirements.') }}</p></details><el-alert v-if="solution.baselineId && solution.baselineId !== project.baselines.at(-1)?.id" type="warning" :closable="false" :title="l('此方案引用旧基线，需要复核。', 'This solution references an older baseline and requires review.')" /><section v-for="(section, index) in solution.sections" :key="index" class="solution-section"><h4>{{ section.title }}</h4><pre class="safe-content">{{ section.text }}</pre><el-button v-if="section.evidenceRefs?.length" type="primary" size="small" @click="showEvidence(section)">{{ l('章节证据', 'Section evidence') }}</el-button></section></article>
          </el-tab-pane>
          <el-tab-pane :label="l('评审与成果', 'Review & outputs')" name="review">
            <section class="section-heading"><h2>{{ l('独立评审', 'Independent review') }}</h2><div><el-button :disabled="!canWrite || !project.solutions.length" @click="openGeneration('S7')">{{ l('让员工检查方案', 'Ask employee to review') }}</el-button><el-button :disabled="!canWrite" @click="openEditor('review')">{{ l('录入独立评审', 'Record independent review') }}</el-button><el-button :disabled="!canWrite" @click="openEditor('release')">{{ l('创建发布候选', 'Create release candidate') }}</el-button></div></section>
            <el-table :data="project.reviews"><el-table-column :label="l('方案版本', 'Solution')"><template #default="{ row }">{{ versionLabel("solutions", row.solutionId) }}</template></el-table-column><el-table-column prop="summary" :label="l('结论', 'Summary')" /><el-table-column :label="l('评审发现', 'Findings')" min-width="300"><template #default="{ row }"><pre class="safe-content">{{ (row.findings || row.issues || []).length ? printable(row.findings || row.issues) : l("无评审问题", "No findings") }}</pre></template></el-table-column></el-table>
            <section class="section-heading"><h3>{{ l('成果版本', 'Release versions') }}</h3><el-button @click="downloadHandoff">{{ l('导出内部交接包', 'Export internal handoff') }}</el-button></section><el-table :data="project.releases"><el-table-column :label="l('版本', 'Version')"><template #default="{ row }">{{ versionLabel("releases", row.id) }}</template></el-table-column><el-table-column :label="l('状态', 'Status')" ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column><el-table-column :label="l('文件', 'Files')" width="90"><template #default="{ row }">{{ row.files?.length || 0 }}</template></el-table-column><el-table-column :label="l('操作', 'Actions')" min-width="224"><template #default="{ row }"><div class="table-actions"><el-button type="primary" size="small" @click="showEvidence(row)">{{ l('查看清单', 'Manifest') }}</el-button><el-button v-for="file in row.files || []" :key="file.filename" type="primary" size="small" :disabled="row.status !== 'PUBLISHED' && !canApprove" @click="download(row.id, file.filename, row.status === 'PUBLISHED' ? 'files' : 'preview')">{{ row.status === 'PUBLISHED' ? l('下载', 'Download') : l('未批准预览', 'Unapproved preview') }} {{ file.filename }}</el-button><el-button type="primary" size="small" :disabled="!canApprove || row.status !== 'PENDING'" @click="approveRelease(row)">{{ l('审批发布', 'Approve release') }}</el-button><el-button type="primary" size="small" :disabled="!canApprove || row.status !== 'APPROVED'" @click="command('PUBLISH_RELEASE', { releaseId: row.id })">{{ l('发布', 'Publish') }}</el-button></div></template></el-table-column></el-table>
            <details class="inline-help"><summary>{{ l('发布规则', 'Release rules') }}</summary><p>{{ l('每个成果版本须经审批后发布；审批针对该版本的具体文件。发布不会自动发送给客户。', 'Release approval covers the exact files. Publishing does not send files to customers.') }}</p></details>
          </el-tab-pane>
        </el-tabs>
      </template>
    </template>
    <el-drawer v-model="evidenceOpen" :title="l('证据与引用链', 'Evidence and references')" size="min(560px, 95vw)"><details class="inline-help"><summary>{{ l('查看展示边界', 'About displayed evidence') }}</summary><p>{{ l('仅展示服务端授权返回的内容；外部图片与脚本不执行。原文与快照以知识库和语义证据记录为准。', 'Only authorized server content is shown; external images and scripts are not executed. Wiki and semantic evidence records are authoritative for source text and snapshots.') }}</p></details><el-descriptions v-if="evidence" :column="1" border><el-descriptions-item v-for="(value, key) in evidence" :key="key" :label="String(key)"><pre class="safe-content">{{ printable(value) }}</pre></el-descriptions-item></el-descriptions></el-drawer><el-dialog v-model="presentationPreview.open" :title="presentationPreview.title" width="min(1100px, 95vw)" @closed="!presentationPreview.open && clearPresentationPreview()"><img v-if="presentationPreview.url" :src="presentationPreview.url" :alt="presentationPreview.title" class="presentation-preview" /></el-dialog>
    <el-dialog v-model="editorOpen" :title="editorTitle" width="min(720px, 95vw)" :close-on-click-modal="false" :before-close="closeEditor">
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="editorKind === 'project'"><el-form-item :label="l('项目名称', 'Project name')" required><el-input v-model="form.name" maxlength="200" /></el-form-item><el-form-item :label="l('客户', 'Customer')" required><el-input v-model="form.customer" maxlength="200" /></el-form-item><el-form-item :label="l('负责人', 'Owner')"><el-select v-model="form.ownerId" filterable :loading="membersLoading" :disabled="membersLoading || !!membersError" :aria-label="l('负责人', 'Owner')" :placeholder="l('选择真实员工', 'Select a workspace member')"><el-option v-if="form.ownerId && !members.some(member => member.userId === form.ownerId)" :value="form.ownerId" :label="ownerName(form.ownerId)" disabled /><el-option v-for="member in members" :key="member.userId" :value="member.userId" :label="memberName(member)" /></el-select><span v-if="membersError" role="alert">{{ membersError }}</span></el-form-item><el-form-item :label="l('售前解决方案员工', 'Presales solution employee')"><el-select v-model="form.agentId" clearable :loading="employeesLoading" :placeholder="l('选择本工作区已配置的数字员工', 'Select a configured workspace employee')"><el-option v-for="employee in employees" :key="employee.id" :value="employee.id" :label="employee.name" :disabled="employee.enabled === false" /></el-select><el-button type="primary" size="small" @click="router.push('/agents')">{{ l('管理员工配置', 'Manage employees') }}</el-button></el-form-item><el-alert v-if="employeeError" :title="employeeError" type="warning" :closable="false" /><p v-if="!employeesLoading && !employees.length" class="muted">{{ l('当前工作区没有可绑定的员工，请先在员工管理中配置并启用售前解决方案员工。', 'No eligible employee in this workspace. Configure and enable a presales employee in employee management.') }}</p><el-form-item :label="l('行业', 'Industry')"><el-input v-model="form.industry" /></el-form-item><el-form-item :label="l('目标', 'Goal')"><el-input v-model="form.goal" type="textarea" :rows="3" /></el-form-item></template>
        <template v-else-if="editorKind === 'material'"><el-form-item :label="l('选择知识库', 'Knowledge base')" required><el-select v-model="form.kbId" filterable :loading="optionsLoading" @change="selectSource"><el-option v-for="source in sourceOptions" :key="source.kbId" :value="source.kbId" :label="source.name" /></el-select></el-form-item><p v-if="form.kbId" class="muted">{{ form.graphId ? l('已关联知识图谱，可用于事实治理。', 'Linked semantic graph is available for governance.') : l('资料尚未关联事实审核；可先管理资料，确认本轮需求前需完成关联。', 'No fact review graph yet. Bind one before confirming requirements.') }}</p><el-button type="primary" size="small" @click="router.push('/wiki')">{{ l('打开知识库上传与解析资料', 'Open Wiki to upload and parse material') }}</el-button><details><summary>{{ l('高级：来源标识', 'Advanced: source identifiers') }}</summary><el-form-item :label="l('知识库 ID', 'Knowledge base ID')"><el-input v-model="form.kbId" /></el-form-item><el-form-item :label="l('语义图 ID（可选）', 'Graph ID (optional)')"><el-input v-model="form.graphId" /></el-form-item></details><el-form-item :label="l('资料用途', 'Source role')"><el-select v-model="form.role"><el-option v-for="role in ['PROJECT', 'PRODUCT', 'CASE']" :key="role" :value="role" :label="stateLabel(role)" /></el-select></el-form-item></template>
        <template v-else-if="editorKind === 'requirement'"><el-form-item :label="l('需求标题', 'Requirement title')" required><el-input v-model="form.title" /></el-form-item><el-form-item :label="l('描述', 'Description')"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item><el-form-item :label="l('来源性质（候选，不代表已确认事实）', 'Origin (draft, not accepted fact)')"><el-select v-model="form.originKind"><el-option v-for="v in ['CUSTOMER_SOURCE', 'PRODUCT_SOURCE', 'INTERNAL_JUDGMENT', 'ASSUMPTION', 'AI_SUGGESTION']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item><div class="form-grid"><el-form-item :label="l('优先级', 'Priority')"><el-select v-model="form.priority"><el-option v-for="v in ['HIGH', 'MEDIUM', 'LOW']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item><el-form-item :label="l('范围', 'Scope')"><el-select v-model="form.scope"><el-option v-for="v in ['IN', 'OUT', 'UNKNOWN']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item></div><el-form-item :label="l('已审核事实（确认本轮需求前须关联）', 'Reviewed fact (required before confirming requirements)')"><el-select :model-value="form.statementId ? `${form.graphId}:${form.statementId}` : ''" filterable clearable :loading="optionsLoading" @change="selectStatement"><el-option v-for="statement in statementOptions" :key="`${statement.graphId}:${statement.id}`" :value="`${statement.graphId}:${statement.id}`" :label="statement.label" /></el-select></el-form-item><p v-if="!statementOptions.length" class="muted">{{ l('尚无可用的已接受事实。先绑定资料，在知识库完成语义提取与审核；当前需求可保存为候选。', 'No accepted statements available. Bind sources and complete semantic extraction and review in Wiki; this requirement can be saved as a draft.') }}</p><details><summary>{{ l('高级：精确修订引用', 'Advanced: exact revision references') }}</summary><el-form-item :label="l('语义图 ID', 'Graph ID')"><el-input v-model="form.graphId" /></el-form-item><el-form-item label="Statement ID"><el-input v-model="form.statementId" /></el-form-item><el-form-item :label="l('Statement 修订', 'Statement revision')"><el-input v-model="form.statementRevision" /></el-form-item></details></template>
        <template v-else-if="editorKind === 'clarification'"><el-alert type="info" :closable="false" :title="form.proposedByTaskId ? l('数字员工需要以下信息才能继续。请填写答复和出处。', 'The employee needs this information to continue. Provide an answer and its source.') : l('这是历史澄清记录。补充信息后可交给负责员工继续处理。', 'This is a previous clarification. Supply information for the assigned employee to continue.')" /><h3>{{ form.question }}</h3><p class="safe-content">{{ form.impact }}</p><p class="muted">{{ project?.requirements.find(r => r.id === form.requirementId)?.title || l('项目整体问题', 'Project-wide question') }}</p><el-form-item :label="l('答复', 'Answer')" :required="form.status === 'ANSWERED'"><el-input v-model="form.answer" type="textarea" /></el-form-item><el-form-item :label="l('答复来源', 'Answer source')" :required="form.status === 'ANSWERED'"><el-input v-model="form.answerSourceId" maxlength="2000" :placeholder="l('例如：客户会议日期、纪要名称与段落，或邮件主题', 'Meeting date, record title and section, or email subject')" /></el-form-item><p class="muted">{{ l('答复将作为员工继续执行的输入，不会自动变成客户确认或已审核事实。', 'Your answer becomes input for the employee, not customer confirmation or an accepted fact.') }}</p><el-form-item :label="l('状态', 'Status')"><el-select v-model="form.status"><el-option v-for="v in ['OPEN', 'ANSWERED']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item></template>
        <template v-else-if="editorKind === 'baseline'"><el-alert type="warning" :closable="false" :title="l('确认本轮方案依据的需求与范围，不代表客户已确认。确认前会检查事实依据及未解决的问题。', 'Confirm the requirements and scope for this solution. This is not customer confirmation. Evidence and unresolved questions are checked first.')" /><el-form-item :label="l('批准理由、条件与责任人', 'Decision reason, conditions and owner')" required><el-input v-model="form.reason" type="textarea" :rows="5" /></el-form-item></template>
        <template v-else-if="editorKind === 'fitgap'"><el-form-item :label="l('需求', 'Requirement')" required><el-select v-model="form.requirementId"><el-option v-for="r in project?.requirements" :key="r.id" :value="r.id" :label="r.title" /></el-select></el-form-item><el-form-item :label="l('满足方式', 'Fulfillment approach')"><el-select v-model="form.status"><el-option v-for="v in ['FIT', 'CONFIG', 'EXTEND', 'PARTNER', 'GAP', 'UNKNOWN']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item><el-form-item :label="l('产品版本', 'Product version')"><el-input v-model="form.productVersion" /></el-form-item><el-form-item :label="l('依据、缺口与处置', 'Basis, gap and response')"><el-input v-model="form.reason" type="textarea" /></el-form-item><el-form-item :label="l('语义图 ID', 'Graph ID')"><el-input v-model="form.graphId" /></el-form-item><el-form-item :label="l('证据 ID（逗号分隔）', 'Evidence IDs (comma separated)')"><el-input v-model="form.evidenceText" /></el-form-item></template>
        <template v-else-if="editorKind === 'solution'"><el-form-item :label="l('方案标题', 'Solution title')" required><el-input v-model="form.title" /></el-form-item><el-form-item :label="l('本轮已确认需求', 'Confirmed requirements')"><el-select v-model="form.baselineId" clearable><el-option v-for="baseline in project?.baselines" :key="baseline.id" :value="baseline.id" :label="baselineLabel(baseline.id)" /></el-select></el-form-item><div v-for="(section, index) in form.sections" :key="index" class="section-editor"><el-form-item :label="`${l('章节', 'Section')} ${Number(index) + 1}`"><el-input v-model="section.title" /></el-form-item><el-form-item :label="l('响应需求', 'Requirement references')"><el-select v-model="section.requirementRefs" multiple><el-option v-for="requirement in editableRequirements" :key="requirement.id" :value="requirement.id" :label="requirement.title" /></el-select></el-form-item><el-form-item :label="l('正文', 'Body')"><el-input v-model="section.text" type="textarea" :rows="6" /></el-form-item></div><el-button @click="form.sections.push({ title: '', text: '', requirementRefs: [] })">{{ l('添加章节', 'Add section') }}</el-button><h3>{{ l('逐项需求响应', 'Requirement responses') }}</h3><section v-for="response in form.requirementResponses" :key="response.requirementId" class="section-editor"><strong>{{ project?.requirements.find(item => item.id === response.requirementId)?.title || response.requirementId }}</strong><el-form-item :label="l('响应分类', 'Response classification')"><el-select v-model="response.status"><el-option v-for="v in ['FULL', 'PARTIAL', 'CONDITIONAL', 'EXCLUDED', 'UNHANDLED']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item><el-form-item :label="l('理由、剩余缺口或条件', 'Reason, remaining gaps or conditions')"><el-input v-model="response.reason" type="textarea" /></el-form-item></section></template>
        <template v-else-if="editorKind === 'context'"><el-form-item :label="l('标题', 'Title')" required><el-input v-model="form.title" /></el-form-item><el-form-item :label="l('项目理解（未接受事实）', 'Context (not accepted facts)')" required><el-input v-model="form.text" type="textarea" :rows="8" /></el-form-item><p>{{ stateLabel(form.originKind) }}</p></template>
        <template v-else-if="editorKind === 'review'"><details class="inline-help"><summary>{{ l('评审规则', 'Review rules') }}</summary><p>{{ l('评审者须独立于方案作者。评审记录不会自动批准或修改方案。', 'The reviewer must differ from the solution author. A review does not approve or modify the solution.') }}</p></details><el-form-item :label="l('精确方案版本', 'Exact solution version')" required><el-select v-model="form.solutionId"><el-option v-for="solution in project?.solutions" :key="solution.id" :value="solution.id" :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></el-form-item><el-form-item :label="l('评审结论', 'Review summary')" required><el-input v-model="form.summary" type="textarea" :rows="3" /></el-form-item><section v-for="(issue, index) in form.issues" :key="index" class="section-editor"><el-form-item :label="l('问题与整改', 'Issue and remediation')"><el-input v-model="issue.description" type="textarea" /></el-form-item><div class="form-grid"><el-form-item :label="l('严重性', 'Severity')"><el-select v-model="issue.severity"><el-option v-for="v in ['BLOCKER', 'WARNING', 'INFO']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item><el-form-item :label="l('处理状态', 'Disposition')"><el-select v-model="issue.status"><el-option v-for="v in ['OPEN', 'RESOLVED', 'ACCEPTED']" :key="v" :value="v" :label="stateLabel(v)" /></el-select></el-form-item></div></section><el-button @click="form.issues.push({ description: '', severity: 'WARNING', status: 'OPEN' })">{{ l('新增问题', 'Add finding') }}</el-button></template>
        <template v-else-if="editorKind === 'release'"><el-form-item :label="l('方案版本', 'Solution version')" required><el-select v-model="form.solutionId"><el-option v-for="solution in project?.solutions" :key="solution.id" :value="solution.id" :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></el-form-item><el-form-item :label="l('输出用途', 'Intended use')"><el-input v-model="form.purpose" /></el-form-item></template>
        <el-alert v-if="editError" :title="editError" type="error" :closable="false" show-icon />
      </el-form><template #footer><span v-if="dirty" class="muted">{{ l('有未保存修改', 'Unsaved changes') }}</span><el-button @click="closeEditor(() => editorOpen = false)">{{ l('取消', 'Cancel') }}</el-button><el-button type="primary" :loading="saving" :disabled="conflict" @click="save">{{ l('保存', 'Save') }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="generationOpen" :title="l('交给售前员工执行', 'Delegate to presales employee')" width="min(580px, 95vw)" :close-on-click-modal="false"><el-alert v-if="employeeError" :title="employeeError" type="error" :closable="false" /><el-alert v-if="!project?.agentId" type="warning" :closable="false" :title="l('项目尚未绑定数字员工，请先在项目档案中选择。', 'No employee assigned. Select one in project settings.')" /><p>{{ l('执行员工', 'Assigned employee') }}：{{ project?.agentName || employees.find(e => e.id === project?.agentId)?.name || '—' }}</p><el-form label-position="top"><el-form-item :label="l('本次工作', 'Work to perform')"><el-select v-model="generation.skill"><el-option v-for="(name, key) in skillNames" :key="key" :value="key" :label="name" /></el-select></el-form-item><el-form-item :label="l('目标与补充说明', 'Goal and additional context')"><el-input v-model="generation.taskGoal" type="textarea" :rows="4" /></el-form-item></el-form><details class="inline-help"><summary>{{ l('执行范围', 'Execution scope') }}</summary><p>{{ l('员工读取本项目授权资料与已补充信息。需求确认和成果发布仍需人工审批。', 'The employee uses this project’s authorized sources and supplied information. Requirement confirmation and release remain human decisions.') }}</p></details><template #footer><el-button @click="generationOpen = false">{{ l('关闭', 'Close') }}</el-button><el-button type="primary" :disabled="!project?.agentId || !generation.taskGoal.trim() || dirty" :loading="saving" @click="generate">{{ l('开始执行', 'Start work') }}</el-button></template></el-dialog>
  </main>
</template>
<script setup lang="ts">
import PresalesDashboard from '../components/PresalesDashboard.vue'
import { loadPortfolio } from '../shared/dashboard'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { presalesApi, type PresalesCapabilities, type PresalesProject, type PresalesRecord } from '../api/presalesApi'
import { label as l } from '../shared/locale'
import { isCurrentRequest, presalesError, coverageLabel, operationReceipt } from '../shared/state'
const narrowQuery = window.matchMedia('(max-width: 768px)')
const isNarrow = ref(narrowQuery.matches)
function updateViewport(event: MediaQueryListEvent) { isNarrow.value = event.matches }
narrowQuery.addEventListener('change', updateViewport)
const receipt = operationReceipt()
const route = useRoute(), router = useRouter(), workspace = useWorkspaceStore()
const projectId = computed(() => String(route.params.projectId || ''))
const members = ref<PresalesRecord[]>([]), membersLoading = ref(false), membersError = ref('')
function memberName(member: PresalesRecord): string { return member.nickname || member.username || l('未命名成员', 'Unnamed member') }
function ownerName(id?: string): string { const member = members.value.find(item => item.userId === id); return member ? memberName(member) : id ? l('成员不可用', 'Member unavailable') : '—' }
async function loadMembers(ws: string, signal: AbortSignal) {
  members.value = []; membersError.value = ''; membersLoading.value = true
  try { const rows = await presalesApi.members(ws, signal); if (!signal.aborted && ws === workspace.currentWorkspaceId) members.value = rows }
  catch { if (!signal.aborted && ws === workspace.currentWorkspaceId) membersError.value = l('成员加载失败，请刷新重试。', 'Unable to load members. Refresh to retry.') }
  finally { if (!signal.aborted && ws === workspace.currentWorkspaceId) membersLoading.value = false }
}

const capabilities = ref<PresalesCapabilities>(), project = ref<PresalesProject>(), projects = ref<PresalesProject[]>([])
const portfolio = ref<PresalesProject[]>([]), portfolioLoading = ref(false), portfolioError = ref(false)
let portfolioController: AbortController | undefined
async function refreshPortfolio() {
  portfolioController?.abort(); portfolioController = new AbortController()
  const ws = workspace.currentWorkspaceId, signal = portfolioController.signal
  portfolio.value = []; portfolioError.value = false
  if (!ws || projectId.value || !capabilities.value?.enabled) { portfolioLoading.value = false; return }
  portfolioLoading.value = true
  try { const rows = await loadPortfolio(page => presalesApi.list(ws, { page, pageSize: 100 }, signal), signal); if (!signal.aborted && ws === workspace.currentWorkspaceId) portfolio.value = rows }
  catch { if (!signal.aborted) portfolioError.value = true }
  finally { if (!signal.aborted) portfolioLoading.value = false }
}
function filterStage(stage: string) { statusFilter.value = stage; search() }
function displayDate(value: string) { if (!value) return '—'; const date = new Date(value); return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
const projectMetrics = computed(() => {
  const p = project.value
  if (!p) return []
  return [
    { tab: 'requirements', label: l('需求', 'Requirements'), value: p.requirements.length, secondary: l(`${p.clarifications.filter(c => c.status !== 'ANSWERED').length} 项待澄清`, `${p.clarifications.filter(c => c.status !== 'ANSWERED').length} open`) },
    { tab: 'materials', label: l('资料来源', 'Sources'), value: p.materials.filter(m => m.status !== 'WITHDRAWN').length },
    { tab: 'solution', label: l('方案迭代', 'Solutions'), value: p.solutions.length },
    { tab: 'review', label: l('已发布成果', 'Published releases'), value: p.releases.filter(r => r.status === 'PUBLISHED').length },
  ]
})
const loading = ref(false), saving = ref(false), error = ref(''), conflict = ref(false), page = ref(1), total = ref(0)
const compareId = ref(''), compareTargetId = ref('')
const responseRows = computed(() => (project.value?.requirements || []).map(requirement => { const sections = (project.value?.solutions.at(-1)?.sections || []).filter((section: PresalesRecord) => section.requirementRefs?.includes(requirement.id)); const solution = project.value?.solutions.at(-1); const baseline = project.value?.baselines.find(item => item.id === solution?.baselineId); const ref = baseline?.references?.find((item: PresalesRecord) => item.requirementId === requirement.id); return { title: requirement.title, scope: ref?.scope || requirement.scope, response: solution?.coverage?.responses?.find((item: PresalesRecord) => item.requirementId === requirement.id)?.status || 'UNHANDLED', fit: project.value?.fitGaps.filter(fit => fit.requirementId === requirement.id).at(-1)?.status || 'UNKNOWN', sections: sections.map((section: PresalesRecord) => section.title).join(' / ') || '—', covered: sections.length > 0 } }))
const coverage = computed(() => { const rows = responseRows.value.filter(row => row.scope === 'IN'); return rows.length ? coverageLabel(rows.length, rows.filter(row => row.covered).length) : l('不适用（零分母）', 'Not applicable (zero denominator)') })
const comparison = computed(() => { const before = project.value?.solutions.find(solution => solution.id === compareId.value)?.sections || []; const after = (project.value?.solutions.find(solution => solution.id === compareTargetId.value) || project.value?.solutions.at(-1))?.sections || []; return Array.from(new Set([...before, ...after].map((section: PresalesRecord) => String(section.title)))).map(title => ({ title, before: before.find((section: PresalesRecord) => section.title === title)?.text || '—', after: after.find((section: PresalesRecord) => section.title === title)?.text || '—' })) })
const clarificationFilter = ref('ALL')
const filteredClarifications = computed(() => (project.value?.clarifications || []).filter(c => clarificationFilter.value === 'ALL' || (clarificationFilter.value === 'OPEN' ? c.status !== 'ANSWERED' : c.status === 'ANSWERED')))
const query = ref(''), ownerFilter = ref(''), statusFilter = ref(''), tab = ref('overview')
const stages = ['DISCOVERY', 'REQUIREMENTS', 'BASELINED', 'SOLUTION', 'RELEASE', 'ARCHIVED']
const canWrite = computed(() => !!capabilities.value?.enabled && !!capabilities.value?.canWrite && project.value?.status !== 'ARCHIVED')
const canApprove = computed(() => canWrite.value && !!capabilities.value?.canApprove && !!capabilities.value?.semanticEnabled)
const evidenceOpen = ref(false), evidence = ref<PresalesRecord>()
const presentationPreview = ref({ open: false, url: '', title: '' })
async function previewPresentation(presentation: PresalesRecord, filename: string) {
  const ws = workspace.currentWorkspaceId, id = projectId.value, artifactId = String(presentation.artifactId || '')
  const solutionId = project.value?.solutions?.find((solution: PresalesRecord) => solution.presentation?.artifactId === artifactId)?.id
  if (!ws || !id || !solutionId || !filename) return
  try {
    const blob = await presalesApi.file(ws, id, solutionId, filename, 'draft')
    if (presentationPreview.value.url) URL.revokeObjectURL(presentationPreview.value.url)
    presentationPreview.value = { open: true, url: URL.createObjectURL(new Blob([blob], { type: 'image/svg+xml' })), title: filename }
  } catch (e) { error.value = presalesError(e).message }
}
function clearPresentationPreview() {
  if (presentationPreview.value.url) URL.revokeObjectURL(presentationPreview.value.url)
  presentationPreview.value = { open: false, url: '', title: '' }
}
async function showEvidence(record: PresalesRecord) {
  evidence.value = record; evidenceOpen.value = true
  const ws = workspace.currentWorkspaceId, id = projectId.value
  const evidenceId = record.evidenceIds?.[0] || record.evidenceRefs?.[0]
  if (!ws || !record.graphId || !evidenceId) return
  try { const result = await presalesApi.evidence(ws, id, record.graphId, String(evidenceId)); if (isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)) evidence.value = { ...record, sourceSnapshot: result } }
  catch (e) { evidence.value = { id: record.id, status: 'UNAVAILABLE', error: presalesError(e).message } }
}
function versionLabel(kind: "solutions" | "releases", id: string): string { const index = project.value?.[kind].findIndex(item => item.id === id) ?? -1; return index < 0 ? "—" : `V${index + 1}` }
function baselineLabel(id: string): string { const index = project.value?.baselines.findIndex(item => item.id === id) ?? -1; return index < 0 ? l('需求确认', 'Requirements confirmation') : `${l('需求确认', 'Requirements confirmation')} V${index + 1}` }
function employeeIssue(code: string): string {
  const messages: Record<string, [string, string]> = {
    EMPLOYEE_UNAVAILABLE: ['负责员工不可用，请检查绑定、工作区与启用状态。', 'Assigned employee unavailable. Check assignment, workspace and enabled state.'],
    EMPLOYEE_RUNTIME_FAILED: ['员工执行失败，请查看执行过程并检查员工的模型配置后重试。', 'Employee execution failed. Check the execution and model configuration before retrying.'],
    EMPLOYEE_RUNTIME_UNAVAILABLE: ['员工运行服务暂不可用。', 'Employee runtime is unavailable.'],
    PRESENTATION_UNAVAILABLE: ['成果编译服务暂不可用，本次执行未完成。', 'Presentation compiler is unavailable; this run did not complete.'],
    PRESENTATION_FAILED: ['成果草稿编译失败，请检查页面内容后重试。', 'Presentation draft compilation failed; review the content and retry.'],
    PPT_GENERATION_FAILED: ['成果草稿生成失败，请检查 PPT 技能配置后重试。', 'Output draft generation failed; check the PPT skill configuration and retry.'],
    PPT_GENERATION_TIMEOUT: ['成果草稿生成超时，请稍后重试。', 'Output draft generation timed out; retry later.'],
    PROJECT_CHANGED_DURING_GENERATION: ['执行期间项目已变化，本次结果未采纳。请重新执行。', 'Project changed during execution. Results were not applied; run again.'],
  }
  const message = messages[code]; return message ? l(...message) : code
}
function stateLabel(state: string): string { const labels: Record<string, [string, string]> = { DISCOVERY: ['项目理解', 'Discovery'], REQUIREMENTS: ['需求梳理', 'Requirements'], BASELINED: ['需求已基线', 'Baselined'], SOLUTION: ['方案设计', 'Solution'], RELEASE: ['成果发布', 'Release'], ARCHIVED: ['已归档', 'Archived'], ACTIVE: ['进行中', 'Active'], FULL: ['完整响应', 'Full'], PARTIAL: ['部分响应', 'Partial'], CONDITIONAL: ['条件响应', 'Conditional'], EXCLUDED: ['排除范围', 'Excluded'], UNHANDLED: ['未处理', 'Unhandled'] }; Object.assign(labels, { HIGH: ['高', 'High'], MEDIUM: ['中', 'Medium'], LOW: ['低', 'Low'], IN: ['范围内', 'In scope'], OUT: ['范围外', 'Out of scope'], UNKNOWN: ['待核实', 'Unknown'], UNCONFIRMED: ['未确认', 'Unconfirmed'], OPEN: ['待处理', 'Open'], ANSWERED: ['已答复', 'Answered'], RESOLVED: ['已解决', 'Resolved'], ACCEPTED: ['已接受', 'Accepted'], PENDING: ['待批准', 'Awaiting approval'], APPROVED: ['已批准', 'Approved'], PUBLISHED: ['已发布', 'Published'], DRAFT: ['草稿', 'Draft'], SUCCEEDED: ['已完成', 'Succeeded'], RUNNING: ['运行中', 'Running'], FAILED: ['失败', 'Failed'], CANCELLED: ['已停止接收', 'Result discarded'], PROJECT: ['项目资料', 'Project material'], PRODUCT: ['产品资料', 'Product material'], CASE: ['案例资料', 'Case material'], CUSTOMER_SOURCE: ['客户来源', 'Customer source'], PRODUCT_SOURCE: ['产品来源', 'Product source'], INTERNAL_JUDGMENT: ['内部判断', 'Internal judgment'], ASSUMPTION: ['假设', 'Assumption'], AI_SUGGESTION: ['AI 建议', 'AI suggestion'], FIT: ['直接满足', 'Fit'], CONFIG: ['配置后满足', 'Configuration'], EXTEND: ['需要开发', 'Extension'], PARTNER: ['依赖合作方', 'Partner'], GAP: ['暂不支持', 'Gap'], BLOCKER: ['阻断', 'Blocker'], WARNING: ['需关注', 'Warning'], INFO: ['提示', 'Information'] }); const pair = labels[state]; return pair ? l(pair[0], pair[1]) : state || '—' }
function printable(value: unknown): string { return typeof value === 'string' ? value : JSON.stringify(value, null, 2) || '—' }
let controller: AbortController | undefined
const polling = new Map<string, AbortSignal>()
let pollController: AbortController | undefined
function waitForPoll(ms: number): Promise<void> { return new Promise(resolve => window.setTimeout(resolve, ms)) }
function pollTask(operationId: string) {
  const pollWs = workspace.currentWorkspaceId, pollId = projectId.value, pollSignal = pollController?.signal
  if (!pollWs || !pollId || !pollSignal) return
  const pollKey = `${pollWs}/${pollId}/${operationId}`
  const previousSignal = polling.get(pollKey)
  if (previousSignal && !previousSignal.aborted) return
  polling.set(pollKey, pollSignal)
  void (async () => {
    try {
      for (let attempt = 0; attempt < 600; attempt++) {
        await waitForPoll(1000)
        if (pollSignal.aborted) return
        const detail = await presalesApi.get(pollWs, pollId, pollSignal)
        if (!isCurrentRequest(pollWs, workspace.currentWorkspaceId, pollId, projectId.value)) return
        project.value = detail
        const task = (detail.tasks || []).find((item: PresalesRecord) => item.operationId === operationId)
        if (!task || task.status !== 'RUNNING') return
      }
    } catch (e) {
      if (!pollSignal.aborted) error.value = presalesError(e).message
    } finally { if (polling.get(pollKey) === pollSignal) polling.delete(pollKey) }
  })()
}
async function load() {
  if (dirty.value) return
  controller?.abort(); controller = new AbortController()
  pollController?.abort(); pollController = new AbortController()
  const ws = workspace.currentWorkspaceId, id = projectId.value, signal = controller.signal
  members.value = []; membersError.value = ''; membersLoading.value = false
  portfolioController?.abort(); portfolio.value = []; portfolioError.value = false; portfolioLoading.value = false; project.value = undefined; projects.value = []; capabilities.value = undefined; evidence.value = undefined; evidenceOpen.value = false; compareId.value = ''
  if (!ws) { error.value = l('请选择工作区。', 'Select a workspace.'); return }
  loading.value = true; error.value = ''; conflict.value = false
  try {
    const caps = await presalesApi.capabilities(ws, signal)
    if (!isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) || signal.aborted) return
    capabilities.value = caps
    if (!caps.enabled) return
    void loadMembers(ws, signal)
    if (!id) void refreshPortfolio()
    if (id) {
      const detail = await presalesApi.get(ws, id, signal)
      if (isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) && !signal.aborted) { project.value = detail; (detail.tasks || []).filter((task: PresalesRecord) => task.status === 'RUNNING' && task.operationId).forEach((task: PresalesRecord) => pollTask(task.operationId)) }
    } else {
      const result = await presalesApi.list(ws, { q: query.value, ownerId: ownerFilter.value, stage: statusFilter.value, page: page.value, pageSize: 20 }, signal)
      if (isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) && !signal.aborted) { projects.value = result.items; total.value = result.total }
    }
  } catch (e) { if (!signal.aborted) { if ((e as { response?: { status?: number } }).response?.status === 404 && !capabilities.value) capabilities.value = { enabled: false, semanticEnabled: false, canWrite: false, canApprove: false }; else error.value = presalesError(e).message } }
  finally { if (!signal.aborted) loading.value = false }
}
function search() { page.value = 1; void load() }
type Editor = 'project' | 'material' | 'requirement' | 'clarification' | 'baseline' | 'fitgap' | 'solution' | 'release' | 'review' | 'context'
const sourceOptions = ref<PresalesRecord[]>([]), statementOptions = ref<PresalesRecord[]>([]), optionsLoading = ref(false)
const editorOpen = ref(false), editorKind = ref<Editor>('project'), form = ref<Record<string, any>>({}), editError = ref(''), initialForm = ref('')
const editableRequirements = computed(() => { const baseline = project.value?.baselines.find(item => item.id === form.value.baselineId); return (project.value?.requirements || []).filter(item => !baseline || baseline.references?.some((reference: PresalesRecord) => reference.requirementId === item.id)) })
watch(() => form.value.baselineId, () => { if (editorKind.value !== 'solution' || !editorOpen.value) return; form.value.requirementResponses = editableRequirements.value.map(item => form.value.requirementResponses?.find((response: PresalesRecord) => response.requirementId === item.id) || { requirementId: item.id, status: 'UNHANDLED', reason: '' }) })
const dirty = computed(() => editorOpen.value && JSON.stringify(form.value) !== initialForm.value)
const editorTitle = computed(() => ({ project: l('项目档案', 'Project details'), material: l('绑定资料', 'Bind material'), requirement: l('需求修订', 'Requirement revision'), clarification: l('澄清问题', 'Clarification'), baseline: l('确认本轮需求', 'Confirm requirements'), fitgap: l('需求与能力匹配', 'Requirement capability matching'), solution: l('方案编辑', 'Solution editor'), release: l('发布候选', 'Release candidate'), review: l('人工独立评审', 'Independent human review'), context: l('项目理解草稿', 'Context draft') })[editorKind.value])
function openEditor(kind: Editor, record?: PresalesRecord) {
  if (!canWrite.value) return
  editorKind.value = kind; editError.value = ''; if (kind === 'project') void loadEmployees()
  form.value = JSON.parse(JSON.stringify(record || ({ project: { name: '', customer: '', ownerId: '', agentId: '', industry: '', goal: '' }, material: { kbId: '', graphId: '', role: 'PROJECT' }, requirement: { title: '', description: '', originKind: 'INTERNAL_JUDGMENT', priority: 'MEDIUM', scope: 'IN', statementId: '', statementRevision: '', graphId: '' }, clarification: { question: '', requirementId: '', impact: '', ownerId: '', answer: '', answerSourceId: '', status: 'OPEN' }, solution: { title: '', baselineId: project.value?.baselines.at(-1)?.id || '', sections: [{ title: '', text: '' }] }, fitgap: { requirementId: '', status: 'UNKNOWN', reason: '', evidenceText: '', productVersion: '', graphId: '' }, baseline: { reason: '' }, release: { solutionId: '', purpose: '' }, review: { solutionId: '', summary: '', issues: [] }, context: { title: '', text: '', originKind: 'AI_SUGGESTION', sourceRefs: [] } })[kind]))
  if (kind === 'solution') { delete form.value.id; if (!form.value.requirementResponses) form.value.requirementResponses = editableRequirements.value.map(item => ({ requirementId: item.id, status: 'UNHANDLED', reason: '' })) }
  initialForm.value = JSON.stringify(form.value); editorOpen.value = true; if (kind === 'material' || kind === 'requirement') void loadOptions(kind)
}
async function loadOptions(kind: Editor) {
  const ws = workspace.currentWorkspaceId, id = projectId.value
  if (!ws) return
  optionsLoading.value = true; sourceOptions.value = []; statementOptions.value = []
  try { const options = kind === 'material' ? await presalesApi.sources(ws) : await presalesApi.statements(ws, id); if (!isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) || editorKind.value !== kind) return; if (kind === 'material') sourceOptions.value = options; else statementOptions.value = options }
  catch (e) { editError.value = presalesError(e).message }
  finally { optionsLoading.value = false }
}
function selectSource(kbId: string) { const source = sourceOptions.value.find(item => item.kbId === kbId); form.value.graphId = source?.graphId || ''; form.value.name = source?.name || '' }
function selectStatement(key: string) { const statement = statementOptions.value.find(item => `${item.graphId}:${item.id}` === key); form.value.statementId = statement?.id || ''; form.value.statementRevision = statement?.revision || ''; form.value.graphId = statement?.graphId || ''; form.value.evidenceIds = statement?.evidenceIds || [] }
async function discard(): Promise<boolean> {
  if (saving.value) return false
  if (!dirty.value) { editorOpen.value = false; return true }
  try { await ElMessageBox.confirm(l('放弃尚未保存的修改？', 'Discard unsaved changes?'), l('未保存修改', 'Unsaved changes'), { type: 'warning' }); editorOpen.value = false; return true } catch { return false }
}
async function closeEditor(done: () => void) { if (await discard()) done() }
onBeforeRouteLeave(discard); onBeforeRouteUpdate(discard)
const unregister = workspace.registerBeforeSwitch(discard)
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || saving.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
async function command(action: string, payload: object): Promise<boolean> {
  const ws = workspace.currentWorkspaceId, current = project.value
  if (!ws || !current || !canWrite.value || saving.value) return false
  saving.value = true; editError.value = ''; error.value = ''
  try {
    const result = await presalesApi.command(ws, current.id, { action, payload, expectedVersion: current.version, operationId: receipt({ ws, id: current.id, version: current.version, action, payload }) })
    if (isCurrentRequest(ws, workspace.currentWorkspaceId, current.id, projectId.value)) project.value = result
    return true
  } catch (e) { const issue = presalesError(e); conflict.value = issue.conflict; error.value = editError.value = issue.message; return false }
  finally { saving.value = false }
}
async function save() {
  if (!canWrite.value || saving.value || conflict.value) return
  const data = JSON.parse(JSON.stringify(form.value))
  const required: Partial<Record<Editor, string[]>> = { project: ['name', 'customer'], material: ['kbId'], requirement: ['title'], clarification: ['question'], baseline: ['reason'], fitgap: ['requirementId'], solution: ['title'], release: ['solutionId'], review: ['solutionId', 'summary'], context: ['title', 'text'] }
  if (required[editorKind.value]?.some(key => !String(data[key] || '').trim())) { editError.value = l('请填写必填字段。', 'Complete the required fields.'); return }
  if (editorKind.value === 'clarification' && data.status === 'ANSWERED' && (!data.answer?.trim() || !data.answerSourceId?.trim())) { editError.value = l('标记已答复前，请填写答复和答复来源。', 'Provide an answer and its source before marking answered.'); return }
  if (editorKind.value === 'project') {
    const ws = workspace.currentWorkspaceId
    if (!ws) return
    saving.value = true
    try {
      const body = { name: data.name, customer: data.customer, ownerId: data.ownerId, agentId: data.agentId ?? '', industry: data.industry, goal: data.goal, expectedVersion: project.value?.version || 0, operationId: receipt({ ws, id: projectId.value, version: project.value?.version || 0, data }) }
      const result = project.value ? await presalesApi.update(ws, project.value.id, body) : await presalesApi.create(ws, body)
      editorOpen.value = false; saving.value = false
      if (ws === workspace.currentWorkspaceId) { project.value = result; if (projectId.value) await load(); else await router.push(`/presales/${result.id}`) }
    } catch (e) { const issue = presalesError(e); editError.value = error.value = issue.message; conflict.value = issue.conflict }
    finally { saving.value = false }
    return
  }
  if (editorKind.value === 'fitgap') { data.evidenceIds = String(data.evidenceText || '').split(',').map((id: string) => id.trim()).filter(Boolean); delete data.evidenceText }
  const actions = { material: 'BIND_MATERIAL', requirement: 'SAVE_REQUIREMENT', clarification: 'SAVE_CLARIFICATION', baseline: 'APPROVE_BASELINE', fitgap: 'SAVE_FIT_GAP', solution: 'SAVE_SOLUTION', release: 'CREATE_RELEASE', review: 'SAVE_REVIEW', context: 'SAVE_CONTEXT' }
  if (await command(actions[editorKind.value], data)) { editorOpen.value = false; if (editorKind.value === 'clarification' && data.status === 'ANSWERED' && project.value?.agentId) await continueEmployee() }
}
async function downloadHandoff() {
  const ws = workspace.currentWorkspaceId, id = projectId.value
  if (!ws) return
  try { const result = await presalesApi.handoff(ws, id); if (!isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)) return; const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `internal-handoff-${id}-v${project.value?.version}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) } catch (e) { error.value = presalesError(e).message }
}
async function download(versionId: string, filename: string, kind: 'files' | 'preview' | 'draft') {
  const ws = workspace.currentWorkspaceId, id = projectId.value
  if (!ws) return
  try { const blob = await presalesApi.file(ws, id, versionId, filename, kind); if (!isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)) return; const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = (kind === 'files' ? '' : 'UNAPPROVED-') + filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
  catch (e) { error.value = presalesError(e).message }
}
async function approveRelease(release: PresalesRecord) {
  try { const result = await ElMessageBox.prompt(l('确认此精确版本并填写批准理由。', 'Review this exact version and enter your approval reason.'), l('审批发布', 'Approve release'), { inputValidator: value => !!value?.trim() }); await command('APPROVE_RELEASE', { releaseId: release.id, reason: result.value }) } catch { /* Cancel. */ }
}
function adopt(skill: string, item: PresalesRecord) {
  if (skill === 'S1') openEditor('context', { ...item })
  else if (skill === 'S5' || skill === 'S6') openEditor('solution', { id: '', title: item.title, baselineId: project.value?.baselines.at(-1)?.id || '', sections: [{ title: item.title, text: item.text, evidenceRefs: item.sourceRefs || [] }] })
  else openEditor('requirement', { id: '', title: item.title, description: item.text, priority: 'MEDIUM', scope: 'UNKNOWN', originKind: item.originKind, sourceRefs: item.sourceRefs || [] })
}
async function archive() {
  try { await ElMessageBox.confirm(l('归档后项目将只读，是否继续？', 'Archive this project and make it read-only?'), l('归档项目', 'Archive project'), { type: 'warning' }); await command('ARCHIVE', {}) } catch { /* Cancel leaves data unchanged. */ }
}
const generationOpen = ref(false), employees = ref<PresalesRecord[]>([]), employeeError = ref(''), employeesLoading = ref(false)
const generation = ref({ skill: 'S1', taskGoal: '' })
const skillNames = computed(() => ({ S1: l('理解项目与资料', 'Analyze project context'), S2: l('梳理需求与待补充信息', 'Assess requirements and missing information'), S3: l('匹配产品能力', 'Match product capabilities'), S4: l('查找适用案例', 'Find relevant cases'), S5: l('编制方案', 'Compose solution'), S6: l('编制成果草稿', 'Prepare output drafts'), S7: l('检查方案与风险', 'Review solution and risks'), S8: l('整理交接信息', 'Prepare handoff') }))
async function loadEmployees() {
  const ws = workspace.currentWorkspaceId
  if (!ws) return
  employees.value = []; employeeError.value = ''; employeesLoading.value = true
  try { const result = await presalesApi.employees(ws); if (ws === workspace.currentWorkspaceId) employees.value = result }
  catch (e) { if (ws === workspace.currentWorkspaceId) employeeError.value = employeeIssue(presalesError(e).message) }
  finally { if (ws === workspace.currentWorkspaceId) employeesLoading.value = false }
}
async function openGeneration(skill: string) {
  if (dirty.value || !canWrite.value) return
  generation.value.skill = skill
  generation.value.taskGoal = l('请依据本项目授权资料与已补充信息推进工作，列出需要我补充的信息，保留未知项并提供依据。', 'Use authorized project sources and supplied answers to advance the work. Request missing information and preserve unknowns with evidence.')
  generationOpen.value = true
  await loadEmployees()
}
async function continueEmployee() {
  await openGeneration('S2')
  generation.value.taskGoal = l('请读取已答复的补充信息，核对依据，继续梳理本轮需求并提出尚未解决的问题。不要重复创建已有问题。', 'Review supplied answers and sources, continue requirements work and identify unresolved questions without duplicating existing ones.')
}
async function generate() {
  const ws = workspace.currentWorkspaceId, current = project.value
  if (!ws || !current || dirty.value || saving.value || !canWrite.value) return
  saving.value = true
  const operationId = receipt({ ws, id: current.id, version: current.version, generation: generation.value })
  try {
    const result = await presalesApi.generate(ws, current.id, { ...generation.value, expectedVersion: current.version, operationId })
    if (isCurrentRequest(ws, workspace.currentWorkspaceId, current.id, projectId.value)) project.value = result
    generationOpen.value = false
    pollTask(operationId)
  } catch (e) { const issue = presalesError(e); error.value = employeeError.value = employeeIssue(issue.message); conflict.value = issue.conflict }
  finally { saving.value = false }
}
async function cancelTask(task: PresalesRecord) {
  const ws = workspace.currentWorkspaceId, id = projectId.value
  if (!ws || !id || !canWrite.value || task.status !== 'RUNNING') return
  saving.value = true
  try {
    const result = await presalesApi.cancelTask(ws, id, task.id, { operationId: receipt({ ws, id, taskId: task.id, action: 'cancel' }) })
    if (isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)) project.value = result
  } catch (e) { const issue = presalesError(e); error.value = employeeError.value = employeeIssue(issue.message); conflict.value = issue.conflict }
  finally { saving.value = false }
}
watch([() => workspace.currentWorkspaceId, projectId], () => { editorOpen.value = false; generationOpen.value = false; page.value = 1; void load() }, { immediate: true })
onBeforeUnmount(() => { portfolioController?.abort(); controller?.abort(); pollController?.abort(); clearPresentationPreview(); unregister(); narrowQuery.removeEventListener('change', updateViewport); window.removeEventListener('beforeunload', beforeUnload) })
</script>
<style scoped>
.presales-workbench { padding: 24px 32px; color: var(--el-text-color-primary); min-width: 0; max-width: 100%; box-sizing: border-box; height: 100%; overflow: auto; background: var(--el-fill-color-lighter); }
.page-heading, .section-heading, .project-meta, .toolbar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.page-heading, .section-heading { justify-content: space-between; }
.page-heading > div { min-width: 0; max-width: 100%; }
.page-heading h1, .project-meta span { overflow-wrap: anywhere; max-width: 100%; }
.presales-workbench :deep(.el-descriptions__content) { overflow-wrap: anywhere; }
h1 { font-size: 24px; margin: 8px 0; font-weight: 600; } h2 { font-size: 16px; font-weight: 600; } h3 { font-size: 15px; font-weight: 600; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.toolbar { margin: 20px 0 12px; } .toolbar .el-input { width: 240px; } .toolbar .el-select { width: 180px; }
.project-meta { padding: 16px 0; font-size: 13px; } .section-heading { margin: 16px 0; }
.el-alert { margin: 12px 0; } .el-pagination { margin-top: 20px; justify-content: flex-end; }
.safe-content { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.7; margin: 8px 0; }
.solution-revision { border-bottom: 1px solid var(--el-border-color); padding: 12px 0 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.section-editor { border-top: 1px solid var(--el-border-color-light); padding-top: 16px; }
.el-form .el-select { width: 100%; }
@media (max-width: 768px) { .presales-workbench { padding: 16px; }.toolbar .el-input, .toolbar .el-select { width: 100%; }.form-grid { grid-template-columns: 1fr; }.page-heading { align-items: flex-start; } }

.page-heading { margin-bottom:24px; }.page-heading h1 { font-size:26px; letter-spacing:-.4px; }.ledger-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }.ledger-heading h2 { margin:0; font-size:17px; }.ledger-heading h2 span { margin-left:8px; font-size:12px; font-weight:400; color:var(--el-text-color-secondary); }.project-ledger { border:1px solid var(--el-border-color-light); border-radius:6px; }.project-ledger :deep(.el-table__cell) { padding:16px 0; }.project-ledger :deep(.el-table__header th) { background:var(--el-fill-color-light); font-weight:500; }.stage-label { display:inline-flex; align-items:center; gap:6px; font-size:12px; }.stage-label::before { content:''; width:6px; height:6px; border-radius:50%; background:var(--el-color-primary); }.stage-label[data-stage="RELEASE"]::before { background:var(--el-color-success); }.stage-label[data-stage="ARCHIVED"]::before { background:var(--el-text-color-placeholder); }.project-pulse { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); margin:8px 0 24px; border:1px solid var(--el-border-color-light); border-radius:6px; background:var(--el-bg-color); }.project-pulse button { font:inherit; color:inherit; text-align:left; padding:18px 22px; background:none; border:0; border-right:1px solid var(--el-border-color-lighter); cursor:pointer; }.project-pulse button:last-child { border-right:0; }.project-pulse button>span { font-size:12px; color:var(--el-text-color-secondary); }.project-pulse strong { display:block; font-size:25px; font-weight:600; margin-top:8px; }.project-pulse small { display:block; margin-top:5px; color:var(--el-text-color-secondary); font-size:12px; font-weight:400; }.project-pulse button:hover { background:var(--el-color-primary-light-9); }.project-pulse button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:-2px; }.presales-workbench :deep(.el-tabs__content) { background:var(--el-bg-color); padding:4px 20px 24px; border:1px solid var(--el-border-color-light); border-radius:6px; }
@media(max-width:768px) { .ledger-heading { align-items:flex-start; flex-direction:column; }.project-pulse { grid-template-columns:repeat(2,minmax(0,1fr)); }.project-pulse button { padding:14px; }.page-heading h1 { font-size:23px; }.presales-workbench :deep(.el-tabs__content) { padding:4px 12px 16px; } }
.employee-owner { display:flex; justify-content:space-between; align-items:center; gap:20px; padding:18px 22px; border:1px solid var(--el-border-color-light); border-left:3px solid var(--el-color-primary); background:var(--el-bg-color); border-radius:6px; margin:4px 0 18px; }.employee-owner h2 { margin:6px 0; }.employee-owner>div:last-child { display:flex; gap:8px; flex-wrap:wrap; flex-shrink:0; }@media(max-width:1000px) { .employee-owner { flex-direction:column; align-items:flex-start; } }
.inline-help { margin:12px 0 16px; color:var(--el-text-color-secondary); font-size:13px; line-height:1.7; }.inline-help summary { color:var(--el-color-primary); cursor:pointer; }.inline-help p { margin:8px 0 0; max-width:80ch; }.coverage { margin:20px 0 28px; padding:16px 0 20px; border-top:1px solid var(--el-border-color-light); border-bottom:1px solid var(--el-border-color-light); }.coverage-heading { display:flex; align-items:center; gap:12px; margin-bottom:12px; }.coverage-heading h3 { margin:0; }.coverage-heading .inline-help { margin:0; }.coverage-stats { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-bottom:16px; }.coverage-stats>div { padding:12px 14px; border:1px solid var(--el-border-color-lighter); background:var(--el-fill-color-lighter); }.coverage-stats span { display:block; color:var(--el-text-color-secondary); font-size:12px; }.coverage-stats strong { display:block; margin-top:5px; font-size:18px; font-variant-numeric:tabular-nums; }.solution-version-header { display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap; }.solution-version-header>div { display:flex; align-items:center; gap:10px; min-width:0; }.solution-version-header h3 { margin:0; overflow-wrap:anywhere; }.solution-downloads { display:flex; align-items:center; flex-wrap:wrap; gap:4px 12px; margin:14px 0 12px; padding:8px 0; border-top:1px solid var(--el-border-color-lighter); border-bottom:1px solid var(--el-border-color-lighter); color:var(--el-text-color-secondary); font-size:12px; }.solution-downloads .el-button { padding:4px; }.baseline-details { margin:0 0 18px; color:var(--el-text-color-secondary); font-size:13px; line-height:1.7; }.baseline-details summary { color:var(--el-color-primary); cursor:pointer; }.baseline-details p { margin:6px 0 0; overflow-wrap:anywhere; }.solution-section { padding:16px 0; border-top:1px solid var(--el-border-color-lighter); }.solution-section h4 { margin:0 0 8px; font-size:15px; }.solution-section .safe-content { margin:0 0 8px; line-height:1.7; }
@media(max-width:600px) { .coverage-stats { grid-template-columns:1fr; } }

.task-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; margin-bottom:16px; }
.task-heading>strong { min-width:0; overflow-wrap:anywhere; }
.task-actions { display:flex; align-items:center; justify-content:flex-end; gap:8px; flex-wrap:wrap; }
.task-actions .el-button + .el-button { margin-left:0; }
.presales-workbench :deep(.el-button:focus-visible) { outline:2px solid var(--el-color-primary); outline-offset:3px; }
.presentation-result { display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin:14px 0; padding:12px 14px; border:1px solid var(--el-border-color-lighter); background:var(--el-fill-color-lighter); }
.presentation-result .task-actions { width:100%; justify-content:flex-start; }
.presentation-preview { display:block; width:100%; max-height:75vh; object-fit:contain; background:#fff; }
.navigation-link { text-decoration:underline; text-underline-offset:3px; }
.project-pulse button>span::after { content:' ↗'; color:var(--el-color-primary); }
@media(max-width:768px) { .task-actions { justify-content:flex-start; width:100%; } }

.table-actions { display:flex; align-items:center; flex-wrap:wrap; gap:8px; }
.table-actions .el-button { margin:0; flex-shrink:0; }
</style>
