import {
  useCallback,
  useEffect,
  useState,
  type FormEvent,
} from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  dismissEval,
  getEvalMetrics,
  listEvals,
  promoteEval,
  reviewEval,
} from '../api/evals'
import type {
  AnswerEval,
  AnswerEvalPage,
  EvalMetrics,
  ReviewStatus,
  Verdict,
} from '../api/types'

const PAGE_SIZE = 20

const STATUS_OPTIONS: { value: ReviewStatus | ''; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'REVIEWED', label: 'Reviewed' },
  { value: 'DISMISSED', label: 'Dismissed' },
]

const VERDICTS: Verdict[] = ['ACCEPT', 'REWORD', 'REJECT']

interface ReviewDraft {
  verdict: Verdict
  rating: string
  comment: string
  correctedAnswer: string
}

function flagClass(flag: string): string {
  if (flag === 'GUARDRAIL_REFUSAL') return 'badge-failed'
  if (flag === 'NO_SOURCES') return 'badge-uploading'
  return 'badge-pending'
}

function MetricsBar({ metrics }: { metrics: EvalMetrics | null }) {
  const value = (v: number | null): string =>
    v === null ? '—' : Number(v.toFixed(1)).toString()
  return (
    <div className="metric-grid">
      <div className="metric">
        <span className="metric-value">{metrics?.totalCaptured ?? '…'}</span>
        <span className="metric-label">Captured</span>
      </div>
      <div className="metric">
        <span className="metric-value">{metrics?.flagged ?? '…'}</span>
        <span className="metric-label">Flagged</span>
      </div>
      <div className="metric">
        <span className="metric-value">{metrics?.sampled ?? '…'}</span>
        <span className="metric-label">Sampled</span>
      </div>
      <div className="metric">
        <span className="metric-value">{metrics?.pending ?? '…'}</span>
        <span className="metric-label">Pending</span>
      </div>
      <div className="metric">
        <span className="metric-value">{metrics?.reviewed ?? '…'}</span>
        <span className="metric-label">Reviewed</span>
      </div>
      <div className="metric">
        <span className="metric-value">{value(metrics?.averageRating ?? null)}</span>
        <span className="metric-label">Avg rating</span>
      </div>
    </div>
  )
}

