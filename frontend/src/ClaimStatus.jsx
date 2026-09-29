import { useEffect, useState } from 'react'
import { getClaim } from './api.js'
import AppealForm from './AppealForm.jsx'
import { APPEAL_STATUS_LABELS, formatInstant, statusLabel } from './format.js'

const POLL_INTERVAL_MS = 2000
const MAX_ATTEMPTS = 30
const TERMINAL_STATUSES = ['APPROVED', 'DENIED']

function ClaimStatus({ claimId }) {
  const [claim, setClaim] = useState(null)
  const [phase, setPhase] = useState('polling') // polling | timedOut | error
  const [errorMessage, setErrorMessage] = useState(null)
  const [pollTrigger, setPollTrigger] = useState(0)
  const [appealing, setAppealing] = useState(false)

  useEffect(() => {
    let cancelled = false
    let timeoutId
    let attempts = 0

    setPhase('polling')
    setErrorMessage(null)

    async function poll() {
      attempts += 1
      try {
        const result = await getClaim(claimId)
        if (cancelled) return

        setClaim(result)

        if (TERMINAL_STATUSES.includes(result.status)) {
          return
        }
        if (attempts >= MAX_ATTEMPTS) {
          setPhase('timedOut')
          return
        }
        timeoutId = setTimeout(poll, POLL_INTERVAL_MS)
      } catch (err) {
        if (cancelled) return
        setErrorMessage(err.message)
        setPhase('error')
      }
    }

    poll()

    return () => {
      cancelled = true
      clearTimeout(timeoutId)
    }
  }, [claimId, pollTrigger])

  function handleRetry() {
    setPollTrigger((current) => current + 1)
  }

  function handleAppealed(updatedClaim) {
    setClaim(updatedClaim)
    setAppealing(false)
  }

  // `status` is always the rule engine's decision. `displayStatus` differs from it only when a
  // person overturned the denial on appeal (APPROVED_ON_APPEAL).
  const status = claim?.status
  const shownStatus = claim?.displayStatus || status
  const isTerminal = TERMINAL_STATUSES.includes(status)
  const appeal = claim?.appeal
  const canAppeal = status === 'DENIED' && !appeal

  return (
    <section>
      <h2>Claim Status</h2>
      <p className="status-meta">Claim ID: {claimId}</p>

      {shownStatus && (
        <span className={`status-badge status-${shownStatus.toLowerCase()}`}>
          {statusLabel(shownStatus)}
        </span>
      )}

      {phase === 'polling' && !isTerminal && <p className="status-note">Checking for updates…</p>}

      {phase === 'timedOut' && !isTerminal && (
        <div role="status" className="status-processing">
          <p>Still processing — this is taking longer than expected.</p>
          <button className="btn btn-secondary" onClick={handleRetry}>
            Check again
          </button>
        </div>
      )}

      {phase === 'error' && (
        <div role="alert" className="status-error">
          <p>Couldn&apos;t check claim status: {errorMessage}</p>
          <button className="btn btn-secondary" onClick={handleRetry}>
            Retry
          </button>
        </div>
      )}

      {isTerminal && (
        <div>
          {appeal && <p className="decision-heading">Rule engine decision: {status}</p>}
          {claim.decisionReason && <p className="decision-reason">Reason: {claim.decisionReason}</p>}
          {claim.ruleTrace?.length > 0 && (
            <ul className="rule-trace">
              {claim.ruleTrace.map((rule) => (
                <li key={rule}>{rule}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {appeal && (
        <div className="appeal-block" data-testid="appeal-block">
          <p className="decision-heading">
            Appeal: {APPEAL_STATUS_LABELS[appeal.status] || appeal.status}
          </p>
          <p className="status-meta">Submitted {formatInstant(appeal.submittedAt)}</p>
          {appeal.status === 'PENDING_REVIEW' ? (
            <>
              <p>A reviewer will look at your appeal and documents. The rule engine&apos;s decision
                above stands until then.</p>
              <button className="btn btn-secondary" onClick={handleRetry}>
                Check for an update
              </button>
            </>
          ) : (
            <>
              <p className="status-meta">Decided {formatInstant(appeal.decidedAt)} by a reviewer</p>
              {appeal.reviewerNote && (
                <p className="reviewer-note">
                  <strong>Reviewer&apos;s note:</strong> {appeal.reviewerNote}
                </p>
              )}
            </>
          )}
        </div>
      )}

      {canAppeal &&
        (appealing ? (
          <AppealForm
            claimId={claimId}
            onAppealed={handleAppealed}
            onCancel={() => setAppealing(false)}
          />
        ) : (
          <div className="actions">
            <button className="btn" onClick={() => setAppealing(true)}>
              Appeal this decision
            </button>
          </div>
        ))}
    </section>
  )
}

export default ClaimStatus
