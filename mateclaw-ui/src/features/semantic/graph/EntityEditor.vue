<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { graphApi } from '../api/graphApi'
import { useSemanticScope } from '../shared/useSemanticScope'
const props = defineProps<{ graphId: string; classIris?: string[] }>(); const emit = defineEmits<{ saved: [] }>(); const { t } = useI18n(); const { begin } = useSemanticScope()
const iri = ref(''); const assertedTypes = ref<string[]>([]); const displayName = ref(''); const busy = ref(false); const error = ref('')
async function save() { if (!iri.value.trim() || !displayName.value.trim() || busy.value) return; const run = begin(); busy.value = true; error.value = ''; try { await graphApi.createEntity(run.id, props.graphId, { iri: iri.value.trim(), assertedTypes: assertedTypes.value, displayName: displayName.value.trim() }, run.signal); if (run.current()) { iri.value = ''; displayName.value = ''; assertedTypes.value = []; emit('saved') } } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false } }
</script>
<template><el-form :disabled="busy" inline @submit.prevent="save"><el-form-item :label="t('semantic.w.iri', 'IRI')"><el-input v-model="iri" placeholder="https://example.com/entity" /></el-form-item><el-form-item :label="t('semantic.w.type', 'Asserted types')"><el-select v-model="assertedTypes" multiple filterable><el-option v-for="value in classIris ?? []" :key="value" :value="value" :label="value" /></el-select></el-form-item><el-form-item :label="t('semantic.w.name')"><el-input v-model="displayName" /></el-form-item><el-button native-type="submit" type="primary" :loading="busy" :disabled="!iri.trim() || !displayName.trim()">{{ t('semantic.w.addEntity') }}</el-button><el-alert v-if="error" :title="error" type="error" :closable="false" /></el-form></template>
