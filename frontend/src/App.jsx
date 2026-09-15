import { useState } from 'react'
import ClaimForm from './ClaimForm.jsx'
import ClaimStatus from './ClaimStatus.jsx'

function App() {
  const [submittedClaim, setSubmittedClaim] = useState(null)

  return (
    <main>
      <h1>Group Claims Pipeline</h1>
      <p>Synthetic data only — portfolio project.</p>

      {submittedClaim ? (
        <>
          <ClaimStatus claimId={submittedClaim.claimId} />
          <button onClick={() => setSubmittedClaim(null)}>Submit another claim</button>
        </>
      ) : (
        <ClaimForm onSubmitted={setSubmittedClaim} />
      )}
    </main>
  )
}

export default App
