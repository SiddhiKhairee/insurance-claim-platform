const BASE_URL = import.meta.env.VITE_CLAIMS_API_URL || 'http://localhost:8082'

export async function submitClaim(payload) {
  const response = await fetch(`${BASE_URL}/claims`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })

  if (!response.ok) {
    throw new Error(`Failed to submit claim (status ${response.status})`)
  }

  return response.json()
}

export async function getClaim(claimId) {
  const response = await fetch(`${BASE_URL}/claims/${claimId}`)

  if (response.status === 404) {
    throw new Error('Claim not found')
  }

  if (!response.ok) {
    throw new Error(`Failed to fetch claim status (status ${response.status})`)
  }

  return response.json()
}
