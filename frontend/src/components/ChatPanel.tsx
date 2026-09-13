import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  createConversation,
  deleteConversation,
  getMessages,
  listConversations,
  sendChatMessage,
} from '../api/chat'
import type { ChatMessage, Conversation } from '../api/types'
import TrashIcon from './TrashIcon'

interface ChatPanelProps {
  documentSetId: string
  enabled: boolean
}

export default function ChatPanel({ documentSetId, enabled }: ChatPanelProps) {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[] | null>(null)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    listRef.current?.scrollTo?.({ top: listRef.current.scrollHeight })
  }, [messages])

  const loadConversations = useCallback(async (): Promise<Conversation[]> => {
    const convs = await listConversations(documentSetId)
    setConversations(convs)
    return convs
  }, [documentSetId])

  const loadMessages = useCallback(
    async (conversationId: string) => {
      setLoading(true)
      setError(null)
      try {
        setMessages(await getMessages(documentSetId, conversationId))
        setActiveId(conversationId)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Could not load messages')
      } finally {
        setLoading(false)
      }
    },
    [documentSetId],
  )

  useEffect(() => {
    if (!enabled) return
    let active = true
    void (async () => {
      try {
        const convs = await loadConversations()
        if (!active) return
        if (convs.length > 0) {
          await loadMessages(convs[0].id)
        }
      } catch (err) {
        if (active) {
          setError(err instanceof Error ? err.message : 'Could not load conversations')
        }
      }
    })()
    return () => {
      active = false
    }
  }, [enabled, loadConversations, loadMessages])

  const handleNewChat = async () => {
    setError(null)
    try {
      const conv = await createConversation(documentSetId)
      setConversations((prev) => [conv, ...prev])
      setMessages([])
      setActiveId(conv.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start a conversation')
    }
  }

  const handleDelete = async (conversation: Conversation) => {
    if (!window.confirm(`Delete conversation "${conversation.title}"?`)) return
    setError(null)
    try {
      await deleteConversation(documentSetId, conversation.id)
      const next = conversations.filter((c) => c.id !== conversation.id)
      setConversations(next)
      if (activeId === conversation.id) {
        setActiveId(next.length > 0 ? next[0].id : null)
        if (next.length > 0) {
          await loadMessages(next[0].id)
        } else {
          setMessages([])
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete conversation')
    }
  }

  const handleSend = async (e: FormEvent) => {
    e.preventDefault()
    const text = input.trim()
    if (!text || !activeId || sending) return
    setSending(true)
    setError(null)
    try {
      const reply = await sendChatMessage(documentSetId, activeId, text)
      if (reply.title) {
        setConversations((prev) =>
          prev.map((c) => (c.id === activeId ? { ...c, title: reply.title! } : c)),
        )
      }
      setInput('')
      setMessages(await getMessages(documentSetId, activeId))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not send message')
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="card chat-panel">
      <div className="section-head">
        <h2>Ask the documents</h2>
        {enabled && <span className="muted">grounded in this document set</span>}
      </div>
      {error && <div className="alert alert-error">{error}</div>}

      {!enabled ? (
        <p className="muted">Chat is available once the document set is processed.</p>
      ) : (
        <div className="chat-layout">
          <aside className="chat-sidebar">
            <button
              className="btn btn-primary btn-block"
              type="button"
              onClick={handleNewChat}
            >
              New chat
            </button>
            {conversations.length === 0 ? (
              <p className="muted chat-empty">No conversations yet.</p>
            ) : (
              <ul className="chat-cons">
                {conversations.map((conv) => (
                  <li key={conv.id}>
                    <button
                      className={`btn btn-ghost chat-con${conv.id === activeId ? ' active' : ''}`}
                      type="button"
                      onClick={() => loadMessages(conv.id)}
                    >
                      <span className="chat-con-title">{conv.title}</span>
                      <span className="muted chat-con-sub">
                        {new Date(conv.updatedAt).toLocaleString()}
                      </span>
                    </button>
                    <button
                      className="icon-btn chat-con-delete"
                      type="button"
                      aria-label={`Delete ${conv.title}`}
                      title="Delete conversation"
                      onClick={() => handleDelete(conv)}
                    >
                      <TrashIcon />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </aside>

          <div className="chat-main">
            <div className="chat-messages" ref={listRef}>
              {loading ? (
                <p className="muted">Loading messages…</p>
              ) : messages === null ? (
                <p className="muted">Select a conversation to view messages.</p>
              ) : messages.length === 0 ? (
                <p className="muted">Ask a question about the documents below.</p>
              ) : (
                messages.map((msg) => (
                  <div key={msg.id} className={`chat-msg chat-msg-${msg.role}`}>
                    <p className="chat-msg-content">{msg.content}</p>
                    {msg.role === 'assistant' && msg.sources && msg.sources.length > 0 && (
                      <ul className="chat-sources">
                        {msg.sources.map((src, i) => (
                          <li key={i} className="muted chat-source">
                            {src.filename}
                            {src.section ? ` · ${src.section}` : ''}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                ))
              )}
            </div>
            <form className="form-inline chat-form" onSubmit={handleSend}>
              <input
                className="grow"
                type="text"
                placeholder="Ask about this document set…"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                disabled={sending || activeId === null}
              />
              <button
                className="btn btn-primary"
                type="submit"
                disabled={sending || activeId === null || input.trim().length === 0}
              >
                {sending ? 'Sending…' : 'Send'}
              </button>
            </form>
          </div>
        </div>
      )}
    </section>
  )
}