import { Link, useParams } from 'react-router-dom'
import AssistantWidget from './AssistantWidget.jsx'
import ClaimStatus from './ClaimStatus.jsx'

function ClaimPage() {
  const { claimId } = useParams()

  return (
    <>
      <div className="card">
        {/* key: a different claim ID starts a fresh status view (polling, appeal form state). */}
        <ClaimStatus key={claimId} claimId={claimId} />
        <div className="actions">
          <Link className="btn btn-secondary" to="/">
            Submit another claim
          </Link>
        </div>
      </div>
      <div className="card">
        <AssistantWidget claimId={claimId} />
      </div>
    </>
  )
}

export default ClaimPage
