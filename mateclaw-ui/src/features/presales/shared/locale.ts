import { currentLocale } from '@/i18n'
export function label(zh: string, en: string): string { return currentLocale.value === 'zh-CN' ? zh : en }
