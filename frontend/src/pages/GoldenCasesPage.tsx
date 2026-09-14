import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { Link, useParams } from 'react-router-dom'
import { listAllDocuments } from '../api/documentsets'
import {
  createGoldenCase,
  deleteGoldenCase,
  listGoldenCases,
  runGoldenEvaluation,
  updateGoldenCase,
} from '../api/golden'
import type {
  DocumentListItem,
  GoldenCase,
  GoldenEvalReport,
  GoldenSource,
} from '../api/types'
import TrashIcon from '../components/TrashIcon'

interface PickerDoc {
  id: string
  filename: string
}

interface SourcePickerProps {
  documents: PickerDoc[]
  selectedIds: string[]
  onChange: (ids: string[]) => void
  locked?: GoldenSource[]
  disabled?: boolean
  loading?: boolean
}

function SourcePicker({
  documents,
  selectedIds,
  onChange,
  locked = [],
  disabled,
  loading,
}: SourcePickerProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onMouseDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onMouseDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onMouseDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const toggle = (id: string) => {
    onChange(
      selectedIds.includes(id)
        ? selectedIds.filter((selected) => selected !== id)
        : [...selectedIds, id],
    )
  }

  const value =
    loading
      ? 'Loading…'
      : selectedIds.length > 0
        ? documents
            .filter((doc) => selectedIds.includes(doc.id))
            .map((doc) => doc.filename)
            .join(', ')
        : documents.length === 0
          ? 'No READY documents'
          : 'Select sources…'

  return (
    <div className="source-picker" ref={ref}>
      <button
        type="button"
        className="source-picker-trigger"
        aria-label="Expected sources"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="source-picker-value">{value}</span>
        <span className="source-picker-chevron">{open ? '▲' : '▼'}</span>
      </button>
      {open && documents.length > 0 && (
        <div className="source-picker-panel">
          {documents.map((doc) => (
            <label key={doc.id} className="source-picker-option">
              <input
                type="checkbox"
                checked={selectedIds.includes(doc.id)}
                onChange={() => toggle(doc.id)}
              />
              <span>{doc.filename}</span>
            </label>
          ))}
          {locked.length > 0 && (
            <>
              <div className="source-picker-divider" />
              {locked.map((source) => (
                <label key={source.filename} className="source-picker-option is-locked">
                  <input type="checkbox" checked disabled readOnly />
                  <span>{source.filename} (not in set)</span>
                </label>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function buildSources(ids: string[], documents: DocumentListItem[]): GoldenSource[] {
  return ids
    .map((docId) => documents.find((doc) => doc.id === docId))
    .filter((doc): doc is DocumentListItem => Boolean(doc))
    .map((doc) => ({ filename: doc.filename, docId: doc.id, section: null }))
}

interface RowProps {
  goldenCase: GoldenCase
  editing: boolean
  onEdit: () => void
  onCancel: () => void
  onDelete: () => void
  onPromote: () => void
  onSave: (question: string, answer: string) => void
  onSourcesChange: (ids: string[]) => void
  onQuestionChange: (value: string) => void
  onAnswerChange: (value: string) => void
  documents: PickerDoc[]
  selectedIds: string[]
  retained: GoldenSource[]
  questionDraft: string
  answerDraft: string
  busy: boolean
  loading: boolean
}

function CaseRow(props: RowProps) {
  const { goldenCase: gc } = props
  return (
    <li className="list-col">
      <div className="list-row">
        <div className="list-main">
          <span className="list-title">{gc.question}</span>
          <span className="muted list-sub">{gc.referenceAnswer}</span>
          {gc.expectedSources.length > 0 && (
            <span className="chips">
              {gc.expectedSources.map((source) => (
                <span key={source.filename} className="chip">
                  {source.filename}
                </span>
              ))}
            </span>
          )}
        </div>
        <span className={`badge ${gc.status === 'GOLDEN' ? 'badge-ready' : 'badge-pending'}`}>
          {gc.status}
        </span>
        {gc.status === 'DRAFT' && (
          <button
            className="btn btn-primary"
            type="button"
            disabled={props.busy}
            onClick={props.onPromote}
          >
            Promote
          </button>
        )}
        <button className="btn btn-ghost" type="button" disabled={props.loading} onClick={props.onEdit}>
          {props.editing ? 'Cancel' : 'Edit'}
        </button>
        <button
          className="icon-btn"
          type="button"
          aria-label={`Delete case ${gc.question}`}
          title="Delete case"
          onClick={props.onDelete}
        >
          <TrashIcon />
        </button>
      </div>
      {props.editing && (
        <form
          className="row-edit-form"
          onSubmit={(e) => {
            e.preventDefault()
            props.onSave(props.questionDraft, props.answerDraft)
          }}
        >
          <div className="field">
            <span>Question</span>
            <input
              type="text"
              aria-label="Question"
              value={props.questionDraft}
              onChange={(e) => props.onQuestionChange(e.target.value)}
            />
          </div>
          <div className="field">
            <span>Reference answer</span>
            <textarea
              aria-label="Reference answer"
              value={props.answerDraft}
              onChange={(e) => props.onAnswerChange(e.target.value)}
              rows={3}
            />
          </div>
          <div className="field">
            <span>Expected sources</span>
            <SourcePicker
              documents={props.documents}
              selectedIds={props.selectedIds}
              onChange={props.onSourcesChange}
              locked={props.retained}
              disabled={props.busy || props.loading}
              loading={props.loading}
            />
          </div>
          <button className="btn btn-primary" type="submit" disabled={props.busy}>
            Save
          </button>
        </form>
      )}
    </li>
  )
}

function ReportCard({ report }: { report: GoldenEvalReport }) {
  return (
    <section className="card">
      <h2>Evaluation report</h2>
      <div className="metric-grid">
        <div className="metric">
          <span className="metric-value">{(report.recallAtK * 100).toFixed(1)}%</span>
          <span className="metric-label">Recall@K</span>
        </div>
        <div className="metric">
          <span className="metric-value">{(report.mrr * 100).toFixed(1)}%</span>
          <span className="metric-label">MRR</span>
        </div>
        <div className="metric">
          <span className="metric-value">{(report.hitRate * 100).toFixed(1)}%</span>
          <span className="metric-label">Hit rate</span>
        </div>
        <div className="metric">
          <span className="metric-value">{report.casesEvaluated}</span>
          <span className="metric-label">Cases</span>
        </div>
      </div>
      {report.warnings.length > 0 && (
        <ul className="upload-results">
          {report.warnings.map((warning, i) => (
            <li key={i} className="muted">
              {warning}
            </li>
          ))}
        </ul>
      )}
      {report.cases.length > 0 && (
        <table className="eval-table">
          <thead>
            <tr>
              <th>Question</th>
              <th>Expected</th>
              <th>Retrieved (top)</th>
              <th>Hit</th>
              <th>Rank</th>
              <th>Recall</th>
            </tr>
          </thead>
          <tbody>
            {report.cases.map((row) => (
              <tr key={row.question}>
                <td>{row.question}</td>
                <td className="muted">{row.expected.join(', ')}</td>
                <td className="muted">{row.retrieved.join(', ')}</td>
                <td>{row.hit ? '✓' : '✗'}</td>
                <td>{row.rank}</td>
                <td>{row.recall.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

export default function GoldenCasesPage() {
  const { id = '' } = useParams()

  const [cases, setCases] = useState<GoldenCase[] | null>(null)
  const [docs, setDocs] = useState<DocumentListItem[] | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [report, setReport] = useState<GoldenEvalReport | null>(null)
  const [running, setRunning] = useState(false)

  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState('')
  const [selectedSourceIds, setSelectedSourceIds] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  const [editingId, setEditingId] = useState<string | null>(null)
  const [editQuestion, setEditQuestion] = useState('')
  const [editAnswer, setEditAnswer] = useState('')
  const [editSelectedIds, setEditSelectedIds] = useState<string[]>([])
  const [retainedSources, setRetainedSources] = useState<GoldenSource[]>([])
  const [updating, setUpdating] = useState(false)

  const readyDocs = useMemo(
    () => (docs ?? []).filter((doc) => doc.status === 'READY'),
    [docs],
  )

  const goldenCount = useMemo(
    () => (cases ?? []).filter((goldenCase) => goldenCase.status === 'GOLDEN').length,
    [cases],
  )

  const load = useCallback(async () => {
    setCases(await listGoldenCases(id))
  }, [id])

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [loadedCases, loadedDocs] = await Promise.all([
          listGoldenCases(id),
          listAllDocuments(id),
        ])
        if (!active) return
        setCases(loadedCases)
        setDocs(loadedDocs)
      } catch (err) {
        if (!active) return
        if (err instanceof Error && 'status' in err && (err as { status: number }).status === 404) {
          setNotFound(true)
        } else {
          setError(err instanceof Error ? err.message : 'Could not load golden cases')
        }
      }
    })()
    return () => {
      active = false
    }
  }, [id])

  const handleAdd = async (e: FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await createGoldenCase(id, {
        question,
        answer,
        expectedSources: buildSources(selectedSourceIds, readyDocs),
      })
      setQuestion('')
      setAnswer('')
      setSelectedSourceIds([])
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create golden case')
    } finally {
      setSaving(false)
    }
  }

  const startEdit = (goldenCase: GoldenCase) => {
    const selected: string[] = []
    const retained: GoldenSource[] = []
    for (const source of goldenCase.expectedSources) {
      const byId = readyDocs.find((doc) => doc.id === source.docId)
      const byName = readyDocs.find((doc) => doc.filename === source.filename)
      const match = byId ?? byName
      if (match) {
        if (!selected.includes(match.id)) selected.push(match.id)
      } else {
        retained.push(source)
      }
    }
    setEditingId(goldenCase.id)
    setEditQuestion(goldenCase.question)
    setEditAnswer(goldenCase.referenceAnswer)
    setEditSelectedIds(selected)
    setRetainedSources(retained)
  }

  const handleSaveEdit = async (editQuestion: string, editAnswer: string) => {
    if (!editingId) return
    setUpdating(true)
    setError(null)
    try {
      await updateGoldenCase(id, editingId, {
        question: editQuestion,
        answer: editAnswer,
        expectedSources: [
          ...buildSources(editSelectedIds, readyDocs),
          ...retainedSources,
        ],
      })
      setEditingId(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save golden case')
    } finally {
      setUpdating(false)
    }
  }

  const handlePromote = async (goldenCase: GoldenCase) => {
    setUpdating(true)
    setError(null)
    try {
      await updateGoldenCase(id, goldenCase.id, {
        question: goldenCase.question,
        answer: goldenCase.referenceAnswer,
        expectedSources: goldenCase.expectedSources,
        status: 'GOLDEN',
      })
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not promote golden case')
    } finally {
      setUpdating(false)
    }
  }

  const handleDelete = async (goldenCase: GoldenCase) => {
    if (!window.confirm('Delete this golden case?')) return
    setUpdating(true)
    setError(null)
    try {
      await deleteGoldenCase(id, goldenCase.id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete golden case')
    } finally {
      setUpdating(false)
    }
  }

  const handleRun = async () => {
    setRunning(true)
    setError(null)
    try {
      setReport(await runGoldenEvaluation(id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not run evaluation')
    } finally {
      setRunning(false)
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

  return (
    <>
      <div className="detail-head">
        <Link className="btn btn-ghost" to={`/documentsets/${id}`}>
          ← Back to set
        </Link>
        <h1 className="detail-title">Golden dataset &amp; evaluation</h1>
      </div>
      <p className="muted">
        Review LLM-generated questions, promote them to golden cases and run
        retrieval-only evaluation against the indexed documents.
      </p>
      {error && <div className="alert alert-error">{error}</div>}

      <section className="card">
        <div className="section-head">
          <h2>Run evaluation</h2>
          <button
            className="btn btn-primary"
            type="button"
            disabled={running || cases === null || goldenCount === 0}
            onClick={handleRun}
          >
            {running ? 'Running…' : 'Run'}
          </button>
        </div>
        {report ? (
          <ReportCard report={report} />
        ) : cases === null ? (
          <p className="muted">No report yet.</p>
        ) : goldenCount === 0 ? (
          <p className="muted">
            No report yet. Promote at least one case to GOLDEN, then run.
          </p>
        ) : goldenCount === 1 ? (
          <p className="muted">
            No report yet. One golden case is ready — click Run to evaluate.
          </p>
        ) : (
          <p className="muted">
            No report yet. {goldenCount} golden cases are ready — click Run to evaluate.
          </p>
        )}
      </section>

      <section className="card">
        <h2>Add case</h2>
        <form className="row-edit-form" onSubmit={handleAdd}>
          <div className="field">
            <span>Question</span>
            <input
              type="text"
              aria-label="Question"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
            />
          </div>
          <div className="field">
            <span>Reference answer</span>
            <textarea
              aria-label="Reference answer"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              rows={3}
            />
          </div>
          <div className="field">
            <span>Expected sources</span>
            <SourcePicker
              documents={readyDocs}
              selectedIds={selectedSourceIds}
              onChange={setSelectedSourceIds}
              disabled={saving}
              loading={docs === null}
            />
          </div>
          <button
            className="btn btn-primary"
            type="submit"
            disabled={saving || question.trim() === '' || answer.trim() === ''}
          >
            {saving ? 'Saving…' : 'Add case'}
          </button>
        </form>
      </section>

      <section>
        <div className="section-head">
          <h2>Cases ({cases?.length ?? 0})</h2>
        </div>
        {cases === null ? (
          <p className="muted">Loading…</p>
        ) : cases.length === 0 ? (
          <p className="muted">
            No cases yet. They are generated automatically after documents are
            indexed, or you can add one manually.
          </p>
        ) : (
          <ul className="list">
            {cases.map((goldenCase) => {
              const editing = editingId === goldenCase.id
              return (
                <CaseRow
                  key={goldenCase.id}
                  goldenCase={goldenCase}
                  editing={editing}
                  busy={updating}
                  loading={docs === null}
                  onEdit={() => (editing ? setEditingId(null) : startEdit(goldenCase))}
                  onCancel={() => setEditingId(null)}
                  onDelete={() => handleDelete(goldenCase)}
                  onPromote={() => handlePromote(goldenCase)}
                  onSave={handleSaveEdit}
                  questionDraft={editQuestion}
                  answerDraft={editAnswer}
                  documents={readyDocs}
                  selectedIds={editSelectedIds}
                  retained={retainedSources}
                  onQuestionChange={setEditQuestion}
                  onAnswerChange={setEditAnswer}
                  onSourcesChange={setEditSelectedIds}
                />
              )
            })}
          </ul>
        )}
      </section>
    </>
  )
}