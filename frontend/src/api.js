export const BASE_URL = import.meta.env.VITE_CLAIMS_API_URL || 'http://localhost:8082'
const RAG_URL = import.meta.env.VITE_RAG_API_URL || 'http://localhost:8000'

// Two hosted-LLM providers at 20s each plus the groundedness model, so allow longer than the
// claims calls above.
const ASK_TIMEOUT_MS = 60000

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
  const response = await fetch(`${BASE_URL}/claims/${encodeURIComponent(claimId)}`)

  if (response.status === 404) {
    throw new Error('Claim not found')
  }

  if (!response.ok) {
    throw new Error(`Failed to fetch claim status (status ${response.status})`)
  }

  return response.json()
}

// claims-intake-service returns every error as JSON {"error": "..."}. 413 comes from the
// container/nginx upload limits and 429 from the rate limiters, which may not carry that body.
export async function errorMessage(response, fallback) {
  if (response.status === 413) return 'Files too large (max 5 MB each, 16 MB in total).'
  if (response.status === 429) return 'Too many attempts. Try again later.'
  try {
    const body = await response.json()
    if (body && typeof body.error === 'string' && body.error) return body.error
  } catch {
    // Not JSON; use the fallback.
  }
  return `${fallback} (status ${response.status})`
}

// multipart: a `reason` field and one `files` part per document. The browser sets the
// Content-Type with the multipart boundary, so it isn't set here.
export async function submitAppeal(claimId, reason, files) {
  const form = new FormData()
  form.append('reason', reason)
  for (const file of files) {
    form.append('files', file)
  }
  const response = await fetch(`${BASE_URL}/claims/${encodeURIComponent(claimId)}/appeal`, {
    method: 'POST',
    body: form,
  })
  if (!response.ok) {
    throw new Error(await errorMessage(response, 'Failed to submit the appeal'))
  }
  return response.json()
}

// Resolves with the assistant's response body for every outcome (answered | refused |
// abstained); those are normal results, not errors. Only transport/HTTP failures throw.
export async function askAssistant(question, claimId) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ASK_TIMEOUT_MS)
  try {
    const response = await fetch(`${RAG_URL}/assistant/ask`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(claimId ? { question, claimId } : { question }),
      signal: controller.signal,
    })

    if (response.status === 503) {
      throw new Error('The assistant is still starting up. Try again in a moment.')
    }
    if (!response.ok) {
      throw new Error(`The assistant request failed (status ${response.status})`)
    }

    return await response.json()
  } catch (err) {
    if (err.name === 'AbortError') {
      throw new Error('The assistant took too long to respond. Try again.')
    }
    if (err instanceof TypeError) {
      throw new Error("Couldn't reach the assistant.")
    }
    throw err
  } finally {
    clearTimeout(timer)
  }
}
