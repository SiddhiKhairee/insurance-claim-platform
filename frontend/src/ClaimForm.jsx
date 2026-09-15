import { useState } from 'react'
import { submitClaim } from './api.js'

const PLAN_TYPES = ['disability', 'dental', 'vision', 'life']

function ClaimForm({ onSubmitted }) {
  const [employeeId, setEmployeeId] = useState('')
  const [planType, setPlanType] = useState(PLAN_TYPES[0])
  const [amountRequested, setAmountRequested] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  function validate() {
    if (!employeeId.trim()) return 'Employee ID is required.'
    const amount = Number(amountRequested)
    if (!amountRequested || Number.isNaN(amount) || amount <= 0) {
      return 'Amount requested must be a positive number.'
    }
    return null
  }

  async function handleSubmit(event) {
    event.preventDefault()
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }

    setError(null)
    setSubmitting(true)
    try {
      const claim = await submitClaim({
        employeeId: employeeId.trim(),
        planType,
        amountRequested: Number(amountRequested),
        description,
      })
      onSubmitted(claim)
    } catch (err) {
      setError(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <h2>Submit a Claim</h2>

      {error && (
        <p role="alert" className="error-banner">
          {error}
        </p>
      )}

      <div className="field">
        <label htmlFor="employeeId">Employee ID</label>
        <input
          id="employeeId"
          value={employeeId}
          onChange={(event) => setEmployeeId(event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="planType">Plan Type</label>
        <select
          id="planType"
          value={planType}
          onChange={(event) => setPlanType(event.target.value)}
        >
          {PLAN_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="amountRequested">Amount Requested</label>
        <input
          id="amountRequested"
          type="number"
          value={amountRequested}
          onChange={(event) => setAmountRequested(event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="description">Description</label>
        <textarea
          id="description"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </div>

      <button type="submit" className="btn" disabled={submitting}>
        {submitting ? 'Submitting…' : 'Submit Claim'}
      </button>
    </form>
  )
}

export default ClaimForm
