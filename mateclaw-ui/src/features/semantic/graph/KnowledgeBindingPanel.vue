<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { vLoading } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { graphApi } from "../api/graphApi";
import { ontologyApi } from "../api/ontologyApi";
import { semanticError, type SemanticError } from "../api/semanticErrors";
import type { Binding, Revision } from "../api/types";
import { useSemanticScope } from "../shared/useSemanticScope";

const props = defineProps<{ knowledgeBaseId: string; targetRevision?: Revision }>();
const { t, locale } = useI18n();
const tr = (zh: string, en: string) => locale.value.startsWith("zh") ? zh : en;
const router = useRouter();
const { workspace, begin } = useSemanticScope();
const binding = ref<Binding | null>(null);
const choices = ref<(Revision & { ontologyName: string })[]>([]);
const selectedRevisionId = ref("");
const busy = ref(false);
const target = computed(() => props.targetRevision ?? choices.value.find(r => r.id === selectedRevisionId.value));
const same = computed(() => !!binding.value && binding.value.ontologyRevisionId === selectedRevisionId.value);
const currentName = computed(() => choices.value.find(r => r.id === binding.value?.ontologyRevisionId)?.name ?? tr('已绑定模型', 'Bound model'));
function openWorkbench(tab = 'facts') {
  if (binding.value) void router.push({ path: `/semantic/graphs/${binding.value.graphId}`, query: { tab, ...(tab === 'migration' ? { targetRevision: selectedRevisionId.value } : {}) } });
}
function compareVersions() {
  if (binding.value && target.value) void router.push({ path: `/semantic/ontologies/${target.value.ontologyId}/versions`, query: { revision: target.value.id, from: binding.value.ontologyRevisionId } });
}
const sameOntology = computed(() => choices.value.find(r => r.id === binding.value?.ontologyRevisionId)?.ontologyId === target.value?.ontologyId);
const error = ref<SemanticError | null>(null);

async function load() {
  const request = begin();
  busy.value = true;
  error.value = null;
  try {
    const current = await graphApi
      .binding(request.id, props.knowledgeBaseId, request.signal)
      .catch((e) => {
        if (semanticError(e).status === 404) return null;
        throw e;
      });
    const ontologies = await ontologyApi.list(
      request.id,
      "",
      1,
      request.signal,
    );
    for (let page = 2; ontologies.items.length < ontologies.total; page++) {
      if (!request.current()) return;
      const next = await ontologyApi.list(request.id, "", page, request.signal);
      if (next.items.length === 0) break;
      ontologies.items.push(...next.items);
    }
    const revisions = await Promise.all(
      ontologies.items.map(async (ontology) =>
        (
          await ontologyApi.revisions(request.id, ontology.id, request.signal)
        ).map((revision) => ({
          ...revision,
          ontologyName: ontology.name,
        })),
      ),
    );
    if (request.current()) {
      binding.value = current;
      choices.value = revisions
        .flat()
        .filter((revision) => revision.availableForNewBindings || revision.id === current?.ontologyRevisionId);
      selectedRevisionId.value =
        props.targetRevision?.id ?? current?.ontologyRevisionId ?? choices.value[0]?.id ?? "";
    }
  } catch (e) {
    if (request.current()) error.value = semanticError(e);
  } finally {
    if (request.current()) busy.value = false;
  }
}

async function submit(action: "ENABLE" | "DISABLE" | "REBIND") {
  if (busy.value || !workspace.can('publish:ontology')) return;
  if (action === 'REBIND' && (!binding.value?.empty || same.value || !target.value?.availableForNewBindings)) return;
  if (action === 'ENABLE' && ((binding.value && !same.value) || (!binding.value && !target.value?.availableForNewBindings))) return;
  const request = begin();
  busy.value = true;
  error.value = null;
  try {
    const result = await graphApi.bind(
      request.id,
      props.knowledgeBaseId,
      {
        action,
        revisionId: action === "DISABLE" ? undefined : selectedRevisionId.value,
        expectedGraphVersion: binding.value?.graphVersion,
      },
      request.signal,
    );
    if (request.current()) binding.value = result;
  } catch (e) {
    if (request.current()) error.value = semanticError(e);
  } finally {
    if (request.current()) busy.value = false;
  }
}

watch(
  () => [props.knowledgeBaseId, props.targetRevision?.id, workspace.currentWorkspaceId],
  () => {
    binding.value = null;
    choices.value = [];
    void load();
  },
);
onMounted(load);
</script>

