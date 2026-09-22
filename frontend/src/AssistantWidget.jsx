import { useEffect, useRef, useState } from 'react'
import { askAssistant } from './api.js'

const MAX_QUESTION_LENGTH = 1000 // mirrors the RAG service's request validation
const CLAIM_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/

// Hints for the "couldn't answer reliably" state, keyed by the service's abstention `reason`.
const ABSTAIN_HINTS = {
  claim_not_found: 'Check the claim ID and try again.',
  claims_service_unavailable: "Claim details aren't available right now. Try again shortly.",
}
const DEFAULT_ABSTAIN_HINT =
  'Try rephrasing, or name a plan (dental, vision, disability or life) in your question.'

function citationLabel(citation) {
  if (citation.source === 'claim') {
    return `Claim ${citation.claimId}`
  }
  return `${citation.planType} plan: ${citation.section}`
}

function AssistantResult({ result }) {
  const { outcome, answer, citations, claim, reason } = result

  if (outcome === 'answered') {
    return (
      <div className="assistant-result assistant-answered" data-outcome="answered">
        <p>{answer}</p>
        {citations?.length > 0 && (
          <div>
            <p className="assistant-sources-title">Sources</p>
            <ul className="assistant-sources">
              {citations.map((citation, index) => (
                <li key={index}>{citationLabel(citation)}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    )
  }

  if (outcome === 'refused') {
    return (
      <div role="status" className="assistant-result assistant-refused" data-outcome="refused">
        <p className="assistant-result-title">Decisions come from the rule engine</p>
        <p>{answer}</p>
        {claim && (
          <div>
            <span className={`status-badge status-${String(claim.status).toLowerCase()}`}>
              {claim.status}
            </span>
            {claim.ruleTrace?.length > 0 && (
              <ul className="rule-trace">
                {claim.ruleTrace.map((rule) => (
                  <li key={rule}>{rule}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    )
  }

  // abstained
  return (
    <div role="status" className="assistant-result assistant-abstained" data-outcome="abstained">
      <p className="assistant-result-title">Couldn&apos;t answer that reliably</p>
      <p>{answer}</p>
      <p>{ABSTAIN_HINTS[reason] || DEFAULT_ABSTAIN_HINT}</p>
    </div>
  )
}

function AssistantWidget({ claimId: claimIdProp }) {
  const [question, setQuestion] = useState('')
  const [claimId, setClaimId] = useState(claimIdProp || '')
  const [status, setStatus] = useState('idle') // idle | loading | done | error
  const [result, setResult] = useState(null)
  const [errorMessage, setErrorMessage] = useState(null)
  const requestId = useRef(0)

  // Prefill from the claim the user just submitted, but let them edit or clear it.
  useEffect(() => {
    setClaimId(claimIdProp || '')
  }, [claimIdProp])

  function validate() {
    if (!question.trim()) return 'Enter a question.'
    if (question.length > MAX_QUESTION_LENGTH) {
      return `Keep the question under ${MAX_QUESTION_LENGTH} characters.`
    }
    if (claimId.trim() && !CLAIM_ID_PATTERN.test(claimId.trim())) {
      return 'Claim ID may only contain letters, numbers and hyphens.'
    }
    return null
  }

  async function ask() {
    const problem = validate()
    if (problem) {
      setErrorMessage(problem)
      setStatus('error')
      return
    }

    const thisRequest = ++requestId.current
    setStatus('loading')
    setErrorMessage(null)
    try {
      const response = await askAssistant(question.trim(), claimId.trim() || undefined)
      if (thisRequest !== requestId.current) return
      setResult(response)
      setStatus('done')
    } catch (err) {
      if (thisRequest !== requestId.current) return
      setErrorMessage(err.message)
      setStatus('error')
    }
  }

  function handleSubmit(event) {
    event.preventDefault()
    ask()
  }

  const loading = status === 'loading'

  return (
    <section>
      <h2>Ask about a plan or claim</h2>
      <p className="status-meta">
        Answers come only from the synthetic plan documents. This assistant explains decisions; it
        never makes them.
      </p>

      <form onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="assistant-question">Question</label>
          <textarea
            id="assistant-question"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            disabled={loading}
          />
        </div>
        <div className="field">
          <label htmlFor="assistant-claim-id">Claim ID (optional)</label>
          <input
            id="assistant-claim-id"
            value={claimId}
            onChange={(event) => setClaimId(event.target.value)}
            disabled={loading}
          />
        </div>
        <button type="submit" className="btn" disabled={loading}>
          {loading ? 'Asking…' : 'Ask'}
        </button>
      </form>

      {loading && (
        <p role="status" className="status-note">
          Working on it. This can take up to a minute.
        </p>
      )}

      {status === 'error' && (
        <div role="alert" className="status-error">
          <p>{errorMessage}</p>
          {!validate() && (
            <button className="btn btn-secondary" onClick={ask}>
              Retry
            </button>
          )}
        </div>
      )}

      {status === 'done' && result && <AssistantResult result={result} />}
    </section>
  )
}

export default AssistantWidget
