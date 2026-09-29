import { useState } from 'react'
import { submitAppeal } from './api.js'

// Mirrors claims-intake-service's limits (AppealService, spring.servlet.multipart). These checks
// are for fast feedback only: the server re-checks everything and decides the file type from the
// file's bytes, not its name.
export const MAX_FILES = 3
export const MAX_FILE_BYTES = 5 * 1024 * 1024
export const MAX_REASON_CHARS = 2000
const ALLOWED_TYPES = ['application/pdf', 'image/png', 'image/jpeg']
const ALLOWED_EXTENSIONS = /\.(pdf|png|jpe?g)$/i

export function validateAppeal(reason, files) {
  const trimmed = reason.trim()
  if (!trimmed) return 'Explain why you are appealing.'
  if (trimmed.length > MAX_REASON_CHARS) {
    return `Keep the reason under ${MAX_REASON_CHARS} characters.`
  }
  if (files.length === 0) return 'Attach at least one supporting document.'
  if (files.length > MAX_FILES) return `Attach at most ${MAX_FILES} documents.`
  for (const file of files) {
    if (!ALLOWED_TYPES.includes(file.type) && !ALLOWED_EXTENSIONS.test(file.name)) {
      return `${file.name}: only PDF, PNG or JPEG files are accepted.`
    }
    if (file.size > MAX_FILE_BYTES) return `${file.name} is larger than 5 MB.`
  }
  return null
}

function AppealForm({ claimId, onAppealed, onCancel }) {
  const [reason, setReason] = useState('')
  const [files, setFiles] = useState([])
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    const problem = validateAppeal(reason, files)
    if (problem) {
      setError(problem)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const claim = await submitAppeal(claimId, reason.trim(), files)
      onAppealed(claim)
    } catch (err) {
      setError(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="appeal-form" onSubmit={handleSubmit} noValidate>
      <h3>Appeal this decision</h3>
      <p className="status-meta">
        A person reviews every appeal, together with the rule engine&apos;s decision and your
        documents. The documents are synthetic in this demo; don&apos;t upload real personal or
        medical files.
      </p>

      {error && (
        <p role="alert" className="error-banner">
          {error}
        </p>
      )}

      <div className="field">
        <label htmlFor="appeal-reason">Reason for appeal</label>
        <textarea
          id="appeal-reason"
          value={reason}
          maxLength={MAX_REASON_CHARS}
          onChange={(event) => setReason(event.target.value)}
          disabled={submitting}
        />
      </div>

      <div className="field">
        <label htmlFor="appeal-files">Supporting documents</label>
        <input
          id="appeal-files"
          type="file"
          multiple
          accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
          onChange={(event) => setFiles(Array.from(event.target.files || []))}
          disabled={submitting}
        />
        <p className="field-hint">1 to 3 files; PDF, PNG or JPEG; up to 5 MB each.</p>
      </div>

      <div className="button-row">
        <button type="submit" className="btn" disabled={submitting}>
          {submitting ? 'Submitting appeal…' : 'Submit appeal'}
        </button>
        {onCancel && (
          <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={submitting}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

export default AppealForm
