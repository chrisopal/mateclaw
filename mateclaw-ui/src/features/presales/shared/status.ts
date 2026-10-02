import { presalesMessages } from './messages'

/** Display vocabulary only: these values are not domain or authorization allowlists. */
export type KnownStatusValue = keyof (typeof presalesMessages)['en-US']['presales']['states']
export interface KnownStatus {
  readonly kind: 'known'
  readonly value: KnownStatusValue
}
export interface UnknownStatus {
  readonly kind: 'unknown'
  readonly raw: string
}
export interface MissingStatus {
  readonly kind: 'missing'
}
export type PresalesStatus = KnownStatus | UnknownStatus | MissingStatus

/** Preserve exact historical wire text, including whitespace and letter case. */
export function classifyStatus(raw: string | undefined): PresalesStatus {
  if (!raw) return { kind: 'missing' }
  if (Object.hasOwn(presalesMessages['en-US'].presales.states, raw)) {
    return { kind: 'known', value: raw as KnownStatusValue }
  }
  return { kind: 'unknown', raw }
}

export function statusLabel(
  status: PresalesStatus,
  translate: (key: string, named?: Record<string, string>) => string,
): string {
  switch (status.kind) {
    case 'known':
      return translate(`presales.states.${status.value}`)
    case 'unknown':
      return translate('presales.unknown_status', { raw: status.raw })
    case 'missing':
      return '—'
  }
}
