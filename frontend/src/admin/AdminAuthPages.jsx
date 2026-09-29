import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { login, signup } from './adminApi.js'
import { getSession, saveSession } from './adminSession.js'

// The deployed demo is plain HTTP (PLAN.md §12, Phase 8b): credentials cross the network
// unencrypted, so the pages say so.
function PlainHttpWarning() {
  return (
    <p className="status-meta">
      This demo runs over plain HTTP: don&apos;t reuse a password you use anywhere else.
    </p>
  )
}

export function AdminLogin() {
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  if (getSession()) return <Navigate to="/admin/appeals" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    if (!username.trim() || !password) {
      setError('Enter your username and password.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const { token, expiresAt } = await login(username.trim(), password)
      saveSession({ token, expiresAt, username: username.trim().toLowerCase() })
      navigate(location.state?.from || '/admin/appeals', { replace: true })
    } catch (err) {
      setError(err.message)
      setSubmitting(false)
    }
  }

  return (
    <div className="card">
      <form onSubmit={handleSubmit} noValidate>
        <h2>Admin login</h2>
        <PlainHttpWarning />
        {location.state?.message && !error && (
          <p role="status" className="status-note">
            {location.state.message}
          </p>
        )}
        {error && (
          <p role="alert" className="error-banner">
            {error}
          </p>
        )}
        <div className="field">
          <label htmlFor="admin-username">Username</label>
          <input
            id="admin-username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="admin-password">Password</label>
          <input
            id="admin-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <button type="submit" className="btn" disabled={submitting}>
          {submitting ? 'Logging in…' : 'Log in'}
        </button>
        <p className="status-meta form-footer">
          Have a signup code? <Link to="/admin/signup">Create an admin account</Link>
        </p>
      </form>
    </div>
  )
}

export function AdminSignup() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [signupCode, setSignupCode] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    // Same rules as AdminAuthService; the server enforces them.
    if (!/^[a-z0-9._-]{3,32}$/.test(username.trim().toLowerCase())) {
      setError('Username: 3 to 32 characters (letters, numbers, dot, underscore, hyphen).')
      return
    }
    if (password.length < 12 || new TextEncoder().encode(password).length > 72) {
      setError('Password: at least 12 characters and at most 72 bytes.')
      return
    }
    if (!signupCode) {
      setError('Enter the signup code.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await signup(username.trim(), password, signupCode)
      navigate('/admin/login', {
        replace: true,
        state: { message: 'Account created. Log in to continue.' },
      })
    } catch (err) {
      setError(err.message)
      setSubmitting(false)
    }
  }

  return (
    <div className="card">
      <form onSubmit={handleSubmit} noValidate>
        <h2>Create an admin account</h2>
        <p className="status-meta">
          Admins review appeals of denied claims. Sign-up needs the signup code from the site
          owner.
        </p>
        <PlainHttpWarning />
        {error && (
          <p role="alert" className="error-banner">
            {error}
          </p>
        )}
        <div className="field">
          <label htmlFor="signup-username">Username</label>
          <input
            id="signup-username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="signup-password">Password</label>
          <input
            id="signup-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <p className="field-hint">At least 12 characters.</p>
        </div>
        <div className="field">
          <label htmlFor="signup-code">Signup code</label>
          <input
            id="signup-code"
            type="password"
            autoComplete="off"
            value={signupCode}
            onChange={(e) => setSignupCode(e.target.value)}
          />
        </div>
        <button type="submit" className="btn" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Create account'}
        </button>
        <p className="status-meta form-footer">
          Already have an account? <Link to="/admin/login">Log in</Link>
        </p>
      </form>
    </div>
  )
}
