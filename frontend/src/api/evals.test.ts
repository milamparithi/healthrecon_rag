import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request from './client'
import {
  dismissEval,
  getEvalMetrics,
  listEvals,
  promoteEval,
  reviewEval,
} from './evals'

vi.mock('./client', () => ({ default: vi.fn() }))

const mockRequest = vi.mocked(request)

describe('api evals', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists evals for a document set without filters', async () => {
    mockRequest.mockResolvedValue({ content: [] })

    await listEvals('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/evals')
  })

  it('lists evals with filter query params', async () => {
    mockRequest.mockResolvedValue({ content: [] })

    await listEvals('set-1', { page: 2, size: 20, status: 'PENDING', flagged: true, sampled: true })

    expect(mockRequest).toHaveBeenCalledWith(
      '/documentsets/set-1/evals?page=2&size=20&status=PENDING&sampled=true&flagged=true',
    )
  })

  it('reviews an eval', async () => {
    mockRequest.mockResolvedValue({ id: 'eval-1' })

    await reviewEval('set-1', 'eval-1', {
      verdict: 'REJECT',
      rating: 1,
      comment: 'bad',
      correctedAnswer: 'fixed.',
    })

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/evals/eval-1', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verdict: 'REJECT', rating: 1, comment: 'bad', correctedAnswer: 'fixed.' }),
    })
  })

  it('dismisses an eval', async () => {
    mockRequest.mockResolvedValue(undefined)

    await dismissEval('set-1', 'eval-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/evals/eval-1/dismiss', {
      method: 'POST',
    })
  })

  it('promotes an eval to a golden case', async () => {
    mockRequest.mockResolvedValue({ id: 'case-1' })

    await promoteEval('set-1', 'eval-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/evals/eval-1/promote', {
      method: 'POST',
    })
  })

  it('fetches eval metrics', async () => {
    mockRequest.mockResolvedValue({ totalCaptured: 5 })

    await getEvalMetrics('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/evals/metrics')
  })
})