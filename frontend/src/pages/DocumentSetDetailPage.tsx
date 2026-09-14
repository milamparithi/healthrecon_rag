import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  deleteAllDocuments,
  deleteDocument,
  deleteDocumentSet,
  getDocument,
  getDocumentSet,
  listDocuments,
  uploadDocuments,
} from '../api/documentsets'
import type {
  DocumentDetail,
  DocumentListItem,
  DocumentPage,
  DocumentSet,
} from '../api/types'
import StatusBadge from '../components/StatusBadge'
import ChatPanel from '../components/ChatPanel'
import TrashIcon from '../components/TrashIcon'

const POLL_MS = 3000
const PAGE_SIZE = 20

function isBusy(
  set: DocumentSet | null,
  docs: DocumentListItem[] | null,
): boolean {
  if (set?.status === 'UPLOADING') return true
  return !!docs?.some((d) => d.status === 'UPLOADED' || d.status === 'PENDING' || d.status === 'EXTRACTING')
}

export default function DocumentSetDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()

  const [set, setSet] = useState<DocumentSet | null>(null)
  const [docs, setDocs] = useState<DocumentListItem[] | null>(null)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [files, setFiles] = useState<File[]>([])
  const [uploading, setUploading] = useState(false)
  const [uploadResults, setUploadResults] = useState<string[]>([])
  const [expanded, setExpanded] = useState<Record<string, DocumentDetail>>({})
  const [loadingDetail, setLoadingDetail] = useState<string | null>(null)

  const fetchPage = useCallback(
    async (targetPage: number): Promise<[DocumentSet, DocumentPage]> => {
      const [nextSet, nextPage] = await Promise.all([
        getDocumentSet(id),
        listDocuments(id, targetPage, PAGE_SIZE),
      ])
      return [nextSet, nextPage]
    },
    [id],
  )

  const applyPage = useCallback((nextSet: DocumentSet, nextPage: DocumentPage) => {
    setSet(nextSet)
    setDocs(nextPage.content)
    setTotalElements(nextPage.totalElements)
    setTotalPages(nextPage.totalPages)
    setNotFound(false)
  }, [])

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [nextSet, nextPage] = await fetchPage(page)
        if (!active) return
        const maxPage = Math.max(nextPage.totalPages - 1, 0)
        if (page > maxPage) {
          setPage(maxPage)
        } else {
          applyPage(nextSet, nextPage)
        }
      } catch (err) {
        if (active) {
          if (err instanceof Error && 'status' in err && (err as { status: number }).status === 404) {
            setNotFound(true)
          } else {
            setError(err instanceof Error ? err.message : 'Could not load document set')
          }
        }
      }
    })()
    return () => {
      active = false
    }
  }, [id, page, fetchPage, applyPage])

  useEffect(() => {
    if (!isBusy(set, docs)) return
    let active = true
    const poll = async () => {
      if (!active) return
      try {
        const [nextSet, nextPage] = await fetchPage(page)
        if (!active) return
        applyPage(nextSet, nextPage)
        if (!isBusy(nextSet, nextPage.content)) {
          window.clearInterval(id)
        }
      } catch {
        window.clearInterval(id)
      }
    }
    const id = window.setInterval(poll, POLL_MS)
    return () => {
      active = false
      window.clearInterval(id)
    }
  }, [set, docs, page, fetchPage, applyPage])

  const goToPage = (target: number) => {
    setPage(Math.min(Math.max(target, 0), Math.max(totalPages - 1, 0)))
  }

  const handleUpload = async (e: FormEvent) => {
    e.preventDefault()
    if (files.length === 0) return
    setUploading(true)
    setUploadResults([])
    setError(null)
    try {
      const results = await uploadDocuments(id, files)
      setUploadResults(
        results.map((r) =>
          r.status === 'UPLOADED'
            ? `Uploaded ${r.filename}`
            : r.status === 'DUPLICATE'
              ? `${r.filename} is a duplicate`
              : `Failed to read ${r.filename}: ${r.message ?? 'unknown error'}`,
        ),
      )
      setFiles([])
      const [nextSet, nextPage] = await fetchPage(page)
      applyPage(nextSet, nextPage)
      void isBusy(nextSet, nextPage.content)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const toggleDetail = async (doc: DocumentListItem) => {
    if (expanded[doc.id]) {
      const next = { ...expanded }
      delete next[doc.id]
      setExpanded(next)
      return
    }
    setLoadingDetail(doc.id)
    try {
      const detail = await getDocument(id, doc.id)
      setExpanded((prev) => ({ ...prev, [doc.id]: detail }))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load document detail')
    } finally {
      setLoadingDetail(null)
    }
  }

  const handleDeleteDocument = async (docId: string) => {
    if (!window.confirm('Delete this document?')) return
    try {
      await deleteDocument(id, docId)
      setExpanded((prev) => {
        const next = { ...prev }
        delete next[docId]
        return next
      })
      const [nextSet, nextPage] = await fetchPage(page)
      const maxPage = Math.max(nextPage.totalPages - 1, 0)
      if (page > maxPage) {
        setPage(maxPage)
      } else {
        applyPage(nextSet, nextPage)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete document')
    }
  }

  const handleDeleteAll = async () => {
    if (!window.confirm('Delete ALL documents in this set?')) return
    try {
      await deleteAllDocuments(id)
      setExpanded({})
      setDocs([])
      setTotalElements(0)
      setTotalPages(0)
      setPage(0)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete documents')
    }
  }

  const handleDeleteSet = async () => {
    if (!window.confirm('Delete this document set and all its documents?')) return
    try {
      await deleteDocumentSet(id)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete document set')
    }
  }

  if (notFound) {
    return (
      <>

        <div className="card">
          <p className="muted">Document set not found.</p>
          <Link className="btn" to="/">
            Back to dashboard
          </Link>
        </div>
      </>
    )
  }

  if (!set) {
    return <p className="muted">Loading…</p>
  }

  return (
    <>
      <div className="detail-head">
        <Link className="btn btn-ghost" to="/">
          ← Dashboard
        </Link>
        <h1 className="detail-title">{set.name}</h1>
        <StatusBadge status={set.status} />
        <Link className="btn btn-ghost" to={`/documentsets/${id}/evaluation`}>
          Evaluation
        </Link>
        <Link className="btn btn-ghost" to={`/documentsets/${id}/reviews`}>
          Reviews
        </Link>
      </div>
      {set.description && <p className="muted">{set.description}</p>}
      {error && <div className="alert alert-error">{error}</div>}

      <div className="detail-layout">
        <div className="detail-col">
          <section className="card">
            <h2>Upload documents</h2>
            <form className="form-inline" onSubmit={handleUpload}>
              <input
                className="grow"
                type="file"
                multiple
                accept=".txt,.md,.pdf,.docx,.doc"
                onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
              />
              <button
                className="btn btn-primary"
                type="submit"
                disabled={uploading || files.length === 0}
              >
                {uploading ? 'Uploading…' : 'Upload'}
              </button>
            </form>
            {files.length > 0 && (
              <p className="muted file-hint">
                {files.length} file{files.length === 1 ? '' : 's'} selected
              </p>
            )}
            {uploadResults.length > 0 && (
              <ul className="upload-results">
                {uploadResults.map((msg, i) => (
                  <li key={i} className="muted">
                    {msg}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <div className="section-head">
              <h2>Documents</h2>
              {isBusy(set, docs) && <span className="muted">ingesting…</span>}
              {docs !== null && docs.length > 0 && (
                <button
                  className="btn btn-ghost btn-danger-text"
                  type="button"
                  onClick={handleDeleteAll}
                >
                  Delete all
                </button>
              )}
            </div>
            {docs === null ? (
              <p className="muted">Loading…</p>
            ) : docs.length === 0 ? (
              <p className="muted">
                {totalElements === 0 ? 'No documents yet.' : 'This page is empty.'}
              </p>
            ) : (
              <ul className="list">
                {docs.map((doc) => (
                  <li key={doc.id} className="list-col">
                    <div className="list-row">
                      <button
                        className="btn btn-ghost list-main"
                        type="button"
                        onClick={() => toggleDetail(doc)}
                      >
                        <span className="list-title">{doc.filename}</span>
                        <span className="muted list-sub">
                          {(doc.contentLength / 1024).toFixed(1)} KB{doc.error ? ` · ${doc.error}` : ''}
                        </span>
                      </button>
                      <StatusBadge status={doc.status} />
                      <button
                        className="icon-btn"
                        type="button"
                        aria-label={`Delete ${doc.filename}`}
                        title="Delete document"
                        onClick={() => handleDeleteDocument(doc.id)}
                      >
                        <TrashIcon />
                      </button>
                    </div>
                    {loadingDetail === doc.id && <p className="muted doc-detail">Loading text…</p>}
                    {expanded[doc.id] && (
                      <div className="doc-detail card">
                        <p className="doc-text">
                          {expanded[doc.id].extractedText ?? 'No extracted text yet.'}
                        </p>
                      </div>
                    )}
                  </li>
                ))}
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
                  {totalElements} document{totalElements === 1 ? '' : 's'}
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

          <div className="danger-zone">
            <button className="btn btn-danger" type="button" onClick={handleDeleteSet}>
              Delete document set
            </button>
          </div>
        </div>

        <div className="detail-col">
          <ChatPanel documentSetId={id} enabled={set.status === 'READY'} />
        </div>
      </div>
    </>
  )
}