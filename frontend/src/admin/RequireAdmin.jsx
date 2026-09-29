import { useCallback } from 'react'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { SessionExpiredError } from './adminApi.js'
import { clearSession, getSession } from './adminSession.js'

// Route guard for the admin pages. This only decides what the browser shows: every admin API
// call is still checked by claims-intake-service (Spring Security, ADMIN role), which is the
// real enforcement.
export function RequireAdmin() {
  const location = useLocation()
  if (!getSession()) {
    return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}

// Returns a handler for API errors: a SessionExpiredError sends the admin back to login (and
// back to this page afterwards); any other error is returned as its message for the page to show.
export function useAdminErrorHandler() {
  const navigate = useNavigate()
  const location = useLocation()
  return useCallback(
    (err) => {
      if (err instanceof SessionExpiredError) {
        clearSession()
        navigate('/admin/login', {
          replace: true,
          state: { from: location.pathname, message: err.message },
        })
        return null
      }
      return err.message
    },
    [navigate, location.pathname],
  )
}

export function AdminLayout() {
  const navigate = useNavigate()
  const session = getSession()

  function logout() {
    clearSession()
    navigate('/admin/login', { replace: true, state: { message: 'You have logged out.' } })
  }

  return (
    <>
      <div className="admin-bar">
        <span>
          Admin: <strong>{session?.username}</strong>
        </span>
        <button className="btn btn-secondary" onClick={logout}>
          Log out
        </button>
      </div>
      <Outlet />
    </>
  )
}
