import { useState } from 'react'
import ClaimForm from './ClaimForm.jsx'
import ClaimStatus from './ClaimStatus.jsx'

function App() {
  const [submittedClaim, setSubmittedClaim] = useState(null)

  return (
    <main className="app">
      <header className="app-header">
        <h1>Group Claims Pipeline</h1>
        <p>Synthetic data only — portfolio project.</p>
      </header>

      <div className="card">
        {submittedClaim ? (
          <>
            <ClaimStatus claimId={submittedClaim.claimId} />
            <div className="actions">
              <button className="btn btn-secondary" onClick={() => setSubmittedClaim(null)}>
                Submit another claim
              </button>
            </div>
          </>
        ) : (
          <ClaimForm onSubmitted={setSubmittedClaim} />
        )}
      </div>
    </main>
  )
}

export default App
