import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { submitClaim, getClaim } from './api.js'

vi.mock('./api.js', () => ({
  submitClaim: vi.fn(),
  getClaim: vi.fn(),
  askAssistant: vi.fn(),
}))

describe('App', () => {
  it('renders the heading and the claim form by default', () => {
    render(<App />)
    expect(screen.getByText('Group Claims Pipeline')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /submit a claim/i })).toBeInTheDocument()
  })

  it('shows the claim status view after a successful submission', async () => {
    submitClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })
    getClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })

    render(<App />)

    fireEvent.change(screen.getByLabelText(/employee id/i), { target: { value: 'EMP-1' } })
    fireEvent.change(screen.getByLabelText(/amount requested/i), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /claim status/i })).toBeInTheDocument(),
    )
    expect(screen.getByText('Claim ID: abc-123')).toBeInTheDocument()
  })

  it('offers the assistant and prefills its claim ID from the submitted claim', async () => {
    submitClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })
    getClaim.mockResolvedValue({ claimId: 'abc-123', status: 'SUBMITTED' })

    render(<App />)
    expect(screen.getByLabelText(/^question$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/claim id \(optional\)/i)).toHaveValue('')

    fireEvent.change(screen.getByLabelText(/employee id/i), { target: { value: 'EMP-1' } })
    fireEvent.change(screen.getByLabelText(/amount requested/i), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: /submit claim/i }))

    await waitFor(() =>
      expect(screen.getByLabelText(/claim id \(optional\)/i)).toHaveValue('abc-123'),
    )
  })
})
