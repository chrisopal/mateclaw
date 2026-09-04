// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const profileState = vi.hoisted(() => ({ enterprise: true }))
const routerMock = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
}))

vi.mock('@/styles/enterprise/profile', () => ({
  get isEnterpriseUi() {
    return profileState.enterprise
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => routerMock,
  useRoute: () => ({ query: {} }),
}))

vi.mock('@/api/index', () => ({
  authApi: {
    login: vi.fn(),
  },
  ssoApi: {
    providers: vi.fn(),
    authorize: vi.fn(),
    callback: vi.fn(),
    bind: vi.fn(),
  },
}))

const workspaceStoreMock = vi.hoisted(() => ({
  fetchWorkspaces: vi.fn(),
  can: vi.fn(() => true),
}))

vi.mock('@/stores/useWorkspaceStore', () => ({
  useWorkspaceStore: () => workspaceStoreMock,
}))

const systemSettingsStoreMock = vi.hoisted(() => ({
  load: vi.fn(),
}))

vi.mock('@/stores/useSystemSettingsStore', () => ({
  useSystemSettingsStore: () => systemSettingsStoreMock,
}))

import Login from '../Login.vue'
import { authApi, ssoApi } from '@/api/index'

const apps: Array<ReturnType<typeof createApp>> = []

function mountLogin() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(Login)
  app.use(createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: {
      'zh-CN': {
        login: {
          fields: { username: '用户名', password: '密码' },
          placeholders: { username: '请输入用户名', password: '请输入密码' },
          signIn: '登录',
          failed: '登录失败',
          hint: '默认账号：{username} / {password}',
        },
      },
    },
  }))
  app.mount(host)
  apps.push(app)
  return host
}

async function settle() {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

describe('Login enterprise presentation', () => {
  beforeEach(() => {
    profileState.enterprise = true
    vi.mocked(ssoApi.providers).mockResolvedValue({ data: [{ id: 'feishu', displayName: '飞书' }] } as never)
    vi.mocked(authApi.login).mockReset()
    vi.mocked(ssoApi.authorize).mockReset()
    workspaceStoreMock.fetchWorkspaces.mockResolvedValue(undefined)
    workspaceStoreMock.can.mockReturnValue(true)
    routerMock.push.mockReset()
    routerMock.replace.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    apps.splice(0).forEach(app => app.unmount())
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('uses the split enterprise layout and keeps SSO visible with accessible controls', async () => {
    const host = mountLogin()
    await settle()

    expect(host.querySelector('.enterprise-login-layout')).not.toBeNull()
    expect(host.querySelector('.enterprise-login-brand')?.textContent).toContain('AI')
    expect(host.querySelector('.enterprise-login-form-content h2')?.textContent).toContain('登录 MateClaw')
    expect(host.querySelector('.enterprise-login-form-content')?.textContent).toContain('使用你的工作台账号继续')
    expect(host.querySelectorAll('.enterprise-login-feature')).toHaveLength(3)
    expect(host.querySelector('label[for="login-username"]')?.textContent).toContain('用户名')
    expect(host.querySelector('label[for="login-password"]')?.textContent).toContain('密码')
    expect(host.querySelector<HTMLButtonElement>('.eye-btn')?.getAttribute('aria-label')).toContain('密码')
    expect(host.querySelector<HTMLButtonElement>('.sso-btn')?.textContent).toContain('飞书')
  })

  it('keeps classic presentation free of enterprise-only structure and labels', async () => {
    profileState.enterprise = false
    const host = mountLogin()
    await settle()

    expect(host.querySelector('.login-page')).not.toBeNull()
    expect(host.querySelector('.enterprise-login-layout')).toBeNull()
    expect(host.querySelector('.enterprise-login-brand')).toBeNull()
    expect(host.querySelector('label[for="login-username"]')).toBeNull()
  })

  it('submits the unchanged credentials payload and follows the existing dashboard route', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 'token-1', id: 42, username: 'alice', role: 'user' },
    } as never)
    const host = mountLogin()
    await settle()

    const username = host.querySelector<HTMLInputElement>('#login-username')!
    const password = host.querySelector<HTMLInputElement>('#login-password')!
    username.value = 'alice'
    username.dispatchEvent(new Event('input', { bubbles: true }))
    password.value = 'secret'
    password.dispatchEvent(new Event('input', { bubbles: true }))
    host.querySelector<HTMLFormElement>('.login-form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    await settle()

    expect(authApi.login).toHaveBeenCalledWith({ username: 'alice', password: 'secret' })
    expect(localStorage.getItem('token')).toBe('token-1')
    expect(routerMock.push).toHaveBeenCalledWith('/dashboard')
  })

  it('keeps the enabled SSO provider button wired to authorization', async () => {
    vi.mocked(ssoApi.authorize).mockResolvedValue({ data: { authorizeUrl: '/oauth/feishu' } } as never)
    const host = mountLogin()
    await settle()

    host.querySelector<HTMLButtonElement>('.sso-btn')!.click()
    await settle()

    expect(ssoApi.authorize).toHaveBeenCalledWith('feishu')
  })
})
