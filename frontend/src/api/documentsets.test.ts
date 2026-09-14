import { afterEach, describe, expect, it, vi } from 'vitest'
import requestMock from './client'
import { listAllDocuments } from './documentsets'
import type { DocumentListItem, DocumentPage } from './types'

vi.mock('./client', () => ({ default: vi.fn() }))

const mockRequest = vi.mocked(requestMock)

function makeDoc(id: string, filename: string): DocumentListItem {
  return {
    id,
    filename,
    contentType: 'text/markdown',
    contentLength: 10,
    status: 'READY',
    error: null,
    createdAt: '2026-01-01T00:00:00',
  }
}

function makePage(content: DocumentListItem[], page: number, totalElements: number): DocumentPage {
  return {
    content,
    page,
    size: 100,
    totalElements,
    totalPages: Math.ceil(totalElements / 100),
  }
}

describe('api documentsets', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('returns the single page when everything fits in one page', async () => {
    mockRequest.mockResolvedValue(makePage([makeDoc('doc-1', 'a.md')], 0, 1))

    const all = await listAllDocuments('set-1')

    expect(all.map((doc) => doc.filename)).toEqual(['a.md'])
    expect(mockRequest).toHaveBeenCalledTimes(1)
    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/documents?page=0&size=100')
  })

  it('pages through until totalPages is reached', async () => {
    mockRequest
      .mockResolvedValueOnce(makePage([makeDoc('doc-1', 'a.md')], 0, 101))
      .mockResolvedValueOnce(makePage([makeDoc('doc-2', 'b.md')], 1, 101))

    const all = await listAllDocuments('set-1')

    expect(all.map((doc) => doc.filename)).toEqual(['a.md', 'b.md'])
    expect(mockRequest).toHaveBeenCalledTimes(2)
    expect(mockRequest).toHaveBeenLastCalledWith('/documentsets/set-1/documents?page=1&size=100')
  })

  it('stops requesting once all pages are collected', async () => {
    mockRequest.mockResolvedValue(makePage([makeDoc('doc-1', 'a.md')], 0, 1))

    await listAllDocuments('set-1')

    expect(mockRequest).toHaveBeenCalledTimes(1)
  })

  it('returns an empty list when there are no documents', async () => {
    mockRequest.mockResolvedValue(makePage([], 0, 0))

    const all = await listAllDocuments('set-1')

    expect(all).toEqual([])
    expect(mockRequest).toHaveBeenCalledTimes(1)
  })
})