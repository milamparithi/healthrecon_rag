import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dismissEval, getEvalMetrics, listEvals, promoteEval, reviewEval } from '../api/evals'
import type { AnswerEval, AnswerEvalPage, EvalMetrics } from '../api/types'
import EvalsPage from './EvalsPage'

vi.mock('../api/evals', () => ({
  dismissEval: vi.fn(),
  getEvalMetrics: vi.fn(),
  listEvals: vi.fn(),
  promoteEval: vi.fn(),
  reviewEval: vi.fn(),
}))

const mockDismiss = vi.mocked(dismissEval)
const mockMetrics = vi.mocked(getEvalMetrics)
const mockList = vi.mocked(listEvals)
const mockPromote = vi.mocked(promoteEval)
const mockReview = vi.mocked(reviewEval)

function makeEval(over: Partial<AnswerEval> = {}): AnswerEval {
  return {
    id: 'eval-1',
    docSetId: 'set-1',
    chatMessageId: 'msg-1',
    conversationId: 'conv-1',
    question: 'What dosage?',
    answer: 'Take 500 mg.',
    sources: [{ docId: 'doc-1', filename: 'guide.md', section: 'Dosage', snippet: 'Take 500 mg.' }],
    coverageScore: 0.95,
    autoFlags: [],
    sampled: false,
    origin: 'CHAT',
    reviewStatus: 'PENDING',
    verdict: null,
    rating: null,
    comment: null,
    correctedAnswer: null,
    reviewedAt: null,
    createdAt: '2026-01-01T00:00:00',
    ...over,
  }
}

function makePage(content: AnswerEval[], over: Partial<AnswerEvalPage> = {}): AnswerEvalPage {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    ...over,
  }
}

function makeMetrics(over: Partial<EvalMetrics> = {}): EvalMetrics {
  return {
    totalCaptured: 5,
    flagged: 1,
    sampled: 1,
    pending: 3,
    reviewed: 1,
    dismissed: 1,
    accepted: 0,
    reworded: 1,
    rejected: 0,
    averageRating: 4.5,
    ...over,
  }
}

function renderPage(id = 'set-1') {
  return render(
    <MemoryRouter initialEntries={[`/documentsets/${id}/reviews`]}>
      <Routes>
        <Route path="/documentsets/:id/reviews" element={<EvalsPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('EvalsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockMetrics.mockResolvedValue(makeMetrics())
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('renders captured outputs with flags, sampling and metrics', async () => {
    mockList.mockResolvedValue(
      makePage([
        makeEval({ autoFlags: ['GUARDRAIL_REFUSAL'] }),
        makeEval({
          id: 'eval-2',
          question: 'Second one?',
          answer: 'Second answer.',
          autoFlags: ['LOW_COVERAGE'],
          sampled: true,
        }),
      ]),
    )

    renderPage()

    expect(await screen.findByText('What dosage?')).toBeInTheDocument()
    expect(screen.getByText('Second one?')).toBeInTheDocument()
    expect(screen.getByText('GUARDRAIL_REFUSAL')).toBeInTheDocument()
    expect(screen.getByText('LOW_COVERAGE')).toBeInTheDocument()
    expect(screen.getAllByText('sampled').length).toBe(1)
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getAllByText('Flagged').length).toBe(2)
  })

  it('reviews a pending output with verdict and corrected answer', async () => {
    mockList.mockResolvedValue(makePage([makeEval()]))
    mockReview.mockResolvedValue(
      makeEval({ reviewStatus: 'REVIEWED', verdict: 'REJECT', correctedAnswer: 'Take 500 mg.' }),
    )

    renderPage()

    const reviewButton = await screen.findByRole('button', { name: 'Review' })
    fireEvent.click(reviewButton)

    const verdict = screen.getByLabelText('Verdict') as HTMLSelectElement
    fireEvent.change(verdict, { target: { value: 'REJECT' } })
    const rating = screen.getByLabelText('Rating') as HTMLInputElement
    fireEvent.change(rating, { target: { value: '1' } })
    const corrected = screen.getByLabelText('Corrected answer') as HTMLTextAreaElement
    fireEvent.change(corrected, { target: { value: 'Take 500 mg.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save review' }))

    await waitFor(() => {
      expect(mockReview).toHaveBeenCalledWith('set-1', 'eval-1', {
        verdict: 'REJECT',
        rating: 1,
        comment: null,
        correctedAnswer: 'Take 500 mg.',
      })
    })
  })

  it('blocks REWORD/REJECT without a corrected answer', async () => {
    mockList.mockResolvedValue(makePage([makeEval()]))

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Review' }))
    fireEvent.change(screen.getByLabelText('Verdict'), { target: { value: 'REJECT' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save review' }))

    expect(
      await screen.findByText('A corrected answer is required for REWORD or REJECT'),
    ).toBeInTheDocument()
    expect(mockReview).not.toHaveBeenCalled()
  })

  it('promotes a reviewed corrected answer and dismisses pending outputs', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockList.mockResolvedValue(
      makePage([
        makeEval(),
        makeEval({
          id: 'eval-2',
          question: 'Reviewed one?',
          reviewStatus: 'REVIEWED',
          verdict: 'REWORD',
          correctedAnswer: 'Corrected answer.',
        }),
      ]),
    )
    mockPromote.mockResolvedValue({ id: 'case-9' } as never)
    mockDismiss.mockResolvedValue(undefined)

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Promote' }))
    await waitFor(() => {
      expect(mockPromote).toHaveBeenCalledWith('set-1', 'eval-2')
    })

    const dismissButtons = screen.getAllByRole('button', { name: 'Dismiss' })
    fireEvent.click(dismissButtons[0])
    await waitFor(() => {
      expect(mockDismiss).toHaveBeenCalledWith('set-1', 'eval-1')
    })
  })
})