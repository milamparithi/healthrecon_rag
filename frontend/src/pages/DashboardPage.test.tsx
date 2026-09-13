import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createDocumentSet,
  listDocumentSets,
  updateDocumentSet,
} from '../api/documentsets'
import { getQuota } from '../api/quota'
import type { DocumentSet } from '../api/types'
import DashboardPage from './DashboardPage'

vi.mock('../api/documentsets', () => ({
  createDocumentSet: vi.fn(),
  deleteDocumentSet: vi.fn(),
  listDocumentSets: vi.fn(),
  updateDocumentSet: vi.fn(),
}))

vi.mock('../api/quota', () => ({
  getQuota: vi.fn(),
}))

const mockCreate = vi.mocked(createDocumentSet)
const mockList = vi.mocked(listDocumentSets)
const mockGetQuota = vi.mocked(getQuota)
const mockUpdate = vi.mocked(updateDocumentSet)

function makeSet(over: Partial<DocumentSet> = {}): DocumentSet {
  return {
    id: 'set-1',
    name: 'Marketing docs',
    description: 'Q3 collateral',
    status: 'READY',
    documentCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>,
  )
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetQuota.mockResolvedValue({ usedBytes: 0, limitBytes: 104857600 })
  })

  afterEach(() => {
    cleanup()
  })

  it('shows the empty state', async () => {
    mockList.mockResolvedValue([])

    renderPage()

    expect(await screen.findByText(/Nothing here yet/)).toBeInTheDocument()
    expect(mockList).toHaveBeenCalled()
  })

  it('renders the list of document sets', async () => {
    mockList.mockResolvedValue([makeSet()])

    renderPage()

    expect(await screen.findByText('Marketing docs')).toBeInTheDocument()
    expect(screen.getByText(/2 documents/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Marketing docs/ })).toHaveAttribute(
      'href',
      '/documentsets/set-1',
    )
  })

  it('creates a document set and reloads the list', async () => {
    mockList.mockResolvedValue([])
    mockCreate.mockResolvedValue(makeSet())

    renderPage()
    await screen.findByText(/Nothing here yet/)

    fireEvent.change(screen.getByPlaceholderText(/Name, e.g. Marketing docs/), {
      target: { value: 'Q3 papers' },
    })
    fireEvent.change(screen.getByPlaceholderText(/Description \(optional\)/), {
      target: { value: 'Quarterly summaries' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith('Q3 papers', 'Quarterly summaries'),
    )
    expect(mockList).toHaveBeenCalled()
  })

  it('surfaces a load error', async () => {
    mockList.mockRejectedValue(new Error('boom'))

    renderPage()

    expect(await screen.findByText('boom')).toBeInTheDocument()
  })

  it('renders the storage quota', async () => {
    mockList.mockResolvedValue([])
    mockGetQuota.mockResolvedValue({ usedBytes: 10 * 1024 * 1024, limitBytes: 100 * 1024 * 1024 })

    renderPage()

    expect(await screen.findByText(/10\.0 MB of 100\.0 MB used/)).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '10')
  })

  it('renders an edit button and saves the updated set', async () => {
    mockList.mockResolvedValue([makeSet()])
    mockUpdate.mockResolvedValue(makeSet({ name: 'Renamed set', description: 'New blurb' }))

    renderPage()
    await screen.findByText('Marketing docs')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))

    const nameInput = screen.getByPlaceholderText(/^Name$/)
    const form = nameInput.closest('form')
    expect(form).not.toBeNull()
    fireEvent.change(nameInput, { target: { value: 'Renamed set' } })
    fireEvent.change(within(form as HTMLElement).getByPlaceholderText(/Description \(optional\)/), {
      target: { value: 'New blurb' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('set-1', {
        name: 'Renamed set',
        description: 'New blurb',
      }),
    )
    expect(mockList).toHaveBeenCalledTimes(2)
  })

  it('cancels the inline edit without saving', async () => {
    mockList.mockResolvedValue([makeSet()])

    renderPage()
    await screen.findByText('Marketing docs')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByPlaceholderText(/^Name$/), {
      target: { value: 'Should not persist' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument(),
    )
    expect(mockUpdate).not.toHaveBeenCalled()
  })

  it('keeps the save button disabled when the name is empty', async () => {
    mockList.mockResolvedValue([makeSet()])

    renderPage()
    await screen.findByText('Marketing docs')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByPlaceholderText(/^Name$/), {
      target: { value: '' },
    })

    expect(
      screen.getByRole('button', { name: 'Save' }),
    ).toBeDisabled()
  })
})