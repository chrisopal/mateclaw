<template>
  <div class="enterprise-login-layout">
    <section class="enterprise-login-brand" aria-labelledby="enterprise-login-brand-title">
      <div class="enterprise-login-brand-mark">
        <img src="/logo/mateclaw-enterprise-3d-v1.png" alt="MateClaw" class="enterprise-login-logo" />
        <span class="enterprise-login-brand-name">Mate<span>Claw</span></span>
      </div>
      <div class="enterprise-login-brand-copy">
        <p class="enterprise-login-kicker">{{ t('brand.kicker') }}</p>
        <h1 id="enterprise-login-brand-title">{{ t('brand.title') }}</h1>
        <p class="enterprise-login-subtitle">{{ t('brand.subtitle') }}</p>
      </div>
      <ul class="enterprise-login-features" :aria-label="t('features.label')">
        <li v-for="feature in features" :key="feature.key" class="enterprise-login-feature">
          <component :is="feature.icon" class="enterprise-login-feature-icon" aria-hidden="true" />
          <span>{{ t(feature.key) }}</span>
        </li>
      </ul>
    </section>

    <main class="enterprise-login-form-panel">
      <div class="enterprise-login-form-content">
        <h2>{{ t('form.title') }}</h2>
        <p>{{ t('form.accountHint') }}</p>
        <slot />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { Connection, Collection, SetUp } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

const features = [
  { key: 'features.agents', icon: Connection },
  { key: 'features.knowledge', icon: Collection },
  { key: 'features.workflow', icon: SetUp },
] as const

const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      brand: {
        kicker: 'AI 操作系统',
        title: '让 AI 真正进入持续工作的系统',
        subtitle: '安全、可控、可扩展的智能工作台。',
      },
      form: {
        title: '登录 MateClaw',
        accountHint: '使用你的工作台账号继续',
      },
      features: {
        label: '工作台能力',
        agents: 'Agents 智能协作',
        knowledge: 'Knowledge 知识沉淀',
        workflow: 'Workflow 自动执行',
      },
    },
    'en-US': {
      brand: {
        kicker: 'AI OPERATING SYSTEM',
        title: 'Put AI inside a system that keeps working.',
        subtitle: 'A secure, controllable workspace for intelligent operations.',
      },
      form: {
        title: 'Sign in to MateClaw',
        accountHint: 'Use your workspace account to continue',
      },
      features: {
        label: 'Workspace capabilities',
        agents: 'Agents for intelligent collaboration',
        knowledge: 'Knowledge that stays organized',
        workflow: 'Workflow automation',
      },
    },
  },
})
</script>
