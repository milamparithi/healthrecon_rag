import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createConversation,
  deleteConversation,
  getMessages,
  listConversations,
  sendChatMessage,
} from '../api/chat'
import type { ChatMessage, Conversation } from '../api/types'
import ChatPanel from './ChatPanel'

vi.mock('../api/chat', () => ({
  createConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
  sendChatMessage: vi.fn(),
}))

const mockCreate = vi.mocked(createConversation)
const mockDelete = vi.mocked(deleteConversation)
const mockGetMessages = vi.mocked(getMessages)
const mockList = vi.mocked(listConversations)
const mockSend = vi.mocked(sendChatMessage)

function makeConversation(over: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    title: 'New conversation',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

function makeMessage(over: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'msg-1',
    role: 'assistant',
    content: 'Paracetamol is recommended for headaches.',
    sources: [{ docId: 'doc-1', filename: 'headache.md', section: 'Acute Headache', snippet: '…' }],
    createdAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

describe('ChatPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('shows a disabled hint when chat is not enabled', () => {
    render(<ChatPanel documentSetId="set-1" enabled={false} />)

    expect(
      screen.getByText('Chat is available once the document set is processed.'),
    ).toBeInTheDocument()
    expect(mockList).not.toHaveBeenCalled()
  })

  it('creates a conversation and shows empty state when there are none', async () => {
    mockList.mockResolvedValue([])
    mockCreate.mockResolvedValue(makeConversation({ id: 'conv-new' }))

    render(<ChatPanel documentSetId="set-1" enabled={true} />)
    await screen.findByText('No conversations yet.')

    fireEvent.click(screen.getByRole('button', { name: 'New chat' }))

    expect(await screen.findByText('Ask a question about the documents below.')).toBeInTheDocument()
    expect(mockCreate).toHaveBeenCalledWith('set-1')
  })

  it('loads the first conversation and its messages when enabled', async () => {
    mockList.mockResolvedValue([makeConversation()])
    mockGetMessages.mockResolvedValue([
      { ...makeMessage(), id: 'm1', role: 'user', sources: null, content: 'Any tips?' },
      makeMessage({ id: 'm2' }),
    ])

    render(<ChatPanel documentSetId="set-1" enabled={true} />)

    expect(await screen.findByText('Any tips?')).toBeInTheDocument()
    expect(screen.getByText('Paracetamol is recommended for headaches.')).toBeInTheDocument()
    expect(screen.getByText(/headache\.md/)).toBeInTheDocument()
    expect(mockList).toHaveBeenCalledWith('set-1')
    expect(mockGetMessages).toHaveBeenCalledWith('set-1', 'conv-1')
  })

  it('sends a message and reloads the conversation', async () => {
    mockList.mockResolvedValue([makeConversation()])
    mockGetMessages
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { ...makeMessage(), id: 'm1', role: 'user', sources: null, content: 'Any tips?' },
        makeMessage({ id: 'm2' }),
      ])
    mockSend.mockResolvedValue({ conversationId: 'conv-1', messageId: 'm2', answer: 'x', sources: [] })

    render(<ChatPanel documentSetId="set-1" enabled={true} />)
    await screen.findByText('Ask a question about the documents below.')

    fireEvent.change(screen.getByPlaceholderText('Ask about this document set…'), {
      target: { value: 'Any tips?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(mockSend).toHaveBeenCalledWith('set-1', 'conv-1', 'Any tips?'))
    expect(await screen.findByText('Paracetamol is recommended for headaches.')).toBeInTheDocument()
  })

  it('renames the conversation from the first reply title', async () => {
    mockList.mockResolvedValue([makeConversation()])
    mockGetMessages
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { ...makeMessage(), id: 'm1', role: 'user', sources: null, content: 'What dosage?' },
        makeMessage({ id: 'm2' }),
      ])
    mockSend.mockResolvedValue({
      conversationId: 'conv-1',
      messageId: 'm2',
      answer: 'x',
      sources: [],
      title: 'What dosage',
    })

    render(<ChatPanel documentSetId="set-1" enabled={true} />)
    await screen.findByText('Ask a question about the documents below.')

    fireEvent.change(screen.getByPlaceholderText('Ask about this document set…'), {
      target: { value: 'What dosage?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('What dosage')).toBeInTheDocument()
  })

  it('surfaces an error when loading conversations fails', async () => {
    mockList.mockRejectedValue(new Error('boom'))

    render(<ChatPanel documentSetId="set-1" enabled={true} />)

    expect(await screen.findByText('boom')).toBeInTheDocument()
  })

  it('deletes the active conversation and falls back to the next one', async () => {
    mockList.mockResolvedValue([makeConversation({ id: 'conv-1' }), makeConversation({ id: 'conv-2', title: 'Second' })])
    mockGetMessages.mockResolvedValue([])
    mockDelete.mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(<ChatPanel documentSetId="set-1" enabled={true} />)
    await screen.findByText('Ask a question about the documents below.')
    await waitFor(() => expect(mockGetMessages).toHaveBeenCalledWith('set-1', 'conv-1'))

    fireEvent.click(screen.getByRole('button', { name: 'Delete New conversation' }))

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('set-1', 'conv-1'))
    expect(mockGetMessages).toHaveBeenLastCalledWith('set-1', 'conv-2')
    vi.restoreAllMocks()
  })
})