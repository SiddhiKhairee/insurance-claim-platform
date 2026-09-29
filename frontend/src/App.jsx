import { Link, Navigate, Route, Routes } from 'react-router-dom'
import AdminAppealReview from './admin/AdminAppealReview.jsx'
import AdminAppealsList from './admin/AdminAppealsList.jsx'
import { AdminLogin, AdminSignup } from './admin/AdminAuthPages.jsx'
import { AdminLayout, RequireAdmin } from './admin/RequireAdmin.jsx'
import ClaimPage from './ClaimPage.jsx'
import HomePage from './HomePage.jsx'

// Routing lives here; the router itself is created in main.jsx (BrowserRouter) so tests can use
// a MemoryRouter. In production nginx serves index.html for any path that isn't an API route,
// so these URLs survive a refresh; the admin API is under /api/admin, never /admin.
function App() {
  return (
    <main className="app">
      <header className="app-header">
        <h1>
          <Link to="/" className="home-link">
            Group Claims Pipeline
          </Link>
        </h1>
        <p>Synthetic data only — portfolio project.</p>
      </header>

      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/claims/:claimId" element={<ClaimPage />} />
        <Route path="/admin/login" element={<AdminLogin />} />
        <Route path="/admin/signup" element={<AdminSignup />} />
        <Route path="/admin" element={<RequireAdmin />}>
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="/admin/appeals" replace />} />
            <Route path="appeals" element={<AdminAppealsList />} />
            <Route path="appeals/:claimId" element={<AdminAppealReview />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </main>
  )
}

export default App
