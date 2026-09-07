<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ValidationReport, Violation } from '../../api/types'
defineProps<{ report: ValidationReport | null; errors?: Violation[]; dirty: boolean }>()
defineEmits<{ locate: [path: string] }>()
const { t, te } = useI18n()
</script>
<template>
  <section class="semantic-panel" aria-live="polite">
    <h3>{{ t('semantic.validation') }}</h3>
    <p class="semantic-muted">
      {{
        t(
          dirty
            ? 'semantic.validationStale'
            : report?.valid
              ? 'semantic.validationPassed'
              : 'semantic.validationRequired',
        )
      }}
    </p>
    <el-table
      v-if="report?.violations.length || errors?.length"
      :data="[...(errors ?? []), ...(report?.violations ?? [])]"
      ><el-table-column prop="path" :label="t('semantic.field')" min-width="160" /><el-table-column
        :label="t('semantic.issue')"
        min-width="200"
        ><template #default="{ row }">{{
          te('semantic.violations.' + row.code) ? t('semantic.violations.' + row.code) : row.message
        }}</template></el-table-column
      ><el-table-column width="110"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="$emit('locate', row.path)">{{
            t('semantic.locate')
          }}</el-button></template
        ></el-table-column
      ></el-table
    >
  </section>
</template>
