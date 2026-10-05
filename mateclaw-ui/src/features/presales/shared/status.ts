import { presalesMessages } from './messages'

/** Display vocabulary only: these values are not domain or authorization allowlists. */
export type KnownStatusValue = keyof (typeof presalesMessages)['en-US']['presales']['states']
export interface KnownStatus<Value extends KnownStatusValue = KnownStatusValue> {
  readonly kind: 'known'
  readonly value: Value
}
export interface UnknownStatus {
  readonly kind: 'unknown'
  readonly raw: string
}
export interface MissingStatus {
  readonly kind: 'missing'
}
export type PresalesStatus<Value extends KnownStatusValue = KnownStatusValue> =
  | KnownStatus<Value>
  | UnknownStatus
  | MissingStatus

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

/** Current object contracts, independent of the shared translation vocabulary. */
const domainStates = {
  clarification: ['OPEN', 'ANSWERED'],
  task: ['DRAFT', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'],
  release: ['PENDING', 'APPROVED', 'PUBLISHED'],
  fitGap: ['FIT', 'CONFIG', 'EXTEND', 'PARTNER', 'GAP', 'UNKNOWN'],
  reviewIssue: ['OPEN', 'RESOLVED', 'ACCEPTED'],
  reviewSeverity: ['BLOCKER', 'WARNING', 'INFO'],
  response: ['FULL', 'PARTIAL', 'CONDITIONAL', 'EXCLUDED', 'UNHANDLED'],
} as const satisfies Record<string, readonly KnownStatusValue[]>

export type StatusDomain = keyof typeof domainStates
export type DomainStatusValue<Domain extends StatusDomain> = (typeof domainStates)[Domain][number]
export type DomainStatus<Domain extends StatusDomain> = PresalesStatus<DomainStatusValue<Domain>>
export type StateLabel = (raw: string | undefined, domain?: StatusDomain) => string

export function classifyDomainStatus<Domain extends StatusDomain>(
  raw: string | undefined,
  domain: Domain,
): DomainStatus<Domain> {
  if (!raw) return { kind: 'missing' }
  if (domainStates[domain].some((value) => value === raw)) {
    return { kind: 'known', value: raw as DomainStatusValue<Domain> }
  }
  return { kind: 'unknown', raw }
}

export function isDomainStatus<Domain extends StatusDomain>(
  domain: Domain,
  raw: string | undefined,
  expected: DomainStatusValue<NoInfer<Domain>>,
): boolean {
  const status = classifyDomainStatus(raw, domain)
  return status.kind === 'known' && status.value === expected
}
