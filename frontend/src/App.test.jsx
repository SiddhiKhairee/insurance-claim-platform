import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { submitClaim, getClaim } from './api.js'
import { listAppeals } from './admin/adminApi.js'
import { clearSession, saveSession } from './admin/adminSession.js'

vi.mock('./api.js', () => ({
  submitClaim: vi.fn(),
  getClaim: vi.fn(),
  askAssistant: vi.fn(),
  submitAppeal: vi.fn(),
}))

vi.mock('./admin/adminApi.js', () => ({
  SessionExpiredError: class SessionExpiredError extends Error {},
  listAppeals: vi.fn(),
  getAppeal: vi.fn(),
  login: vi.fn(),
  signup: vi.fn(),
}))

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

function submitAClaim() {
  fireEvent.change(screen.getByLabelText(/employee id/i), { target: { value: 'EMP-1' } })
  fireEvent.change(screen.getByLabelText(/amount requested/i), { target: { value: '100' } })
  fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))
}

describe('App', () => {
  afterEach(() => {
    clearSession()
  })

  it('renders the heading and the claim form by default', () => {
    renderAt('/')
    expect(screen.getByText('Group Claims Pipeline')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /submit a claim/i })).toBeInTheDocument()
  })

  it('goes to the claim page after a successful submission', async () => {
    submitClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })
    getClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })

    renderAt('/')
    submitAClaim()

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /claim status/i })).toBeInTheDocument(),
    )
    expect(screen.getByText('Claim ID: abc-123')).toBeInTheDocument()
  })

  it('prefills the assistant claim ID on the claim page', async () => {
    submitClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })
    getClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })

    renderAt('/')
    expect(screen.getByLabelText(/claim id \(optional\)/i)).toHaveValue('')
    submitAClaim()

    await waitFor(() =>
      expect(screen.getByLabelText(/claim id \(optional\)/i)).toHaveValue('abc-123'),
    )
  })

  it('opens a claim directly by URL, so a claimant can come back to it', async () => {
    getClaim.mockResolvedValue({ claimId: 'abc-123', status: 'DENIED' })
    renderAt('/claims/abc-123')
    expect(await screen.findByText('Claim ID: abc-123')).toBeInTheDocument()
    expect(getClaim).toHaveBeenCalledWith('abc-123')
  })

  it('looks up a claim by ID from the home page', async () => {
    getClaim.mockResolvedValue({ claimId: 'xyz-9', status: 'APPROVED' })
    renderAt('/')
    fireEvent.change(screen.getByLabelText(/^claim id$/i), { target: { value: 'xyz-9' } })
    fireEvent.click(screen.getByRole('button', { name: /view claim/i }))
    expect(await screen.findByText('Claim ID: xyz-9')).toBeInTheDocument()
  })

  it('sends a signed-out visitor from /admin/appeals to the admin login', () => {
    renderAt('/admin/appeals')
    expect(screen.getByRole('heading', { name: /admin login/i })).toBeInTheDocument()
    expect(listAppeals).not.toHaveBeenCalled()
  })

  it('shows the admin pages with a session, and logout ends it', async () => {
    saveSession({ token: 't', expiresAt: new Date(Date.now() + 60000).toISOString(), username: 'ann' })
    listAppeals.mockResolvedValue([])

    renderAt('/admin')

    expect(await screen.findByRole('heading', { name: /appealed claims/i })).toBeInTheDocument()
    expect(screen.getByText('ann')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /log out/i }))
    expect(screen.getByRole('heading', { name: /admin login/i })).toBeInTheDocument()
    expect(screen.getByText(/you have logged out/i)).toBeInTheDocument()
  })
})
