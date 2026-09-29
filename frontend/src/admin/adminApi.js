// Admin API client for claims-intake-service's /api/admin/** (sign-up, login, appeal review).
// Every call after login sends the bearer token. A 401 means the token is missing, expired or
// invalid: the session is cleared and SessionExpiredError is thrown so the page can send the
// admin back to the login screen.
import { BASE_URL, errorMessage } from '../api.js'
import { clearSession, getSession } from './adminSession.js'

export class SessionExpiredError extends Error {
  constructor() {
    super('Your admin session has ended. Log in again.')
    this.name = 'SessionExpiredError'
  }
}

async function postJson(path, body) {
  return fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function signup(username, password, signupCode) {
  const response = await postJson('/api/admin/signup', { username, password, signupCode })
  if (!response.ok) throw new Error(await errorMessage(response, 'Sign-up failed'))
  return response.json()
}

// Resolves with {token, expiresAt}.
export async function login(username, password) {
  const response = await postJson('/api/admin/login', { username, password })
  if (!response.ok) throw new Error(await errorMessage(response, 'Login failed'))
  return response.json()
}

async function authorized(path, options = {}) {
  const session = getSession()
  if (!session) throw new SessionExpiredError()
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: { ...(options.headers || {}), Authorization: `Bearer ${session.token}` },
  })
  if (response.status === 401) {
    clearSession()
    throw new SessionExpiredError()
  }
  return response
}

export async function listAppeals(status) {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  const response = await authorized(`/api/admin/appeals${query}`)
  if (!response.ok) throw new Error(await errorMessage(response, 'Could not load appeals'))
  return response.json()
}

export async function getAppeal(claimId) {
  const response = await authorized(`/api/admin/appeals/${encodeURIComponent(claimId)}`)
  if (!response.ok) throw new Error(await errorMessage(response, 'Could not load the appeal'))
  return response.json()
}

export async function decideAppeal(claimId, decision, note) {
  const response = await authorized(
    `/api/admin/appeals/${encodeURIComponent(claimId)}/decision`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision, note }),
    },
  )
  if (!response.ok) throw new Error(await errorMessage(response, 'Could not record the decision'))
  return response.json()
}

// The document endpoint needs the bearer token, so a plain link can't open it. The file is
// fetched as a blob and saved under the filename from the appeal's metadata (so no CORS-exposed
// Content-Disposition header is needed in dev).
export async function downloadDocument(claimId, document) {
  const response = await authorized(
    `/api/admin/appeals/${encodeURIComponent(claimId)}/documents/${encodeURIComponent(document.docId)}`,
  )
  if (!response.ok) throw new Error(await errorMessage(response, 'Could not download the file'))
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = window.document.createElement('a')
    link.href = url
    link.download = document.filename || 'document'
    window.document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // Give the browser a moment to start the download before the URL is revoked.
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}
