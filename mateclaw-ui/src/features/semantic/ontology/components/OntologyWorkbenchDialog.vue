<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { graphApi } from "../../api/graphApi";
import { semanticRequest } from "../../api/ontologyApi";
import type { Binding, Ontology } from "../../api/types";
import { semanticError } from "../../api/semanticErrors";
import { useSemanticScope } from "../../shared/useSemanticScope";
const props = defineProps<{ ontology: Ontology }>();
const emit = defineEmits<{ close: [] }>();
const { t } = useI18n();
const router = useRouter();
const { begin } = useSemanticScope();
const rows = ref<(Binding & { name: string })[]>([]);
const busy = ref(true);
const error = ref("");
async function load() {
  const c = begin();
  busy.value = true;
  error.value = "";
  try {
    const [bindings, bases] = await Promise.all([
      graphApi.bindingsForOntology(c.id, props.ontology.id, c.signal),
      semanticRequest<{ id: string; name: string }[]>(
        c.id,
        { url: "/wiki/knowledge-bases" },
        c.signal,
      ),
    ]);
    if (c.current())
      rows.value = bindings.map((b) => ({
        ...b,
        name:
          bases.find((k) => String(k.id) === b.knowledgeBaseId)?.name ??
          b.knowledgeBaseId,
      }));
  } catch (e) {
    if (c.current()) error.value = semanticError(e).message;
  } finally {
    if (c.current()) busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="`${ontology.name} · ${t('semantic.w.workbench')}`"
    width="min(760px, 94vw)"
    @close="emit('close')"
  >
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-table
      v-loading="busy"
      :data="rows"
      :empty-text="t('semantic.w.noBindings')"
    >
      <el-table-column
        prop="name"
        :label="t('semantic.w.chooseKnowledgeBase')"
        min-width="200"
      />
      <el-table-column :label="t('semantic.version')" width="90"
        ><template #default="{ row }"
          >v{{ row.ontologyVersion }}</template
        ></el-table-column
      >
      <el-table-column :label="t('semantic.state')" width="100"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            t(
              row.enabled
                ? "semantic.bindingEnabled"
                : "semantic.bindingDisabled",
            )
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column width="180"
        ><template #default="{ row }"
          ><el-button
            type="primary"
            :disabled="!row.enabled"
            @click="
              router.push({
                name: 'SemanticWorkbench',
                params: { graphId: row.graphId },
              })
            "
            >{{ t("semantic.w.openWorkbench") }}</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <template #footer
      ><el-button v-if="error" @click="load">{{
        t("semantic.searchAction")
      }}</el-button
      ><el-button @click="router.push('/wiki')">{{
        t("semantic.w.goToKnowledgeBases")
      }}</el-button></template
    >
  </el-dialog>
</template>
