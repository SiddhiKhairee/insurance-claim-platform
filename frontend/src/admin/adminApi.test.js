import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SessionExpiredError,
  decideAppeal,
  downloadDocument,
  listAppeals,
  login,
  signup,
} from './adminApi.js'
import { clearSession, getSession, saveSession } from './adminSession.js'

function respond(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

const inAnHour = () => new Date(Date.now() + 3600_000).toISOString()

describe('adminSession', () => {
  afterEach(() => clearSession())

  it('round-trips a session and drops it once expired', () => {
    saveSession({ token: 'tok', expiresAt: inAnHour(), username: 'ann' })
    expect(getSession()).toMatchObject({ token: 'tok', username: 'ann' })
    expect(getSession(Date.now() + 2 * 3600_000)).toBeNull()
    expect(getSession()).toBeNull() // the expired session was cleared
  })

  it('treats malformed stored data as signed out', () => {
    sessionStorage.setItem('claimsPipelineAdminSession', '{not json')
    expect(getSession()).toBeNull()
  })
})

describe('adminApi', () => {
  beforeEach(() => {
    saveSession({ token: 'tok-123', expiresAt: inAnHour(), username: 'ann' })
  })

  afterEach(() => {
    clearSession()
    vi.unstubAllGlobals()
  })

  it('signup and login post JSON without a token', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(respond(201, { username: 'ann' }))
      .mockResolvedValueOnce(respond(200, { token: 't', expiresAt: 'x' }))
    vi.stubGlobal('fetch', fetchMock)

    await signup('ann', 'long-password-1', 'code')
    await login('ann', 'long-password-1')

    const [signupUrl, signupOptions] = fetchMock.mock.calls[0]
    expect(signupUrl).toMatch(/\/api\/admin\/signup$/)
    expect(JSON.parse(signupOptions.body)).toEqual({
      username: 'ann',
      password: 'long-password-1',
      signupCode: 'code',
    })
    expect(signupOptions.headers.Authorization).toBeUndefined()
    expect(fetchMock.mock.calls[1][0]).toMatch(/\/api\/admin\/login$/)
  })

  it('shows the generic server message on a rejected signup code', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(403, { error: 'Sign-up not allowed' })))
    await expect(signup('ann', 'long-password-1', 'wrong')).rejects.toThrow('Sign-up not allowed')
  })

  it('sends the bearer token and the status filter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, []))
    vi.stubGlobal('fetch', fetchMock)

    await listAppeals('PENDING_REVIEW')

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toMatch(/\/api\/admin\/appeals\?status=PENDING_REVIEW$/)
    expect(options.headers.Authorization).toBe('Bearer tok-123')
  })

  it('clears the session and throws SessionExpiredError on 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(401, { error: 'Unauthorized' })))
    await expect(listAppeals()).rejects.toBeInstanceOf(SessionExpiredError)
    expect(getSession()).toBeNull()
  })

  it('does not call the server without a session', async () => {
    clearSession()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(listAppeals()).rejects.toBeInstanceOf(SessionExpiredError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('posts a decision with its note', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { claimId: 'c1' }))
    vi.stubGlobal('fetch', fetchMock)

    await decideAppeal('c1', 'OVERTURN', 'Receipts confirm it.')

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toMatch(/\/api\/admin\/appeals\/c1\/decision$/)
    expect(options.method).toBe('POST')
    expect(options.headers.Authorization).toBe('Bearer tok-123')
    expect(JSON.parse(options.body)).toEqual({ decision: 'OVERTURN', note: 'Receipts confirm it.' })
  })

  it('downloads a document with the token and saves it under its recorded filename', async () => {
    const blob = new Blob(['%PDF'], { type: 'application/pdf' })
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, blob: async () => blob })
    vi.stubGlobal('fetch', fetchMock)
    URL.createObjectURL = vi.fn(() => 'blob:fake')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    await downloadDocument('c1', { docId: 'd1', filename: 'receipt.pdf' })

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/admin\/appeals\/c1\/documents\/d1$/)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer tok-123')
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(click).toHaveBeenCalledTimes(1)
    expect(click.mock.contexts[0].download).toBe('receipt.pdf')
    click.mockRestore()
  })
})
