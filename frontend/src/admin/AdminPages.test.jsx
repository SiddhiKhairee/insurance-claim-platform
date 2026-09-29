import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminAppealReview from './AdminAppealReview.jsx'
import AdminAppealsList from './AdminAppealsList.jsx'
import { AdminLogin, AdminSignup } from './AdminAuthPages.jsx'
import {
  SessionExpiredError,
  decideAppeal,
  downloadDocument,
  getAppeal,
  listAppeals,
  login,
  signup,
} from './adminApi.js'
import { clearSession, getSession, saveSession } from './adminSession.js'

vi.mock('./adminApi.js', async () => {
  const actual = await vi.importActual('./adminApi.js')
  return {
    SessionExpiredError: actual.SessionExpiredError,
    signup: vi.fn(),
    login: vi.fn(),
    listAppeals: vi.fn(),
    getAppeal: vi.fn(),
    decideAppeal: vi.fn(),
    downloadDocument: vi.fn(),
  }
})

function renderRoutes(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/login" element={<AdminLogin />} />
        <Route path="/admin/signup" element={<AdminSignup />} />
        <Route path="/admin/appeals" element={<AdminAppealsList />} />
        <Route path="/admin/appeals/:claimId" element={<AdminAppealReview />} />
      </Routes>
    </MemoryRouter>,
  )
}

const inAnHour = () => new Date(Date.now() + 3600_000).toISOString()
const signIn = () => saveSession({ token: 'tok', expiresAt: inAnHour(), username: 'ann' })

const PENDING_DETAIL = {
  claimId: 'claim-1234-5678',
  employeeId: 'EMP-25349',
  planType: 'dental',
  amountRequested: 2500,
  description: 'Crown on tooth 14',
  claimSubmittedAt: '2026-09-28T09:00:00Z',
  adjudicatedAt: '2026-09-28T09:00:02Z',
  engineStatus: 'DENIED',
  decisionReason: 'amount exceeds plan limit',
  ruleTrace: ['coverage check: passed', 'plan-limit check: failed'],
  displayStatus: 'DENIED',
  appeal: {
    reason: 'The crown was pre-authorised.',
    status: 'PENDING_REVIEW',
    submittedAt: '2026-09-28T10:00:00Z',
    reviewer: null,
    decidedAt: null,
    reviewerNote: null,
    documents: [
      { docId: 'd1', filename: 'preauth.pdf', contentType: 'application/pdf', sizeBytes: 204800 },
    ],
  },
}