export default function EvalsPage() {
  const { id = '' } = useParams()

  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<ReviewStatus | ''>('')
  const [flagged, setFlagged] = useState(false)
  const [sampled, setSampled] = useState(false)

  const [evals, setEvals] = useState<AnswerEvalPage | null>(null)
  const [metrics, setMetrics] = useState<EvalMetrics | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const [reviewingId, setReviewingId] = useState<string | null>(null)
  const [draft, setDraft] = useState<ReviewDraft>({
    verdict: 'ACCEPT',
    rating: '',
    comment: '',
    correctedAnswer: '',
  })

  const load = useCallback(async (): Promise<[AnswerEvalPage, EvalMetrics]> => {
    return Promise.all([
      listEvals(id, { page, size: PAGE_SIZE, status: status || undefined, flagged, sampled }),
      getEvalMetrics(id),
    ])
  }, [id, page, status, flagged, sampled])

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const result = await load()
        if (!active) return
        setEvals(result[0])
        setMetrics(result[1])
      } catch (err) {
        if (!active) return
        if (err instanceof Error && 'status' in err && (err as { status: number }).status === 404) {
          setNotFound(true)
        } else {
          setError(err instanceof Error ? err.message : 'Could not load output reviews')
        }
      }
    })()
    return () => {
      active = false
    }
  }, [load])

  const goToPage = (target: number) => {
    setPage(Math.min(Math.max(target, 0), Math.max((evals?.totalPages ?? 1) - 1, 0)))
  }

  const resetFilters = () => {
    setPage(0)
  }

  const startReview = (evalRow: AnswerEval) => {
    setReviewingId(evalRow.id)
    setDraft({
      verdict: 'ACCEPT',
      rating: '',
      comment: '',
      correctedAnswer: evalRow.correctedAnswer ?? '',
    })
  }

  const handleReview = async (e: FormEvent) => {
    e.preventDefault()
    if (!reviewingId) return
    const needsCorrection = draft.verdict === 'REWORD' || draft.verdict === 'REJECT'
    const correctedAnswer = draft.correctedAnswer.trim()
    if (needsCorrection && correctedAnswer === '') {
      setError('A corrected answer is required for REWORD or REJECT')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await reviewEval(id, reviewingId, {
        verdict: draft.verdict,
        rating: draft.rating === '' ? null : Number(draft.rating),
        comment: draft.comment.trim() || null,
        correctedAnswer: needsCorrection ? correctedAnswer : draft.correctedAnswer.trim() || null,
      })
      setReviewingId(null)
      const result = await load()
      setEvals(result[0])
      setMetrics(result[1])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the review')
    } finally {
      setBusy(false)
    }
  }

  const handleDismiss = async (evalRow: AnswerEval) => {
    if (!window.confirm('Dismiss this output from the review queue?')) return
    setBusy(true)
    setError(null)
    try {
      await dismissEval(id, evalRow.id)
      const result = await load()
      setEvals(result[0])
      setMetrics(result[1])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not dismiss the output')
    } finally {
      setBusy(false)
    }
  }

  const handlePromote = async (evalRow: AnswerEval) => {
    if (!evalRow.correctedAnswer) return
    if (!window.confirm('Promote the corrected answer to a golden case?')) return
    setBusy(true)
    setError(null)
    try {
      await promoteEval(id, evalRow.id)
      const result = await load()
      setEvals(result[0])
      setMetrics(result[1])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not promote to a golden case')
    } finally {
      setBusy(false)
    }
  }

  if (notFound) {
    return (
      <div className="card">
        <p className="muted">Document set not found.</p>
        <Link className="btn" to="/">
          Back to dashboard
        </Link>
      </div>
    )
  }

  const totalElements = evals?.totalElements ?? 0
  const totalPages = evals?.totalPages ?? 0

  return (
    <>
      <div className="detail-head">
        <Link className="btn btn-ghost" to={`/documentsets/${id}`}>
          ← Back to set
        </Link>
        <h1 className="detail-title">Output reviews</h1>
      </div>
      <p className="muted">
        Chat answers are captured automatically. Flagged outputs (guardrail
        refusals, low grounding coverage, missing sources) and a random sample
        are queued for human review here.
      </p>
      {error && <div className="alert alert-error">{error}</div>}

      <section className="card">
        <div className="section-head">
          <h2>Overview</h2>
        </div>
        <MetricsBar metrics={metrics} />
      </section>

      <section className="card">
        <div className="section-head">
          <h2>Reviews ({totalElements})</h2>
          <div className="filter-row">
            <select
              value={status}
              aria-label="Review status"
              onChange={(e) => {
                setStatus(e.target.value as ReviewStatus | '')
                resetFilters()
              }}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <label className="filter-option">
              <input
                type="checkbox"
                aria-label="Only flagged"
                checked={flagged}
                onChange={(e) => {
                  setFlagged(e.target.checked)
                  resetFilters()
                }}
              />
              <span>Flagged</span>
            </label>
            <label className="filter-option">
              <input
                type="checkbox"
                aria-label="Only sampled"
                checked={sampled}
                onChange={(e) => {
                  setSampled(e.target.checked)
                  resetFilters()
                }}
              />
              <span>Sampled</span>
            </label>
          </div>
        </div>

        {evals === null ? (
          <p className="muted">Loading…</p>
        ) : evals.content.length === 0 ? (
          <p className="muted">No outputs match these filters.</p>
        ) : (
          <ul className="list">
            {evals.content.map((evalRow) => {
              const reviewing = reviewingId === evalRow.id
              return (
                <li className="list-col" key={evalRow.id}>
                  <div className="list-row">
                    <div className="list-main">
                      <span className="list-title">{evalRow.question}</span>
                      <span className="muted list-sub">{evalRow.answer}</span>
                      {evalRow.sources.length > 0 && (
                        <span className="chips">
                          {evalRow.sources.map((source) => (
                            <span key={source.filename} className="chip">
                              {source.filename}
                            </span>
                          ))}
                        </span>
                      )}
                      {evalRow.autoFlags.map((flag) => (
                        <span key={flag} className={`badge ${flagClass(flag)}`}>
                          {flag}
                        </span>
                      ))}
                      {evalRow.sampled && <span className="badge badge-pending">sampled</span>}
                      {evalRow.coverageScore !== null && (
                        <span className="muted list-sub">
                          coverage {(evalRow.coverageScore * 100).toFixed(0)}%
                        </span>
                      )}
                    </div>
                    <span
                      className={`badge ${evalRow.reviewStatus === 'REVIEWED' ? 'badge-ready' : evalRow.reviewStatus === 'DISMISSED' ? 'badge-empty' : 'badge-pending'}`}
                    >
                      {evalRow.reviewStatus}
                    </span>
                    {evalRow.reviewStatus === 'PENDING' && (
                      <>
                        <button
                          className="btn btn-primary"
                          type="button"
                          disabled={busy}
                          onClick={() => (reviewing ? setReviewingId(null) : startReview(evalRow))}
                        >
                          {reviewing ? 'Cancel' : 'Review'}
                        </button>
                        <button
                          className="btn btn-ghost"
                          type="button"
                          disabled={busy}
                          onClick={() => handleDismiss(evalRow)}
                        >
                          Dismiss
                        </button>
                      </>
                    )}
                    {evalRow.reviewStatus === 'REVIEWED' && evalRow.correctedAnswer && (
                      <button
                        className="btn btn-ghost"
                        type="button"
                        disabled={busy}
                        onClick={() => handlePromote(evalRow)}
                      >
                        Promote
                      </button>
                    )}
                  </div>
                  {reviewing && (
                    <form className="row-edit-form" onSubmit={handleReview}>
                      <div className="field">
                        <span>Verdict</span>
                        <select
                          aria-label="Verdict"
                          value={draft.verdict}
                          onChange={(e) =>
                            setDraft((d) => ({ ...d, verdict: e.target.value as Verdict }))
                          }
                        >
                          {VERDICTS.map((verdict) => (
                            <option key={verdict} value={verdict}>
                              {verdict}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="field">
                        <span>Rating (1–5)</span>
                        <input
                          type="number"
                          min={1}
                          max={5}
                          aria-label="Rating"
                          value={draft.rating}
                          onChange={(e) => setDraft((d) => ({ ...d, rating: e.target.value }))}
                        />
                      </div>
                      <div className="field">
                        <span>Comment</span>
                        <textarea
                          aria-label="Comment"
                          rows={2}
                          value={draft.comment}
                          onChange={(e) => setDraft((d) => ({ ...d, comment: e.target.value }))}
                        />
                      </div>
                      {(draft.verdict === 'REWORD' || draft.verdict === 'REJECT') && (
                        <div className="field">
                          <span>Corrected answer</span>
                          <textarea
                            aria-label="Corrected answer"
                            rows={3}
                            value={draft.correctedAnswer}
                            onChange={(e) =>
                              setDraft((d) => ({ ...d, correctedAnswer: e.target.value }))
                            }
                          />
                        </div>
                      )}
                      <button className="btn btn-primary" type="submit" disabled={busy}>
                        Save review
                      </button>
                    </form>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {totalElements > 0 && (
          <div className="pager">
            <button
              className="btn btn-ghost"
              type="button"
              disabled={page === 0}
              onClick={() => goToPage(page - 1)}
            >
              ← Prev
            </button>
            <span className="muted">
              {totalElements} output{totalElements === 1 ? '' : 's'}
              {totalPages > 1 ? ` · page ${page + 1} of ${totalPages}` : ''}
            </span>
            <button
              className="btn btn-ghost"
              type="button"
              disabled={page >= totalPages - 1}
              onClick={() => goToPage(page + 1)}
            >
              Next →
            </button>
          </div>
        )}
      </section>
    </>
  )
}