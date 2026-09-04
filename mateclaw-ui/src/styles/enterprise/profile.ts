export type UiProfile = 'classic' | 'enterprise'

/** Only opt in for an explicit setting; an invalid build must not silently reskin the app. */
export function resolveUiProfile(value: unknown): UiProfile {
  return value === 'enterprise' ? 'enterprise' : 'classic'
}

export function applyUiProfile(root: HTMLElement, value: unknown): UiProfile {
  const profile = resolveUiProfile(value)
  root.dataset.uiProfile = profile
  // The tab icon is independent of the in-page logos; keep it on the same profile.
  const favicon = root.ownerDocument.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (favicon) {
    favicon.setAttribute('href', profile === 'enterprise' ? '/logo/mateclaw-enterprise-3d-v1.png' : '/logo/favicon.ico')
    favicon.type = profile === 'enterprise' ? 'image/png' : 'image/x-icon'
  }
  return profile
}

export const uiProfile = resolveUiProfile(import.meta.env.VITE_UI_PROFILE)
export const isEnterpriseUi = uiProfile === 'enterprise'
