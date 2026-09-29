import { afterEach, describe, expect, it, vi } from 'vitest'
import { askAssistant, getClaim, submitAppeal } from './api.js'

function respond(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

describe('submitAppeal', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts multipart with a reason and one files part per document', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(201, { claimId: 'c1' }))
    vi.stubGlobal('fetch', fetchMock)
    const a = new File(['a'], 'a.pdf', { type: 'application/pdf' })
    const b = new File(['b'], 'b.png', { type: 'image/png' })

    await expect(submitAppeal('c1', 'why', [a, b])).resolves.toEqual({ claimId: 'c1' })

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toMatch(/\/claims\/c1\/appeal$/)
    expect(options.method).toBe('POST')
    // No manual Content-Type: the browser must add the multipart boundary itself.
    expect(options.headers).toBeUndefined()
    expect(options.body.get('reason')).toBe('why')
    expect(options.body.getAll('files').map((f) => f.name)).toEqual(['a.pdf', 'b.png'])
  })

  it('surfaces the service JSON error message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(409, { error: 'Claim already appealed' })))
    await expect(submitAppeal('c1', 'r', [])).rejects.toThrow('Claim already appealed')
  })

  it('maps 413 and 429 to plain messages even without a JSON body', async () => {
    const noJson = (status) => ({ ok: false, status, json: async () => { throw new Error('html') } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noJson(413)))
    await expect(submitAppeal('c1', 'r', [])).rejects.toThrow(/too large/i)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noJson(429)))
    await expect(submitAppeal('c1', 'r', [])).rejects.toThrow(/too many attempts/i)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noJson(500)))
    await expect(submitAppeal('c1', 'r', [])).rejects.toThrow(/status 500/)
  })

  it('encodes claim IDs taken from the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, {}))
    vi.stubGlobal('fetch', fetchMock)
    await getClaim('../actuator')
    expect(fetchMock.mock.calls[0][0]).toMatch(/\/claims\/..%2Factuator$/)
  })
})

describe('askAssistant', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts the question, omits claimId when absent, and returns the body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { outcome: 'answered' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(askAssistant('What is the limit?')).resolves.toEqual({ outcome: 'answered' })

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toMatch(/\/assistant\/ask$/)
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ question: 'What is the limit?' })
  })

  it('sends claimId when given', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { outcome: 'answered' }))
    vi.stubGlobal('fetch', fetchMock)

    await askAssistant('Why denied?', 'abc-123')

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      question: 'Why denied?',
      claimId: 'abc-123',
    })
  })

  it('treats refusals and abstentions as normal 200 results, not errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(200, { outcome: 'abstained' })))
    await expect(askAssistant('q')).resolves.toEqual({ outcome: 'abstained' })
  })

  it('maps 503 to a "still starting up" message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(503, { detail: 'loading' })))
    await expect(askAssistant('q')).rejects.toThrow(/still starting up/i)
  })

  it('reports other HTTP failures with the status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(422, {})))
    await expect(askAssistant('q')).rejects.toThrow(/status 422/)
  })

  it('maps a network failure to a friendly message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(askAssistant('q')).rejects.toThrow(/couldn't reach the assistant/i)
  })

  it('maps an aborted request to a timeout message', async () => {
    const abort = new Error('aborted')
    abort.name = 'AbortError'
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort))
    await expect(askAssistant('q')).rejects.toThrow(/took too long/i)
  })
})
