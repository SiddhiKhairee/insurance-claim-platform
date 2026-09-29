// Small display helpers shared by the claimant and admin pages.

// ISO-8601 instant -> "YYYY-MM-DD HH:MM UTC" (UTC so every viewer sees the same recorded time).
export function formatInstant(iso) {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`
}

export function formatAmount(amount) {
  if (amount === null || amount === undefined) return ''
  return `$${Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export const APPEAL_STATUS_LABELS = {
  PENDING_REVIEW: 'Pending review',
  UPHELD: 'Denial upheld',
  OVERTURNED: 'Overturned — approved on appeal',
}

export function statusLabel(status) {
  return status === 'APPROVED_ON_APPEAL' ? 'Approved on appeal' : status
}
