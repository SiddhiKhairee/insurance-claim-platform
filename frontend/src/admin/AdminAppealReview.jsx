import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { APPEAL_STATUS_LABELS, formatAmount, formatInstant, statusLabel } from '../format.js'
import { decideAppeal, downloadDocument, getAppeal } from './adminApi.js'
import { useAdminErrorHandler } from './RequireAdmin.jsx'

const MAX_NOTE_CHARS = 2000 // AdminAppealService.MAX_NOTE_CHARS

function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function DecisionForm({ claimId, onDecided }) {
  const handleError = useAdminErrorHandler()
  const [decision, setDecision] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const ready = decision && note.trim()

  async function handleSubmit(event) {
    event.preventDefault()
    if (!ready) return
    setError(null)
    setSubmitting(true)
    try {
      onDecided(await decideAppeal(claimId, decision, note.trim()))
    } catch (err) {
      setError(handleError(err))
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="decision-form" noValidate>
      <h3>Your decision</h3>
      <p className="status-meta">
        Upholding keeps the rule engine&apos;s denial. Overturning approves the claim on appeal.
        Either way the engine&apos;s decision and ruleTrace stay on record unchanged. A decision is
        final.
      </p>
      {error && (
        <p role="alert" className="error-banner">
          {error}
        </p>
      )}
      <fieldset className="field radio-group">
        <legend>Decision</legend>
        <label>
          <input
            type="radio"
            name="decision"
            value="UPHOLD"
            checked={decision === 'UPHOLD'}
            onChange={() => setDecision('UPHOLD')}
          />
          Uphold the denial
        </label>
        <label>
          <input
            type="radio"
            name="decision"
            value="OVERTURN"
            checked={decision === 'OVERTURN'}
            onChange={() => setDecision('OVERTURN')}
          />
          Overturn and approve
        </label>
      </fieldset>
      <div className="field">
        <label htmlFor="decision-note">Note (required)</label>
        <textarea
          id="decision-note"
          aria-describedby="decision-note-hint"
          value={note}
          maxLength={MAX_NOTE_CHARS}
          onChange={(e) => setNote(e.target.value)}
        />
        <p id="decision-note-hint" className="field-hint note-visible">
          This note is visible to the claimant.
        </p>
      </div>
      <button type="submit" className="btn" disabled={!ready || submitting}>
        {submitting ? 'Recording…' : 'Record decision'}
      </button>
    </form>
  )
}

function AdminAppealReview() {
  const { claimId } = useParams()
  const handleError = useAdminErrorHandler()
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)
  const [downloadError, setDownloadError] = useState(null)

  useEffect(() => {
    let cancelled = false
    getAppeal(claimId)
      .then((body) => {
        if (!cancelled) setDetail(body)
      })
      .catch((err) => {
        if (!cancelled) setError(handleError(err))
      })
    return () => {
      cancelled = true
    }
  }, [claimId, handleError])

  async function handleDownload(document) {
    setDownloadError(null)
    try {
      await downloadDocument(claimId, document)
    } catch (err) {
      setDownloadError(handleError(err))
    }
  }

  const back = (
    <p>
      <Link to="/admin/appeals">← All appeals</Link>
    </p>
  )

  if (error) {
    return (
      <div className="card">
        {back}
        <p role="alert" className="error-banner">
          {error}
        </p>
      </div>
    )
  }
  if (!detail) {
    return (
      <div className="card">
        {back}
        <p className="status-note">Loading…</p>
      </div>
    )
  }

  const { appeal } = detail
  const decided = appeal.status !== 'PENDING_REVIEW'

  return (
    <div className="card">
      {back}
      <h2>Appeal review</h2>
      <p className="status-meta">Claim ID: {detail.claimId}</p>
      <span className={`status-badge status-${String(detail.displayStatus).toLowerCase()}`}>
        {statusLabel(detail.displayStatus)}
      </span>

      <section className="review-section">
        <h3>Rule engine decision: {detail.engineStatus}</h3>
        <p className="status-meta">
          {detail.planType} plan · {formatAmount(detail.amountRequested)} · employee{' '}
          {detail.employeeId} · adjudicated {formatInstant(detail.adjudicatedAt)}
        </p>
        {detail.decisionReason && (
          <p className="decision-reason">Reason: {detail.decisionReason}</p>
        )}
        {detail.ruleTrace?.length > 0 && (
          <ul className="rule-trace" aria-label="ruleTrace">
            {detail.ruleTrace.map((rule) => (
              <li key={rule}>{rule}</li>
            ))}
          </ul>
        )}
        {detail.description && (
          <p className="quoted-text">
            <strong>Claim description (from the claimant):</strong> {detail.description}
          </p>
        )}
      </section>

      <section className="review-section">
        <h3>Appeal: {APPEAL_STATUS_LABELS[appeal.status] || appeal.status}</h3>
        <p className="status-meta">Submitted {formatInstant(appeal.submittedAt)}</p>
        <p className="quoted-text">
          <strong>Claimant&apos;s reason:</strong> {appeal.reason}
        </p>
        <h4>Supporting documents</h4>
        {downloadError && (
          <p role="alert" className="error-banner">
            {downloadError}
          </p>
        )}
        <ul className="document-list">
          {appeal.documents.map((doc) => (
            <li key={doc.docId}>
              <button type="button" className="link-button" onClick={() => handleDownload(doc)}>
                {doc.filename}
              </button>{' '}
              <span className="status-meta">
                ({doc.contentType}, {formatSize(doc.sizeBytes)})
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="review-section">
        {decided ? (
          <div data-testid="recorded-decision">
            <h3>Recorded decision</h3>
            <p>
              {APPEAL_STATUS_LABELS[appeal.status]} by <strong>{appeal.reviewer}</strong> on{' '}
              {formatInstant(appeal.decidedAt)}.
            </p>
            <p className="quoted-text">
              <strong>Note (visible to the claimant):</strong> {appeal.reviewerNote}
            </p>
          </div>
        ) : (
          <DecisionForm claimId={detail.claimId} onDecided={setDetail} />
        )}
      </section>
    </div>
  )
}

export default AdminAppealReview
