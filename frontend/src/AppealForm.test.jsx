import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppealForm, { MAX_FILE_BYTES, validateAppeal } from './AppealForm.jsx'
import { submitAppeal } from './api.js'

vi.mock('./api.js', () => ({
  submitAppeal: vi.fn(),
}))

function file(name, type, size = 10) {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}

const pdf = file('receipt.pdf', 'application/pdf')

describe('validateAppeal (mirrors the server limits)', () => {
  it.each([
    ['', [pdf], /explain why/i],
    ['   ', [pdf], /explain why/i],
    ['x'.repeat(2001), [pdf], /under 2000/i],
    ['reason', [], /at least one/i],
    ['reason', [pdf, pdf, pdf, pdf], /at most 3/i],
    ['reason', [file('notes.txt', 'text/plain')], /only PDF, PNG or JPEG/i],
    ['reason', [file('big.pdf', 'application/pdf', MAX_FILE_BYTES + 1)], /larger than 5 MB/i],
  ])('rejects reason=%j files=%j', (reason, files, message) => {
    expect(validateAppeal(reason, files)).toMatch(message)
  })

  it('accepts 1 to 3 PDF/PNG/JPEG files up to 5 MB each', () => {
    const files = [
      pdf,
      file('scan.png', 'image/png'),
      file('photo.JPG', '', MAX_FILE_BYTES), // no MIME from the browser: extension decides
    ]
    expect(validateAppeal('The limit was applied wrongly.', files)).toBeNull()
  })
})

describe('AppealForm', () => {
  beforeEach(() => {
    submitAppeal.mockReset()
  })

  function fillAndSubmit(files) {
    fireEvent.change(screen.getByLabelText(/reason for appeal/i), {
      target: { value: '  Receipts attached.  ' },
    })
    fireEvent.change(screen.getByLabelText(/supporting documents/i), { target: { files } })
    fireEvent.click(screen.getByRole('button', { name: /submit appeal/i }))
  }

  it('does not call the server when validation fails', () => {
    render(<AppealForm claimId="c1" onAppealed={vi.fn()} />)
    fillAndSubmit([file('notes.txt', 'text/plain')])
    expect(screen.getByRole('alert')).toHaveTextContent(/only PDF, PNG or JPEG/)
    expect(submitAppeal).not.toHaveBeenCalled()
  })

  it('submits the trimmed reason and files and reports the updated claim', async () => {
    const onAppealed = vi.fn()
    const updated = { claimId: 'c1', appeal: { status: 'PENDING_REVIEW' } }
    submitAppeal.mockResolvedValue(updated)
    render(<AppealForm claimId="c1" onAppealed={onAppealed} />)

    fillAndSubmit([pdf])

    await vi.waitFor(() => expect(onAppealed).toHaveBeenCalledWith(updated))
    expect(submitAppeal).toHaveBeenCalledWith('c1', 'Receipts attached.', [pdf])
  })

  it("shows the server's error, e.g. a file whose bytes aren't a PDF", async () => {
    submitAppeal.mockRejectedValue(new Error('receipt.pdf is not a PDF, PNG or JPEG file'))
    render(<AppealForm claimId="c1" onAppealed={vi.fn()} />)

    fillAndSubmit([pdf])

    expect(await screen.findByRole('alert')).toHaveTextContent('is not a PDF, PNG or JPEG')
    expect(screen.getByRole('button', { name: /submit appeal/i })).toBeEnabled()
  })

  it('tells the claimant not to upload real documents', () => {
    render(<AppealForm claimId="c1" onAppealed={vi.fn()} />)
    expect(screen.getByText(/don't upload real personal or medical files/i)).toBeInTheDocument()
  })
})
