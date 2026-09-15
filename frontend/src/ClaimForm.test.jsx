import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import ClaimForm from './ClaimForm.jsx'
import { submitClaim } from './api.js'

vi.mock('./api.js', () => ({
  submitClaim: vi.fn(),
}))

describe('ClaimForm', () => {
  beforeEach(() => {
    submitClaim.mockReset()
  })

  it('blocks submit when required fields are missing', async () => {
    render(<ClaimForm onSubmitted={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Employee ID is required.')
    expect(submitClaim).not.toHaveBeenCalled()
  })

  it('submits valid input and calls onSubmitted with the created claim', async () => {
    const onSubmitted = vi.fn()
    submitClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })

    render(<ClaimForm onSubmitted={onSubmitted} />)

    fireEvent.change(screen.getByLabelText(/employee id/i), { target: { value: 'EMP-1' } })
    fireEvent.change(screen.getByLabelText(/plan type/i), { target: { value: 'dental' } })
    fireEvent.change(screen.getByLabelText(/amount requested/i), { target: { value: '250' } })
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: 'Cleaning' } })
    fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))

    await waitFor(() =>
      expect(submitClaim).toHaveBeenCalledWith({
        employeeId: 'EMP-1',
        planType: 'dental',
        amountRequested: 250,
        description: 'Cleaning',
      }),
    )
    await waitFor(() =>
      expect(onSubmitted).toHaveBeenCalledWith({ claimId: 'abc-123', status: 'SUBMITTED' }),
    )
  })

  it('surfaces an error message when submission fails', async () => {
    submitClaim.mockRejectedValue(new Error('Failed to submit claim (status 500)'))

    render(<ClaimForm onSubmitted={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/employee id/i), { target: { value: 'EMP-1' } })
    fireEvent.change(screen.getByLabelText(/amount requested/i), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to submit claim (status 500)',
    )
  })
})