describe('admin pages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    clearSession()
  })

  describe('login and sign-up', () => {
    it('logs in, stores the session and opens the appeals list', async () => {
      login.mockResolvedValue({ token: 'tok', expiresAt: inAnHour() })
      listAppeals.mockResolvedValue([])
      renderRoutes('/admin/login')

      expect(screen.getByText(/plain HTTP/i)).toBeInTheDocument()
      fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'Ann' } })
      fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'long-password-1' } })
      fireEvent.click(screen.getByRole('button', { name: /log in/i }))

      expect(await screen.findByRole('heading', { name: /appealed claims/i })).toBeInTheDocument()
      expect(login).toHaveBeenCalledWith('Ann', 'long-password-1')
      expect(getSession()).toMatchObject({ token: 'tok', username: 'ann' })
    })

    it('shows a failed login without storing anything', async () => {
      login.mockRejectedValue(new Error('Invalid username or password'))
      renderRoutes('/admin/login')

      fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'ann' } })
      fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'wrong-password' } })
      fireEvent.click(screen.getByRole('button', { name: /log in/i }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
      expect(getSession()).toBeNull()
    })

    it('signs up with the signup code, then asks the admin to log in', async () => {
      signup.mockResolvedValue({ username: 'ann' })
      renderRoutes('/admin/signup')

      fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'ann' } })
      fireEvent.change(screen.getByLabelText(/^password$/i), {
        target: { value: 'long-password-1' },
      })
      fireEvent.change(screen.getByLabelText(/signup code/i), { target: { value: 'the-code' } })
      fireEvent.click(screen.getByRole('button', { name: /create account/i }))

      expect(await screen.findByText(/account created/i)).toBeInTheDocument()
      expect(signup).toHaveBeenCalledWith('ann', 'long-password-1', 'the-code')
      expect(getSession()).toBeNull()
    })

    it('checks sign-up fields before calling the server', () => {
      renderRoutes('/admin/signup')
      fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'ann' } })
      fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: 'short' } })
      fireEvent.change(screen.getByLabelText(/signup code/i), { target: { value: 'c' } })
      fireEvent.click(screen.getByRole('button', { name: /create account/i }))

      expect(screen.getByRole('alert')).toHaveTextContent(/at least 12 characters/i)
      expect(signup).not.toHaveBeenCalled()
    })
  })

  describe('appeals list', () => {
    it('lists appeals and filters by status', async () => {
      signIn()
      listAppeals.mockResolvedValue([
        {
          claimId: 'claim-1234-5678',
          appealStatus: 'PENDING_REVIEW',
          appealSubmittedAt: '2026-09-28T10:00:00Z',
          planType: 'dental',
          amountRequested: 2500,
          engineStatus: 'DENIED',
          documentCount: 2,
        },
      ])
      renderRoutes('/admin/appeals')

      const link = await screen.findByRole('link', { name: 'claim-12…' })
      expect(link).toHaveAttribute('href', '/admin/appeals/claim-1234-5678')
      expect(screen.getByText('$2,500.00')).toBeInTheDocument()
      expect(screen.getByRole('cell', { name: 'Pending review' })).toBeInTheDocument()
      expect(listAppeals).toHaveBeenLastCalledWith('')

      fireEvent.change(screen.getByLabelText(/show/i), { target: { value: 'OVERTURNED' } })
      await waitFor(() => expect(listAppeals).toHaveBeenLastCalledWith('OVERTURNED'))
    })

    it('sends the admin to login when the session has expired', async () => {
      signIn()
      listAppeals.mockRejectedValue(new SessionExpiredError())
      renderRoutes('/admin/appeals')

      expect(await screen.findByRole('heading', { name: /admin login/i })).toBeInTheDocument()
      expect(screen.getByText(/session has ended/i)).toBeInTheDocument()
    })
  })

  describe('appeal review', () => {
    beforeEach(() => signIn())

    it("shows the engine's decision, ruleTrace, the appeal and its documents", async () => {
      getAppeal.mockResolvedValue(PENDING_DETAIL)
      renderRoutes('/admin/appeals/claim-1234-5678')

      expect(await screen.findByText('Rule engine decision: DENIED')).toBeInTheDocument()
      expect(screen.getByText('Reason: amount exceeds plan limit')).toBeInTheDocument()
      expect(screen.getByText('plan-limit check: failed')).toBeInTheDocument()
      expect(screen.getByText(/The crown was pre-authorised\./)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'preauth.pdf' }))
      expect(downloadDocument).toHaveBeenCalledWith(
        'claim-1234-5678',
        PENDING_DETAIL.appeal.documents[0],
      )
    })

    it('requires a decision and a note, and says the note is visible to the claimant', async () => {
      getAppeal.mockResolvedValue(PENDING_DETAIL)
      renderRoutes('/admin/appeals/claim-1234-5678')

      const submit = await screen.findByRole('button', { name: /record decision/i })
      expect(screen.getByText('This note is visible to the claimant.')).toBeInTheDocument()
      expect(screen.getByLabelText(/note \(required\)/i)).toHaveAccessibleDescription(
        'This note is visible to the claimant.',
      )
      expect(submit).toBeDisabled()

      fireEvent.click(screen.getByLabelText(/overturn and approve/i))
      expect(submit).toBeDisabled()
      fireEvent.change(screen.getByLabelText(/note \(required\)/i), { target: { value: '   ' } })
      expect(submit).toBeDisabled()
      fireEvent.change(screen.getByLabelText(/note \(required\)/i), {
        target: { value: 'Pre-authorisation on file.' },
      })
      expect(submit).toBeEnabled()
    })

    it('records an overturn and then shows the recorded decision instead of the form', async () => {
      getAppeal.mockResolvedValue(PENDING_DETAIL)
      decideAppeal.mockResolvedValue({
        ...PENDING_DETAIL,
        displayStatus: 'APPROVED_ON_APPEAL',
        appeal: {
          ...PENDING_DETAIL.appeal,
          status: 'OVERTURNED',
          reviewer: 'ann',
          decidedAt: '2026-09-29T15:30:00Z',
          reviewerNote: 'Pre-authorisation on file.',
        },
      })
      renderRoutes('/admin/appeals/claim-1234-5678')

      fireEvent.click(await screen.findByLabelText(/overturn and approve/i))
      fireEvent.change(screen.getByLabelText(/note \(required\)/i), {
        target: { value: '  Pre-authorisation on file. ' },
      })
      fireEvent.click(screen.getByRole('button', { name: /record decision/i }))

      const recorded = await screen.findByTestId('recorded-decision')
      expect(decideAppeal).toHaveBeenCalledWith(
        'claim-1234-5678',
        'OVERTURN',
        'Pre-authorisation on file.',
      )
      expect(recorded).toHaveTextContent('Overturned — approved on appeal by ann on 2026-09-29')
      expect(recorded).toHaveTextContent('Note (visible to the claimant): Pre-authorisation on file.')
      // The engine's decision is still shown as it was written.
      expect(screen.getByText('Rule engine decision: DENIED')).toBeInTheDocument()
      expect(screen.getByText('Approved on appeal')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /record decision/i })).not.toBeInTheDocument()
    })

    it('shows the server error when a decision is rejected (e.g. already decided)', async () => {
      getAppeal.mockResolvedValue(PENDING_DETAIL)
      decideAppeal.mockRejectedValue(new Error('Appeal already decided'))
      renderRoutes('/admin/appeals/claim-1234-5678')

      fireEvent.click(await screen.findByLabelText(/uphold the denial/i))
      fireEvent.change(screen.getByLabelText(/note \(required\)/i), { target: { value: 'No.' } })
      fireEvent.click(screen.getByRole('button', { name: /record decision/i }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Appeal already decided')
    })
  })
})
