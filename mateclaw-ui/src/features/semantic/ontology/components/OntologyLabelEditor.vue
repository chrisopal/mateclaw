<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AxiomDescriptor, AxiomEdit } from '../../api/types'
import { editableLabel, replaceLabel } from '../labelEditing'
const props = defineProps<{ axioms: AxiomDescriptor[]; disabled: boolean }>()
const emit = defineEmits<{ apply: [changes: AxiomEdit[]] }>()
const labels = computed(() => props.axioms.map(editableLabel).filter(label => label !== null))
const selected = ref(''), value = ref(''), language = ref(''), error = ref('')
watch([selected, labels], () => {
  const label = labels.value.find(item => item.axiomId === selected.value)
  value.value = label?.value ?? ''; language.value = label?.language ?? ''; error.value = ''
})
function apply() {
  if (props.disabled) return
  const label = labels.value.find(item => item.axiomId === selected.value)
  if (!label) return
  try { emit('apply', replaceLabel(label, value.value, language.value)); error.value = '' }
  catch (cause) { error.value = (cause as Error).message }
}
</script>
<template>
  <el-form v-if="labels.length" label-position="top" :disabled="disabled" @submit.prevent="apply">
    <h3>编辑标签</h3>
    <p class="semantic-muted">修改已有标签；其他公理保持不变。请先保存文档修改。</p>
    <el-form-item label="标签对象"><el-select v-model="selected" placeholder="选择标签"><el-option v-for="label in labels" :key="label.axiomId" :value="label.axiomId" :label="`${label.subject} · ${label.value} (${label.language || '无语言'})`" /></el-select></el-form-item>
    <el-form-item label="标签内容"><el-input v-model="value" /></el-form-item>
    <el-form-item label="语言标签"><el-input v-model="language" placeholder="例如 zh-CN；可留空" /></el-form-item>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button native-type="submit" type="primary" :disabled="disabled || !selected || !value.trim()">保存标签</el-button>
  </el-form>
</template>
