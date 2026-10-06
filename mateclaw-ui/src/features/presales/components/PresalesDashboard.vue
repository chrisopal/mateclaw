<template>
  <section
    class="dashboard"
    :aria-label="t('presales.dashboard_overview')"
  >
    <div class="overview-actions">
      <button
        type="button"
        class="text-action"
        :disabled="loading"
        @click="$emit('refresh')"
      >
        {{ loading ? t('presales.dashboard_updating') : t('presales.dashboard_refresh') }}
      </button>
    </div>
    <el-alert
      v-if="error"
      type="warning"
      :closable="false"
      :title="t('presales.dashboard_unavailable')"
    />
    <template v-else>
      <div
        class="metric-strip"
        :aria-busy="loading"
      >
        <div
          v-for="metric in metrics"
          :key="metric.label"
          class="metric"
        >
          <span :title="metric.hint">{{ metric.label }}</span
          ><strong
            >{{ loading ? '—' : metric.value }}<small>{{ metric.unit }}</small></strong
          >
        </div>
      </div>
      <div class="overview-grid">
        <section class="pipeline-panel">
          <div class="panel-heading">
            <h2>{{ t('presales.dashboard_pipeline') }}</h2>
          </div>
          <div class="pipeline-track">
            <button
              v-for="(stage, i) in summary.stages"
              :key="stage.key"
              type="button"
              class="pipeline-stage"
              :class="{ selected: selectedStage === stage.key }"
              :aria-pressed="selectedStage === stage.key"
              @click="$emit('stage', selectedStage === stage.key ? '' : stage.key)"
            >
              <span class="stage-name"
                ><i :style="{ opacity: 0.4 + i * 0.15 }"></i>{{ names[i] }}</span
              ><strong>{{ loading ? '—' : stage.count }}</strong
              ><span class="stage-bar"
                ><span
                  :style="{
                    width: `${loading || !summary.active ? 0 : (stage.count / summary.active) * 100}%`,
                  }"
                ></span
              ></span>
            </button>
          </div>
        </section>
        <section class="attention-panel">
          <div class="panel-heading">
            <h2>{{ t('presales.dashboard_attention') }}</h2>
          </div>
          <p
            v-if="loading"
            class="attention-empty"
          >
            {{ t('presales.dashboard_loading') }}
          </p>
          <p
            v-else-if="!summary.attention.length"
            class="attention-empty"
          >
            {{ t('presales.dashboard_no_questions') }}
          </p>
          <button
            v-for="item in loading ? [] : summary.attention"
            :key="item.id"
            type="button"
            class="attention-row"
            @click="$emit('open', item.id)"
          >
            <span
              ><strong>{{ item.name }}</strong
              ><small>{{ item.customer }}</small></span
            ><b
              >{{ item.openClarificationCount }}
              <small>{{ t('presales.dashboard_open') }}</small></b
            >
          </button>
        </section>
      </div>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { PresalesProjectSummary } from '../api/presalesApi'
import { summarizeProjects } from '../shared/dashboard'
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
const { t } = useI18n({ messages: presalesMessages })
const props = defineProps<{
  projects: PresalesProjectSummary[]
  loading: boolean
  error: boolean
  selectedStage: string
}>()
defineEmits<{ refresh: []; stage: [value: string]; open: [id: string] }>()
const summary = computed(() => summarizeProjects(props.projects))
const names = computed(() => [
  t('presales.dashboard_discovery'),
  t('presales.dashboard_requirements'),
  t('presales.dashboard_baseline'),
  t('presales.dashboard_solution'),
  t('presales.dashboard_release'),
])
const metrics = computed(() => [
  {
    label: t('presales.dashboard_active'),
    value: summary.value.active,
    unit: t('presales.dashboard_unit_projects'),
  },
  {
    label: t('presales.dashboard_questions'),
    hint: t('presales.unanswered_explanation'),
    value: summary.value.questions,
    unit: t('presales.dashboard_unit_items'),
  },
  {
    label: t('presales.dashboard_solutions'),
    value: summary.value.solutions,
    unit: t('presales.dashboard_unit_projects'),
  },
  {
    label: t('presales.dashboard_releases'),
    value: summary.value.releases,
    unit: t('presales.dashboard_unit_projects'),
  },
])
</script>
<style scoped>
.dashboard {
  margin: 12px 0 28px;
}
.overview-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.text-action {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  cursor: pointer;
  padding: 7px 12px;
  font: inherit;
}
.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
  padding: 20px 0;
}
.metric {
  padding: 0 24px;
  border-right: 1px solid var(--el-border-color-lighter);
}
.metric:last-child {
  border: 0;
}
.metric > span {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.metric > strong {
  display: block;
  font-size: 32px;
  line-height: 1.4;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  margin-top: 6px;
}
.metric strong small {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}
.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(280px, 1fr);
  gap: 20px;
  margin-top: 20px;
}
.pipeline-panel,
.attention-panel {
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  border-radius: 6px;
  padding: 20px;
  min-width: 0;
}
.panel-heading h2 {
  font-size: 15px;
  margin: 0;
  font-weight: 600;
}
.pipeline-track {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin: 20px 0 0;
}
.pipeline-stage {
  text-align: left;
  font: inherit;
  color: inherit;
  padding: 10px 8px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  cursor: pointer;
  min-width: 0;
}
.pipeline-stage:hover,
.pipeline-stage.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.stage-name {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.stage-name i {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--el-color-primary);
}
.pipeline-stage strong {
  display: block;
  font-size: 25px;
  font-weight: 500;
  margin: 12px 0;
  font-variant-numeric: tabular-nums;
}
.stage-bar {
  display: block;
  height: 3px;
  background: var(--el-border-color-lighter);
}
.stage-bar > span {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
}
.attention-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin: 0;
}
.attention-empty {
  padding: 28px 0;
}
.attention-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  text-align: left;
  border: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 12px 0;
  background: none;
  font: inherit;
  color: inherit;
  cursor: pointer;
}
.attention-row > span {
  min-width: 0;
}
.attention-row strong {
  display: block;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.attention-row small {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.attention-row > b {
  color: var(--el-color-warning-dark-2);
  white-space: nowrap;
  font-size: 16px;
}
.attention-row > b small {
  display: inline;
  font-weight: 400;
}
.attention-row:hover strong {
  color: var(--el-color-primary);
}
button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
@media (max-width: 1100px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
  .metric {
    padding: 0 16px;
  }
}
@media (max-width: 600px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 20px;
  }
  .metric:nth-child(2) {
    border: 0;
  }
  .pipeline-track {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .pipeline-panel,
  .attention-panel {
    padding: 16px;
  }
  .metric > strong {
    font-size: 28px;
  }
}

.text-action:hover:not(:disabled) {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.text-action:disabled {
  color: var(--el-disabled-text-color);
  border-color: var(--el-disabled-border-color);
  background: var(--el-disabled-bg-color);
  cursor: not-allowed;
}
.stage-name::after {
  content: '›';
  margin-left: auto;
  color: var(--el-color-primary);
  font-size: 18px;
}
.attention-row strong {
  color: var(--el-color-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
