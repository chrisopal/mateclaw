import type { Proposal } from '../api/workbenchTypes'

type Translate = (key: string, values?: Record<string, string>) => string

/** Business validity is independent of the current recorded revision. */
export function formatValidity(value: Pick<Proposal, 'validityKind' | 'validFrom' | 'validTo'>, t: Translate): string {
  if (value.validityKind === 'UNKNOWN') return t('semantic.w.unknown')
  if (!value.validFrom && !value.validTo) return t('semantic.w.allTime')
  if (!value.validFrom) return t('semantic.w.untilTime', { time: value.validTo! })
  if (!value.validTo) return t('semantic.w.fromTime', { time: value.validFrom })
  return `${value.validFrom} — ${value.validTo}`
}
