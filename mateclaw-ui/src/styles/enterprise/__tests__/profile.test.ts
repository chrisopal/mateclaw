import { describe, expect, it } from 'vitest'
import { applyUiProfile, resolveUiProfile } from '../profile'

describe('enterprise UI profile boundary', () => {
  it.each([undefined, null, '', 'classic', 'Enterprise', 'unexpected', {}])('keeps classic for an unrecognized setting: %s', value => {
    expect(resolveUiProfile(value)).toBe('classic')
  })

  it('activates enterprise without changing the saved light/dark mode or other root attributes', () => {
    const root = document.createElement('div')
    root.className = 'dark user-class'
    root.lang = 'zh-CN'
    expect(applyUiProfile(root, 'enterprise')).toBe('enterprise')
    expect(root.dataset.uiProfile).toBe('enterprise')
    expect(root.className).toBe('dark user-class')
    expect(root.lang).toBe('zh-CN')
    applyUiProfile(root, 'classic')
    expect(root.dataset.uiProfile).toBe('classic')
    expect(root.className).toBe('dark user-class')
  })

  it('switches the browser tab icon with the profile and restores classic without duplicating links', () => {
    const doc = document.implementation.createHTMLDocument('MateClaw')
    doc.head.innerHTML = '<link rel="icon" href="/logo/favicon.ico"><link rel="apple-touch-icon" href="/touch.png">'
    const favicon = doc.querySelector<HTMLLinkElement>('link[rel="icon"]')!

    applyUiProfile(doc.documentElement, 'enterprise')
    expect(favicon.getAttribute('href')).toBe('/logo/mateclaw-enterprise-3d-v1.png')
    expect(favicon.type).toBe('image/png')
    applyUiProfile(doc.documentElement, 'enterprise')
    expect(doc.querySelectorAll('link[rel="icon"]')).toHaveLength(1)
    expect(doc.querySelector('link[rel="apple-touch-icon"]')?.getAttribute('href')).toBe('/touch.png')

    applyUiProfile(doc.documentElement, 'classic')
    expect(favicon.getAttribute('href')).toBe('/logo/favicon.ico')
    expect(favicon.type).toBe('image/x-icon')
  })
})
