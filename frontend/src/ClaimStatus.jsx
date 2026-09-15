import { useEffect, useState } from 'react'
import { getClaim } from './api.js'

const POLL_INTERVAL_MS = 2000
const MAX_ATTEMPTS = 30
const TERMINAL_STATUSES = ['APPROVED', 'DENIED']

function ClaimStatus({ claimId }) {
  const [claim, setClaim] = useState(null)
  const [phase, setPhase] = useState('polling') // polling | timedOut | error
  const [errorMessage, setErrorMessage] = useState(null)
  const [pollTrigger, setPollTrigger] = useState(0)

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

  const status = claim?.status
  const isTerminal = TERMINAL_STATUSES.includes(status)

  return (
    <section>
      <h2>Claim Status</h2>
      <p className="status-meta">Claim ID: {claimId}</p>

      {status && (
        <span className={`status-badge status-${status.toLowerCase()}`}>{status}</span>
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
    </section>
  )
}

export default ClaimStatus
