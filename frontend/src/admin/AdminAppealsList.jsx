import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { APPEAL_STATUS_LABELS, formatAmount, formatInstant } from '../format.js'
import { listAppeals } from './adminApi.js'
import { useAdminErrorHandler } from './RequireAdmin.jsx'

const FILTERS = [
  { value: '', label: 'All' },
  { value: 'PENDING_REVIEW', label: 'Pending review' },
  { value: 'UPHELD', label: 'Upheld' },
  { value: 'OVERTURNED', label: 'Overturned' },
]

function AdminAppealsList() {
  const handleError = useAdminErrorHandler()
  const [filter, setFilter] = useState('')
  const [appeals, setAppeals] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    listAppeals(filter)
      .then((rows) => {
        if (!cancelled) setAppeals(rows)
      })
      .catch((err) => {
        if (!cancelled) setError(handleError(err))
      })
    return () => {
      cancelled = true
    }
  }, [filter, handleError])

  return (
    <div className="card">
      <h2>Appealed claims</h2>
      <p className="status-meta">
        Pending appeals first. Each claim&apos;s first decision was made by the rule engine; an
        appeal is decided by a person.
      </p>

      <div className="field">
        <label htmlFor="appeal-filter">Show</label>
        <select id="appeal-filter" value={filter} onChange={(e) => setFilter(e.target.value)}>
          {FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <p role="alert" className="error-banner">
          {error}
        </p>
      )}
      {!error && appeals === null && <p className="status-note">Loading…</p>}
      {appeals?.length === 0 && <p className="status-note">No appeals to show.</p>}

      {appeals?.length > 0 && (
        <div className="table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>Claim</th>
                <th>Plan</th>
                <th>Amount</th>
                <th>Engine decision</th>
                <th>Appeal</th>
                <th>Submitted</th>
                <th>Docs</th>
              </tr>
            </thead>
            <tbody>
              {appeals.map((row) => (
                <tr key={row.claimId}>
                  <td>
                    <Link to={`/admin/appeals/${row.claimId}`}>{row.claimId.slice(0, 8)}…</Link>
                  </td>
                  <td>{row.planType}</td>
                  <td>{formatAmount(row.amountRequested)}</td>
                  <td>{row.engineStatus}</td>
                  <td>{APPEAL_STATUS_LABELS[row.appealStatus] || row.appealStatus}</td>
                  <td>{formatInstant(row.appealSubmittedAt)}</td>
                  <td>{row.documentCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default AdminAppealsList
