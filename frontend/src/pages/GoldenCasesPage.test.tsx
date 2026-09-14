import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listAllDocuments } from '../api/documentsets'
import {
  createGoldenCase,
  deleteGoldenCase,
  listGoldenCases,
  runGoldenEvaluation,
  updateGoldenCase,
} from '../api/golden'
import type { DocumentListItem, GoldenCase, GoldenEvalReport } from '../api/types'
import GoldenCasesPage from './GoldenCasesPage'

vi.mock('../api/documentsets', () => ({
  listAllDocuments: vi.fn(),
}))

vi.mock('../api/golden', () => ({
  createGoldenCase: vi.fn(),
  deleteGoldenCase: vi.fn(),
  listGoldenCases: vi.fn(),
  runGoldenEvaluation: vi.fn(),
  updateGoldenCase: vi.fn(),
}))

const mockListAll = vi.mocked(listAllDocuments)
const mockCreate = vi.mocked(createGoldenCase)
const mockDelete = vi.mocked(deleteGoldenCase)
const mockList = vi.mocked(listGoldenCases)
const mockRun = vi.mocked(runGoldenEvaluation)
const mockUpdate = vi.mocked(updateGoldenCase)

function makeDoc(over: Partial<DocumentListItem> = {}): DocumentListItem {
  return {
    id: 'doc-1',
    filename: 'headache.md',
    contentType: 'text/markdown',
    contentLength: 10,
    status: 'READY',
    error: null,
    createdAt: '2026-01-01T00:00:00',
    ...over,
  }
}

function makeCase(over: Partial<GoldenCase> = {}): GoldenCase {
  return {
    id: 'case-1',
    docSetId: 'set-1',
    sourceDocId: 'doc-1',
    question: 'What is the first step?',
    referenceAnswer: 'Take paracetamol 500 mg.',
    expectedSources: [{ filename: 'headache.md', docId: 'doc-1', section: null }],
    status: 'DRAFT',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...over,
  }
}

function makeReport(over: Partial<GoldenEvalReport> = {}): GoldenEvalReport {
  return {
    casesEvaluated: 1,
    recallAtK: 1,
    mrr: 1,
    hitRate: 1,
    cases: [
      {
        question: 'What is the first step?',
        expected: ['headache.md'],
        retrieved: ['headache.md'],
        hit: true,
        rank: 1,
        recall: 1,
      },
    ],
    warnings: [],
    ...over,
  }
}

function renderPage(id = 'set-1') {
  return render(
    <MemoryRouter initialEntries={[`/documentsets/${id}/evaluation`]}>
      <Routes>
        <Route
          path="/documentsets/:id/evaluation"
          element={<GoldenCasesPage />}
        />
      </Routes>
    </MemoryRouter>,
  )
}

