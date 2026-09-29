import { render, screen, act, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import ClaimStatus from './ClaimStatus.jsx'
import { getClaim, submitAppeal } from './api.js'

vi.mock('./api.js', () => ({
  getClaim: vi.fn(),
  submitAppeal: vi.fn(),
}))

describe('ClaimStatus', () => {
  beforeEach(() => {
    getClaim.mockReset()
    submitAppeal.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls until a terminal status and renders the decision', async () => {
    getClaim
      .mockResolvedValueOnce({ claimId: 'c1', status: 'SUBMITTED' })
      .mockResolvedValueOnce({
        claimId: 'c1',
        status: 'APPROVED',
        decisionReason: 'within plan limit',
        ruleTrace: ['coverage-check'],
      })

    vi.useFakeTimers()
    render(<ClaimStatus claimId="c1" />)

    await act(async () => {
      await Promise.resolve()
    })
    expect(getClaim).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000)
    })

    expect(getClaim).toHaveBeenCalledTimes(2)
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
    expect(screen.getByText('Reason: within plan limit')).toBeInTheDocument()
    expect(screen.getByText('coverage-check')).toBeInTheDocument()
  })

  it('shows a still-processing state after the attempt cap without a terminal status', async () => {
    getClaim.mockResolvedValue({ claimId: 'c1', status: 'SUBMITTED' })

    vi.useFakeTimers()
    render(<ClaimStatus claimId="c1" />)

    await act(async () => {
      await Promise.resolve()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000 * 29)
    })

    expect(getClaim).toHaveBeenCalledTimes(30)
    expect(screen.getByRole('status')).toHaveTextContent('Still processing')

    const checkAgain = screen.getByRole('button', { name: /check again/i })
    getClaim.mockResolvedValueOnce({ claimId: 'c1', status: 'SUBMITTED' })
    await act(async () => {
      checkAgain.click()
      await Promise.resolve()
    })
    expect(getClaim).toHaveBeenCalledTimes(31)
  })

  it('shows a distinct error state when the poll request fails', async () => {
    getClaim.mockRejectedValue(new Error('network down'))

    render(<ClaimStatus claimId="c1" />)

    expect(await screen.findByRole('alert')).toHaveTextContent('network down')
  })

  describe('appeals', () => {
    const denied = {
      claimId: 'c1',
      status: 'DENIED',
      displayStatus: 'DENIED',
      decisionReason: 'amount exceeds plan limit',
      ruleTrace: ['plan-limit check: failed'],
      appeal: null,
    }

    it('offers an appeal on a DENIED claim with no appeal yet', async () => {
      getClaim.mockResolvedValue(denied)
      render(<ClaimStatus claimId="c1" />)

      fireEvent.click(await screen.findByRole('button', { name: /appeal this decision/i }))

      expect(screen.getByLabelText(/reason for appeal/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/supporting documents/i)).toBeInTheDocument()
    })

    it('does not offer an appeal on an APPROVED claim', async () => {
      getClaim.mockResolvedValue({ ...denied, status: 'APPROVED', displayStatus: 'APPROVED' })
      render(<ClaimStatus claimId="c1" />)
      await screen.findByText('APPROVED')
      expect(screen.queryByRole('button', { name: /appeal/i })).not.toBeInTheDocument()
    })

    it('shows a pending appeal and no second appeal action', async () => {
      getClaim.mockResolvedValue({
        ...denied,
        appeal: { status: 'PENDING_REVIEW', submittedAt: '2026-09-28T10:00:00Z', documentCount: 1 },
      })
      render(<ClaimStatus claimId="c1" />)

      const block = await screen.findByTestId('appeal-block')
      expect(block).toHaveTextContent('Appeal: Pending review')
      expect(block).toHaveTextContent('Submitted 2026-09-28 10:00 UTC')
      expect(screen.queryByRole('button', { name: /appeal this decision/i })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /check for an update/i })).toBeInTheDocument()
    })

    it('shows an overturned appeal while keeping the rule engine decision visible', async () => {
      getClaim.mockResolvedValue({
        ...denied,
        displayStatus: 'APPROVED_ON_APPEAL',
        appeal: {
          status: 'OVERTURNED',
          submittedAt: '2026-09-28T10:00:00Z',
          decidedAt: '2026-09-29T15:30:00Z',
          reviewerNote: 'Receipts confirm the procedure.',
          documentCount: 2,
        },
      })
      render(<ClaimStatus claimId="c1" />)

      expect(await screen.findByText('Approved on appeal')).toBeInTheDocument()
      expect(screen.getByText('Rule engine decision: DENIED')).toBeInTheDocument()
      expect(screen.getByText('plan-limit check: failed')).toBeInTheDocument()
      const block = screen.getByTestId('appeal-block')
      expect(block).toHaveTextContent('Overturned — approved on appeal')
      expect(block).toHaveTextContent('Decided 2026-09-29 15:30 UTC')
      expect(block).toHaveTextContent("Reviewer's note: Receipts confirm the procedure.")
    })

    it('shows an upheld appeal with the claim still DENIED', async () => {
      getClaim.mockResolvedValue({
        ...denied,
        appeal: {
          status: 'UPHELD',
          submittedAt: '2026-09-28T10:00:00Z',
          decidedAt: '2026-09-29T15:30:00Z',
          reviewerNote: 'Over the plan limit.',
          documentCount: 1,
        },
      })
      render(<ClaimStatus claimId="c1" />)

      expect(await screen.findByTestId('appeal-block')).toHaveTextContent('Denial upheld')
      expect(screen.getByText('DENIED')).toBeInTheDocument()
    })

    it('replaces the form with the pending appeal after a successful submission', async () => {
      getClaim.mockResolvedValue(denied)
      submitAppeal.mockResolvedValue({
        ...denied,
        appeal: { status: 'PENDING_REVIEW', submittedAt: '2026-09-28T10:00:00Z', documentCount: 1 },
      })
      render(<ClaimStatus claimId="c1" />)

      fireEvent.click(await screen.findByRole('button', { name: /appeal this decision/i }))
      fireEvent.change(screen.getByLabelText(/reason for appeal/i), {
        target: { value: 'The limit was applied wrongly.' },
      })
      const file = new File(['%PDF-1.4'], 'receipt.pdf', { type: 'application/pdf' })
      fireEvent.change(screen.getByLabelText(/supporting documents/i), { target: { files: [file] } })
      fireEvent.click(screen.getByRole('button', { name: /submit appeal/i }))

      expect(await screen.findByTestId('appeal-block')).toHaveTextContent('Pending review')
      expect(submitAppeal).toHaveBeenCalledWith('c1', 'The limit was applied wrongly.', [file])
      expect(screen.queryByLabelText(/reason for appeal/i)).not.toBeInTheDocument()
    })
  })
})
