import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request from './client'
import { getQuota } from './quota'

vi.mock('./client', () => ({ default: vi.fn() }))

const mockRequest = vi.mocked(request)

describe('quota', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fetches the quota snapshot', async () => {
    mockRequest.mockResolvedValue({ usedBytes: 10, limitBytes: 100 })

    const quota = await getQuota()

    expect(quota).toEqual({ usedBytes: 10, limitBytes: 100 })
    expect(mockRequest).toHaveBeenCalledWith('/quota')
  })
})