import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  createDocumentSet,
  deleteDocumentSet,
  listDocumentSets,
  updateDocumentSet,
} from '../api/documentsets'
import { getQuota } from '../api/quota'
import type { DocumentSet, Quota } from '../api/types'
import StatusBadge from '../components/StatusBadge'
import TrashIcon from '../components/TrashIcon'

const POLL_MS = 3000

function formatBytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export default function DashboardPage() {
  const [sets, setSets] = useState<DocumentSet[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [quota, setQuota] = useState<Quota | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)

  const isBusy = useCallback(
    (data: DocumentSet[] | null) =>
      !!data?.some((s) => s.status === 'UPLOADING'),
    [],
  )

  const load = useCallback(async (): Promise<DocumentSet[] | null> => {
    try {
      const data = await listDocumentSets()
      setLoadError(null)
      setSets(data)
      return data
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Could not load document sets')
      return null
    }
  }, [])

  useEffect(() => {
    let active = true
    void (async () => {
      const data = await listDocumentSets()
      if (active) {
        setSets(data)
      }
    })().catch((err: unknown) => {
      if (active) {
        setLoadError(err instanceof Error ? err.message : 'Could not load document sets')
      }
    })
    void getQuota()
      .then((q) => {
        if (active) setQuota(q)
      })
      .catch(() => {
        // quota display is best-effort
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    if (!sets || !isBusy(sets)) return
    let active = true
    const id = window.setInterval(async () => {
      if (!active) return
      const data = await load()
      if (data && !isBusy(data)) {
        window.clearInterval(id)
      }
    }, POLL_MS)
    return () => {
      active = false
      window.clearInterval(id)
    }
  }, [sets, isBusy, load])

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault()
    setCreateError(null)
    setCreating(true)
    try {
      await createDocumentSet(name.trim(), description.trim())
      setName('')
      setDescription('')
      await load()
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Could not create document set')
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async (id: string) => {
    if (!window.confirm('Delete this document set and all its documents?')) return
    try {
      await deleteDocumentSet(id)
      await load()
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Could not delete document set')
    }
  }

  const startEdit = (set: DocumentSet) => {
    setEditingId(set.id)
    setEditName(set.name)
    setEditDescription(set.description ?? '')
    setEditError(null)
  }

  const cancelEdit = () => {
    setEditingId(null)
    setEditName('')
    setEditDescription('')
    setEditError(null)
  }

  const handleSaveEdit = async (id: string, e: FormEvent) => {
    e.preventDefault()
    const trimmed = editName.trim()
    if (!trimmed) return
    setSavingEdit(true)
    setEditError(null)
    try {
      await updateDocumentSet(id, {
        name: trimmed,
        description: editDescription.trim() || null,
      })
      cancelEdit()
      await load()
    } catch (err) {
      setEditError(err instanceof Error ? err.message : 'Could not update document set')
    } finally {
      setSavingEdit(false)
    }
  }

  return (
    <>
      <section className="card">
        <h2>New document set</h2>
        <form className="form-inline" onSubmit={handleCreate}>
          <input
            className="grow"
            type="text"
            placeholder="Name, e.g. Marketing docs"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <input
            className="grow"
            type="text"
            placeholder="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
          <button className="btn btn-primary" type="submit" disabled={creating}>
            {creating ? 'Creating…' : 'Create'}
          </button>
        </form>
        {createError && <div className="alert alert-error">{createError}</div>}
      </section>

      {loadError && <div className="alert alert-error">{loadError}</div>}

      {quota && (
        <section className="card quota">
          <div className="quota-row">
            <span className="muted">
              Storage {formatBytes(quota.usedBytes)} of {formatBytes(quota.limitBytes)} used
            </span>
            <span className="muted quota-pct">
              {quota.limitBytes > 0
                ? `${Math.round((quota.usedBytes / quota.limitBytes) * 100)}%`
                : ''}
            </span>
          </div>
          <div
            className="quota-bar"
            role="progressbar"
            aria-valuenow={Math.round(
              quota.limitBytes > 0 ? (quota.usedBytes / quota.limitBytes) * 100 : 0,
            )}
            aria-valuemin={0}
            aria-valuemax={100}
          >
            <div
              className="quota-fill"
              style={{
                width: `${Math.min(
                  100,
                  quota.limitBytes > 0 ? (quota.usedBytes / quota.limitBytes) * 100 : 0,
                )}%`,
              }}
            />
          </div>
        </section>
      )}

      <section>
        <div className="section-head">
          <h2>Your document sets</h2>
          {isBusy(sets) && <span className="muted">ingesting…</span>}
        </div>
        {sets === null ? (
          <p className="muted">Loading…</p>
        ) : sets.length === 0 ? (
          <p className="muted">
            Nothing here yet. Create a document set to get started.
          </p>
        ) : (
          <ul className="list">
            {sets.map((set) =>
              editingId === set.id ? (
                <li key={set.id} className="list-col">
                  <form
                    className="form-inline row-edit-form"
                    onSubmit={(e) => handleSaveEdit(set.id, e)}
                  >
                    <input
                      className="grow"
                      type="text"
                      placeholder="Name"
                      required
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                    />
                    <input
                      className="grow"
                      type="text"
                      placeholder="Description (optional)"
                      value={editDescription}
                      onChange={(e) => setEditDescription(e.target.value)}
                    />
                    <button
                      className="btn btn-primary"
                      type="submit"
                      disabled={savingEdit || editName.trim().length === 0}
                    >
                      {savingEdit ? 'Saving…' : 'Save'}
                    </button>
                    <button
                      className="btn btn-ghost"
                      type="button"
                      onClick={cancelEdit}
                    >
                      Cancel
                    </button>
                  </form>
                  {editError && <div className="alert alert-error row-edit-error">{editError}</div>}
                </li>
              ) : (
                <li key={set.id} className="list-row">
                  <Link to={`/documentsets/${set.id}`} className="list-main">
                    <span className="list-title">{set.name}</span>
                    <span className="muted list-sub">
                      {set.documentCount} document{set.documentCount === 1 ? '' : 's'}
                      {set.description ? ` · ${set.description}` : ''}
                    </span>
                  </Link>
                  <StatusBadge status={set.status} />
                  <button
                    className="btn btn-ghost"
                    type="button"
                    onClick={() => startEdit(set)}
                  >
                    Edit
                  </button>
                  <button
                    className="icon-btn"
                    type="button"
                    aria-label={`Delete set ${set.name}`}
                    title="Delete set"
                    onClick={() => handleDelete(set.id)}
                  >
                    <TrashIcon />
                  </button>
                </li>
              ),
            )}
          </ul>
        )}
      </section>
    </>
  )
}