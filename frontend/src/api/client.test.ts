import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request, { ApiError, UNAUTHORIZED_EVENT, setToken } from './client'

function jsonResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}

function failingResponse(status: number, body?: unknown): Response {
  return {
    ok: false,
    status,
    json: async () => {
      if (body === undefined) {
        throw new Error('invalid json')
      }
      return body
    },
  } as Response
}

describe('api client', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('attaches the bearer token', async () => {
    setToken('tok-123')
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }))

    await request('/documentsets')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/documentsets',
      expect.objectContaining({}),
    )
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = init.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer tok-123')
  })

  it('does not attach a header when no token is stored', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, []))

    await request('/documentsets')

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = init.headers as Headers
    expect(headers.has('Authorization')).toBe(false)
  })

  it('returns undefined for 204 responses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204))

    await expect(request('/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
  })

  it('throws ApiError for non-2xx responses with a body', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { message: 'Conflict' }))

    const err = await request('/documentsets', { method: 'POST' }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(409)
    expect((err as ApiError).message).toBe('Conflict')
  })

  it('uses a fallback message when the error body is missing', async () => {
    fetchMock.mockResolvedValue(failingResponse(500))

    const err = await request('/documentsets').catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(500)
    expect((err as ApiError).message).toBe('Request failed (500)')
  })

  it('clears the token and dispatches auth:unauthorized on a protected 401', async () => {
    const listener = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, listener)
    setToken('tok-123')
    fetchMock.mockResolvedValue(failingResponse(401, { message: 'Unauthorized' }))

    const err = await request('/documentsets').catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('hr_token')).toBeNull()
    window.removeEventListener(UNAUTHORIZED_EVENT, listener)
  })

  it('does not trigger logout handling for failed auth endpoints', async () => {
    const listener = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, listener)
    setToken('tok-123')
    fetchMock.mockResolvedValue(failingResponse(401, { message: 'Bad credentials' }))

    const err = await request('/auth/login', { method: 'POST' }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
    expect(listener).not.toHaveBeenCalled()
    expect(localStorage.getItem('hr_token')).toBe('tok-123')
    window.removeEventListener(UNAUTHORIZED_EVENT, listener)
  })
})