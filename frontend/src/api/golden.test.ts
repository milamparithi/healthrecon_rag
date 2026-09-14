import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request from './client'
import {
  createGoldenCase,
  deleteGoldenCase,
  listGoldenCases,
  runGoldenEvaluation,
  updateGoldenCase,
} from './golden'

vi.mock('./client', () => ({ default: vi.fn() }))

const mockRequest = vi.mocked(request)

describe('api golden', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists golden cases for a document set', async () => {
    mockRequest.mockResolvedValue([])

    await listGoldenCases('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/golden')
  })

  it('creates a golden case', async () => {
    mockRequest.mockResolvedValue({ id: 'case-1' })

    await createGoldenCase('set-1', {
      question: 'Q?',
      answer: 'A.',
      expectedSources: [{ filename: 'guide.md', docId: null, section: null }],
    })

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/golden', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: 'Q?',
        answer: 'A.',
        expectedSources: [{ filename: 'guide.md', docId: null, section: null }],
      }),
    })
  })

  it('updates a golden case', async () => {
    mockRequest.mockResolvedValue({ id: 'case-1' })

    await updateGoldenCase('set-1', 'case-1', {
      question: 'Q2?',
      answer: 'A2.',
      expectedSources: null,
      status: 'GOLDEN',
    })

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/golden/case-1', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: 'Q2?',
        answer: 'A2.',
        expectedSources: null,
        status: 'GOLDEN',
      }),
    })
  })

  it('deletes a golden case', async () => {
    mockRequest.mockResolvedValue(undefined)

    await deleteGoldenCase('set-1', 'case-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/golden/case-1', {
      method: 'DELETE',
    })
  })

  it('runs the evaluation', async () => {
    mockRequest.mockResolvedValue({ casesEvaluated: 1 })

    await runGoldenEvaluation('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/golden/run', {
      method: 'POST',
    })
  })
})