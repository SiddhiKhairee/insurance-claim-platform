import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AssistantWidget from './AssistantWidget.jsx'
import { askAssistant } from './api.js'

vi.mock('./api.js', () => ({
  askAssistant: vi.fn(),
}))

function ask(text) {
  fireEvent.change(screen.getByLabelText(/^question$/i), { target: { value: text } })
  fireEvent.click(screen.getByRole('button', { name: /^ask$/i }))
}

describe('AssistantWidget', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('shows an answer with its policy and claim sources', async () => {
    askAssistant.mockResolvedValue({
      outcome: 'answered',
      answer: 'The Group Dental Plan pays up to $2,000.00 per claim.',
      reason: null,
      citations: [
        { source: 'policy', docId: 'dental_coverage-limits', planType: 'dental',
          section: 'Coverage Limits', score: 0.9 },
        { source: 'claim', claimId: 'abc-123' },
      ],
      claim: null,
    })
    render(<AssistantWidget />)

    ask('  What is the dental limit?  ')

    expect(await screen.findByText(/pays up to \$2,000\.00 per claim/i)).toBeInTheDocument()
    expect(screen.getByText('dental plan: Coverage Limits')).toBeInTheDocument()
    expect(screen.getByText('Claim abc-123')).toBeInTheDocument()
    // Trimmed, and no claimId key sent when the field is empty.
    expect(askAssistant).toHaveBeenCalledWith('What is the dental limit?', undefined)
  })

  it('shows a refusal as information, not an error, with the claim decision and rule trace', async () => {
    askAssistant.mockResolvedValue({
      outcome: 'refused',
      answer: "I don't make adjudication decisions; the rule engine does.",
      reason: 'decision_request',
      citations: [],
      claim: { claimId: 'abc-123', status: 'DENIED', ruleTrace: ['plan-limit check: failed'] },
    })
    render(<AssistantWidget />)

    ask('Approve my claim')

    expect(await screen.findByText(/decisions come from the rule engine/i)).toBeInTheDocument()
    expect(screen.getByText(/i don't make adjudication decisions/i)).toBeInTheDocument()
    expect(screen.getByText('DENIED')).toBeInTheDocument()
    expect(screen.getByText('plan-limit check: failed')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows a distinct "couldn\'t answer reliably" state for abstentions', async () => {
    askAssistant.mockResolvedValue({
      outcome: 'abstained',
      answer: "I don't have enough information to answer that reliably.",
      reason: 'no_grounded_answer',
      citations: [],
      claim: null,
    })
    render(<AssistantWidget />)

    ask('What is the out-of-pocket maximum?')

    expect(await screen.findByText(/couldn't answer that reliably/i)).toBeInTheDocument()
    expect(screen.getByText(/i don't have enough information/i)).toBeInTheDocument()
    expect(screen.getByText(/try rephrasing/i)).toBeInTheDocument()
    expect(screen.queryByText(/sources/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('gives claim-specific hints when the claim lookup was the problem', async () => {
    askAssistant.mockResolvedValue({
      outcome: 'abstained',
      answer: "I couldn't find that claim.",
      reason: 'claim_not_found',
      citations: [],
      claim: null,
    })
    render(<AssistantWidget />)

    ask('Why was it denied?')

    expect(await screen.findByText(/check the claim id and try again/i)).toBeInTheDocument()
  })

  it('shows an error with a working retry when the request fails', async () => {
    askAssistant.mockRejectedValueOnce(new Error("Couldn't reach the assistant."))
    askAssistant.mockResolvedValueOnce({
      outcome: 'answered', answer: 'A thirty day wait applies.', reason: null, citations: [],
      claim: null,
    })
    render(<AssistantWidget />)

    ask('How long is the wait?')

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't reach the assistant/i)
    fireEvent.click(screen.getByRole('button', { name: /retry/i }))

    expect(await screen.findByText(/a thirty day wait applies/i)).toBeInTheDocument()
    expect(askAssistant).toHaveBeenCalledTimes(2)
  })

  it('validates the question and claim ID before calling the service', () => {
    render(<AssistantWidget />)

    fireEvent.click(screen.getByRole('button', { name: /^ask$/i }))
    expect(screen.getByRole('alert')).toHaveTextContent(/enter a question/i)

    fireEvent.change(screen.getByLabelText(/claim id/i), { target: { value: 'bad id!' } })
    ask('What is the limit?')
    expect(screen.getByRole('alert')).toHaveTextContent(/letters, numbers and hyphens/i)

    fireEvent.change(screen.getByLabelText(/claim id/i), { target: { value: '' } })
    ask('x'.repeat(1001))
    expect(screen.getByRole('alert')).toHaveTextContent(/under 1000 characters/i)

    expect(askAssistant).not.toHaveBeenCalled()
  })

  it('prefills the claim ID from props, sends it, and follows prop changes', async () => {
    askAssistant.mockResolvedValue({
      outcome: 'answered', answer: 'It was denied.', reason: null, citations: [], claim: null,
    })
    const { rerender } = render(<AssistantWidget claimId="abc-123" />)
    expect(screen.getByLabelText(/claim id/i)).toHaveValue('abc-123')

    ask('Why was it denied?')
    await waitFor(() =>
      expect(askAssistant).toHaveBeenCalledWith('Why was it denied?', 'abc-123'),
    )

    rerender(<AssistantWidget claimId="def-456" />)
    expect(screen.getByLabelText(/claim id/i)).toHaveValue('def-456')
  })

  it('disables the form while a request is in flight', async () => {
    let resolve
    askAssistant.mockReturnValue(new Promise((r) => { resolve = r }))
    render(<AssistantWidget />)

    ask('What is the limit?')

    expect(await screen.findByRole('button', { name: /asking/i })).toBeDisabled()
    expect(screen.getByLabelText(/^question$/i)).toBeDisabled()

    resolve({ outcome: 'answered', answer: 'Done.', reason: null, citations: [], claim: null })
    expect(await screen.findByText('Done.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^ask$/i })).toBeEnabled()
  })
})