describe('GoldenCasesPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockListAll.mockResolvedValue([makeDoc()])
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders golden cases with status badges', async () => {
    mockList.mockResolvedValue([
      makeCase(),
      makeCase({ id: 'case-2', question: 'Second?', status: 'GOLDEN' }),
    ])

    renderPage()

    expect(await screen.findByText('What is the first step?')).toBeInTheDocument()
    expect(screen.getByText('Second?')).toBeInTheDocument()
    expect(screen.getAllByText('DRAFT').length).toBe(1)
    expect(screen.getAllByText('GOLDEN').length).toBe(1)
  })

  it('adds a case through the form', async () => {
    mockList.mockResolvedValue([])
    mockListAll.mockResolvedValue([
      makeDoc(),
      makeDoc({ id: 'doc-2', filename: 'redflag.md' }),
    ])
    mockCreate.mockResolvedValue(makeCase())

    renderPage()
    await screen.findByText(/No cases yet/)

    fireEvent.change(screen.getByLabelText('Question'), {
      target: { value: 'Dosage?' },
    })
    fireEvent.change(screen.getByLabelText(/Reference answer/), {
      target: { value: '500 mg.' },
    })
    fireEvent.click(screen.getByLabelText('Expected sources'))
    fireEvent.click(screen.getByLabelText('redflag.md'))
    fireEvent.click(screen.getByRole('button', { name: 'Add case' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith('set-1', {
        question: 'Dosage?',
        answer: '500 mg.',
        expectedSources: [
          { filename: 'redflag.md', docId: 'doc-2', section: null },
        ],
      }),
    )
  })

  it('only offers READY documents as expected sources', async () => {
    mockList.mockResolvedValue([])
    mockListAll.mockResolvedValue([
      makeDoc(),
      makeDoc({ id: 'doc-2', filename: 'todo.doc', status: 'FAILED' }),
    ])

    renderPage()
    await screen.findByText(/No cases yet/)

    fireEvent.click(screen.getByLabelText('Expected sources'))

    expect(screen.getByLabelText('headache.md')).toBeInTheDocument()
    expect(screen.queryByText('todo.doc')).not.toBeInTheDocument()
  })

  it('promotes a draft case to golden', async () => {
    mockList.mockResolvedValue([makeCase()])
    mockUpdate.mockResolvedValue(makeCase({ status: 'GOLDEN' }))

    renderPage()
    await screen.findByText('What is the first step?')

    fireEvent.click(screen.getByRole('button', { name: 'Promote' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'set-1',
        'case-1',
        expect.objectContaining({ status: 'GOLDEN' }),
      ),
    )
  })

  it('shows a run-ready hint when a golden case exists without a report', async () => {
    mockList.mockResolvedValue([makeCase({ status: 'GOLDEN' })])

    renderPage()
    await screen.findByText('What is the first step?')

    expect(
      screen.getByText(/One golden case is ready — click Run to evaluate/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/Promote at least one case to GOLDEN/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled()
  })

  it('disables Run and shows the promote hint when there are no golden cases', async () => {
    mockList.mockResolvedValue([makeCase({ status: 'DRAFT' })])

    renderPage()
    await screen.findByText('What is the first step?')

    expect(
      screen.getByText(/No report yet\. Promote at least one case to GOLDEN, then run\./),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled()
  })

  it('edits an existing case', async () => {
    mockList.mockResolvedValue([makeCase()])
    mockUpdate.mockResolvedValue(makeCase())

    renderPage()
    await screen.findByText('What is the first step?')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const questionInput = screen.getAllByLabelText('Question')[1]
    fireEvent.change(questionInput, { target: { value: 'New question?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('set-1', 'case-1', {
        question: 'New question?',
        answer: 'Take paracetamol 500 mg.',
        expectedSources: [
          { filename: 'headache.md', docId: 'doc-1', section: null },
        ],
      }),
    )
  })

  it('keeps sources that are no longer in the set when editing', async () => {
    mockList.mockResolvedValue([
      makeCase({
        expectedSources: [{ filename: 'old.md', docId: 'doc-x', section: null }],
      }),
    ])
    mockUpdate.mockResolvedValue(makeCase())

    renderPage()
    await screen.findByText('What is the first step?')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.click(screen.getAllByLabelText('Expected sources')[1])

    expect(screen.getByText(/old\.md \(not in set\)/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('set-1', 'case-1', {
        question: 'What is the first step?',
        answer: 'Take paracetamol 500 mg.',
        expectedSources: [{ filename: 'old.md', docId: 'doc-x', section: null }],
      }),
    )
  })

  it('deletes a case after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockList
      .mockResolvedValueOnce([makeCase()])
      .mockResolvedValue([])
    mockDelete.mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('What is the first step?')

    fireEvent.click(screen.getByRole('button', { name: 'Delete case What is the first step?' }))

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('set-1', 'case-1'))
    expect(await screen.findByText(/No cases yet/)).toBeInTheDocument()
    confirmSpy.mockRestore()
  })

  it('runs the evaluation and shows the report', async () => {
    mockList.mockResolvedValue([makeCase({ status: 'GOLDEN' })])
    mockRun.mockResolvedValue(makeReport())

    renderPage()
    await screen.findByText('What is the first step?')

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText('Evaluation report')).toBeInTheDocument()
    expect(screen.getAllByText('100.0%')).toHaveLength(3)
    expect(screen.getByText('Recall@K')).toBeInTheDocument()
    expect(screen.getByText('MRR')).toBeInTheDocument()
    expect(screen.getByText('Hit rate')).toBeInTheDocument()
    expect(screen.getAllByText('headache.md').length).toBeGreaterThan(0)
  })

  it('shows a not-found state', async () => {
    mockList.mockRejectedValue(Object.assign(new Error('missing'), { status: 404 }))

    renderPage()

    expect(await screen.findByText(/Document set not found/)).toBeInTheDocument()
  })
})