import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  deleteAllDocuments,
  deleteDocument,
  deleteDocumentSet,
  getDocumentSet,
  listDocuments,
} from '../api/documentsets'
import type { DocumentListItem, DocumentPage, DocumentSet } from '../api/types'
import DocumentSetDetailPage from './DocumentSetDetailPage'

vi.mock('../api/documentsets', () => ({
  deleteAllDocuments: vi.fn(),
  deleteDocument: vi.fn(),
  deleteDocumentSet: vi.fn(),
  getDocument: vi.fn(),
  getDocumentSet: vi.fn(),
  listDocuments: vi.fn(),
  uploadDocuments: vi.fn(),
}))

vi.mock('../components/ChatPanel', () => ({
  default: () => <div data-testid="chat-panel" />,
}))

const mockDeleteAll = vi.mocked(deleteAllDocuments)
const mockDeleteDocument = vi.mocked(deleteDocument)
const mockDeleteSet = vi.mocked(deleteDocumentSet)
const mockGetSet = vi.mocked(getDocumentSet)
const mockList = vi.mocked(listDocuments)

function makeSet(over: Partial<DocumentSet> = {}): DocumentSet {
  return {
    id: 'set-1',
    name: 'Marketing docs',
    description: null,
    status: 'READY',
    documentCount: 3,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...over,
  }
}

function makeDoc(id: string, filename: string): DocumentListItem {
  return {
    id,
    filename,
    contentType: 'text/plain',
    contentLength: 1024,
    status: 'READY',
    error: null,
    createdAt: '2026-01-01T00:00:00',
  }
}

function makePage(content: DocumentListItem[], over: Partial<DocumentPage> = {}): DocumentPage {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: Math.max(content.length > 20 ? 2 : 1, 1),
    ...over,
  }
}

function renderPage(id = 'set-1') {
  return render(
    <MemoryRouter initialEntries={[`/documentsets/${id}`]}>
      <Routes>
        <Route path="/documentsets/:id" element={<DocumentSetDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('DocumentSetDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders a paginated document list', async () => {
    mockGetSet.mockResolvedValue(makeSet())
    mockList.mockImplementation(async (_id, page) =>
      page === 1
        ? makePage([makeDoc('doc-3', 'three.txt')], {
            page: 1,
            totalElements: 3,
            totalPages: 2,
          })
        : makePage(
            [makeDoc('doc-1', 'one.txt'), makeDoc('doc-2', 'two.txt')],
            { totalElements: 3, totalPages: 2 },
          ),
    )

    renderPage()

    expect(await screen.findByText('one.txt')).toBeInTheDocument()
    expect(screen.getByText('two.txt')).toBeInTheDocument()
    expect(screen.getByText(/3 documents · page 1 of 2/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Next/ }))

    expect(await screen.findByText('three.txt')).toBeInTheDocument()
    expect(mockList).toHaveBeenLastCalledWith('set-1', 1, 20)
  })

  it('deletes all documents after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockGetSet.mockResolvedValue(makeSet())
    mockList.mockResolvedValue(
      makePage([makeDoc('doc-1', 'one.txt')], { totalElements: 1, totalPages: 1 }),
    )
    mockDeleteAll.mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('one.txt')

    fireEvent.click(screen.getByRole('button', { name: 'Delete all' }))

    await waitFor(() => expect(mockDeleteAll).toHaveBeenCalledWith('set-1'))
    expect(await screen.findByText('No documents yet.')).toBeInTheDocument()
    confirmSpy.mockRestore()
  })

  it('deletes a single document', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockGetSet.mockResolvedValue(makeSet())
    mockList
      .mockResolvedValueOnce(makePage([makeDoc('doc-1', 'one.txt')]))
      .mockResolvedValue(makePage([]))
    mockDeleteDocument.mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('one.txt')

    fireEvent.click(screen.getByRole('button', { name: 'Delete one.txt' }))

    await waitFor(() => expect(mockDeleteDocument).toHaveBeenCalledWith('set-1', 'doc-1'))
    expect(await screen.findByText('No documents yet.')).toBeInTheDocument()
    confirmSpy.mockRestore()
  })

  it('deletes the whole set and navigates back', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockGetSet.mockResolvedValue(makeSet())
    mockList.mockResolvedValue(makePage([makeDoc('doc-1', 'one.txt')]))
    mockDeleteSet.mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('one.txt')

    fireEvent.click(screen.getByRole('button', { name: 'Delete document set' }))

    await waitFor(() => expect(mockDeleteSet).toHaveBeenCalledWith('set-1'))
    confirmSpy.mockRestore()
  })

  it('shows a not-found state', async () => {
    mockGetSet.mockRejectedValue(Object.assign(new Error('missing'), { status: 404 }))
    mockList.mockRejectedValue(Object.assign(new Error('missing'), { status: 404 }))

    renderPage()

    expect(await screen.findByText(/Document set not found/)).toBeInTheDocument()
  })
})