<template>
  <section class="semantic-binding-panel" :class="{ 'binding-inline': targetRevision }">
    <div class="semantic-binding-heading">
      <div>
        <h3>{{ targetRevision ? tr('应用状态', 'Application status') : t("semantic.bindingTitle") }}</h3>
      </div>
      <div class="semantic-binding-actions">
        <el-button
          v-if="binding?.enabled"
          type="primary"
          :disabled="busy"
          @click="openWorkbench()"
          >{{ t("semantic.w.openWorkbench") }}</el-button
        >
        <el-tag v-if="binding" :type="binding.enabled ? 'success' : 'info'">
          {{
            t(
              binding.enabled
                ? "semantic.bindingEnabled"
                : "semantic.bindingDisabled",
            )
          }}
        </el-tag>
      </div>
    </div>
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))"
      :description="error.message"
    />
    <div v-loading="busy" class="semantic-binding-controls">
      <el-select v-if="!targetRevision"
        v-model="selectedRevisionId"
        :aria-label="t('semantic.bindingRevision')"
        :disabled="busy || !workspace.can('publish:ontology')"
      >
        <el-option
          v-for="revision in choices"
          :key="revision.id"
          :value="revision.id"
          :label="`${revision.ontologyName} · v${revision.version}`"
        />
      </el-select>
      <template v-if="workspace.can('publish:ontology')">
        <el-button
          v-if="!binding"
          type="primary"
          :disabled="busy || !target?.availableForNewBindings"
          @click="submit('ENABLE')"
        >
          {{ t("semantic.bind") }}
        </el-button>
        <el-button
          v-else-if="!binding.enabled && same"
          :disabled="busy"
          type="primary"
          @click="submit('ENABLE')"
        >
          {{ t("semantic.enableBinding") }}
        </el-button>
        <el-button v-else-if="binding.enabled" :disabled="busy" @click="submit('DISABLE')">{{
          t("semantic.disableBinding")
        }}</el-button>
        <el-button
          v-if="binding"
          :disabled="
            busy || !binding.empty || same || !target?.availableForNewBindings
          "
          @click="submit('REBIND')"
        >
          {{ t("semantic.rebind") }}
        </el-button>
      </template>
    </div>

    <p v-if="targetRevision && !targetRevision.availableForNewBindings && !same" class="semantic-binding-meta">{{ tr('此版本已停止新应用，请选择其他版本。', 'This version is unavailable for new applications.') }}</p>
    <p v-if="binding" class="semantic-binding-meta">{{ currentName }} ·

      {{ t("semantic.pinnedVersion", { version: binding.ontologyVersion }) }} ·
      {{
        binding.empty ? t("semantic.graphEmpty") : t("semantic.graphNotEmpty")
      }}
    </p>
    <p v-if="binding && !binding.empty && !same" class="semantic-binding-meta">{{ sameOntology ? tr('知识库已有内容，需先检查版本差异并迁移。', 'This knowledge base contains data. Review differences and migrate first.') : tr('知识库已使用其他业务模型，请选择其他知识库。', 'This knowledge base uses another business model. Select another knowledge base.') }}</p>
    <div v-if="binding" class="semantic-binding-actions binding-next">
      <el-button v-if="!same && sameOntology" :disabled="busy" @click="compareVersions">{{ tr('查看版本差异', 'Compare versions') }}</el-button>
      <el-button v-if="!same && sameOntology && !binding.empty && workspace.can('publish:ontology')" :disabled="busy" @click="openWorkbench('migration')">{{ tr('前往版本迁移', 'Open migration') }}</el-button>
      <el-button v-if="binding.enabled && same" :disabled="busy" @click="openWorkbench('extraction')">{{ tr('试抽取', 'Try extraction') }}</el-button>
      <el-button :disabled="busy" @click="load">{{ tr('刷新绑定', 'Refresh binding') }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.binding-next { margin-top: 12px; }
.semantic-binding-panel.binding-inline { margin: 0; padding: 16px 0 0; border: 0; border-radius: 0; }
.semantic-binding-panel {
  margin: 16px 0 0;
  padding: 20px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
}
.semantic-binding-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.semantic-binding-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.semantic-binding-heading h3 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 16px;
}
.semantic-binding-heading p,
.semantic-binding-meta {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}
.semantic-binding-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}
.semantic-binding-controls .el-select {
  width: min(420px, 100%);
}
@media (max-width: 720px) {
  .semantic-binding-heading {
    flex-direction: column;
  }
  .semantic-binding-controls .el-select {
    width: 100%;
  }
}
</style>
