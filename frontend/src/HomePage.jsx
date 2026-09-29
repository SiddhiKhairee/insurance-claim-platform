import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AssistantWidget from './AssistantWidget.jsx'
import ClaimForm from './ClaimForm.jsx'

const CLAIM_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/

function ClaimLookup() {
  const navigate = useNavigate()
  const [claimId, setClaimId] = useState('')
  const [error, setError] = useState(null)

  function handleSubmit(event) {
    event.preventDefault()
    const id = claimId.trim()
    if (!CLAIM_ID_PATTERN.test(id)) {
      setError('Enter a claim ID (letters, numbers and hyphens).')
      return
    }
    navigate(`/claims/${id}`)
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2>Check a claim</h2>
      <p className="status-meta">
        There are no claimant accounts in this demo: anyone with a claim&apos;s ID can view it and,
        if it was denied, appeal it.
      </p>
      {error && (
        <p role="alert" className="error-banner">
          {error}
        </p>
      )}
      <div className="field">
        <label htmlFor="lookup-claim-id">Claim ID</label>
        <input id="lookup-claim-id" value={claimId} onChange={(e) => setClaimId(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-secondary">
        View claim
      </button>
    </form>
  )
}

function HomePage() {
  const navigate = useNavigate()

  return (
    <>
      <div className="card">
        <ClaimForm onSubmitted={(claim) => navigate(`/claims/${claim.claimId}`)} />
      </div>
      <div className="card">
        <ClaimLookup />
      </div>
      <div className="card">
        <AssistantWidget />
      </div>
    </>
  )
}

export default HomePage
