import { render, screen, act } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import ClaimStatus from './ClaimStatus.jsx'
import { getClaim } from './api.js'

vi.mock('./api.js', () => ({
  getClaim: vi.fn(),
}))

describe('ClaimStatus', () => {
  beforeEach(() => {
    getClaim.mockReset()
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
})
