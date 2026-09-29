// The admin's session: the bearer token from POST /api/admin/login, its expiry, and the username.
//
// Kept in sessionStorage (owner decision, PLAN.md §12 2026-09-28): it survives a refresh in the
// same tab and is gone when the tab closes. Any script on the page could read it (XSS); that is
// accepted because the SPA loads no third-party scripts. Storage access can throw (private mode,
// blocked site data), so every access is guarded and a failure just means "not signed in".
const KEY = 'claimsPipelineAdminSession'

export function saveSession({ token, expiresAt, username }) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ token, expiresAt, username }))
  } catch {
    // Storage unavailable: the admin stays signed in only until the next navigation reads it.
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // Nothing to clear.
  }
}

// Returns the session, or null when there is none or it has expired (an expired one is cleared).
export function getSession(now = Date.now()) {
  let session
  try {
    session = JSON.parse(sessionStorage.getItem(KEY))
  } catch {
    return null
  }
  if (!session || typeof session.token !== 'string' || !session.token) return null
  const expires = Date.parse(session.expiresAt)
  if (Number.isNaN(expires) || expires <= now) {
    clearSession()
    return null
  }
  return session
}
