import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request from './client'
import {
  createConversation,
  deleteConversation,
  getMessages,
  listConversations,
  sendChatMessage,
} from './chat'

vi.mock('./client', () => ({ default: vi.fn() }))

const mockRequest = vi.mocked(request)

describe('api chat', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists conversations for a document set', async () => {
    mockRequest.mockResolvedValue([])

    await listConversations('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/conversations')
  })

  it('creates a conversation without a title', async () => {
    mockRequest.mockResolvedValue({ id: 'conv-1' })

    await createConversation('set-1')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    })
  })

  it('creates a conversation with a title', async () => {
    mockRequest.mockResolvedValue({ id: 'conv-1' })

    await createConversation('set-1', 'Triage')

    expect(mockRequest).toHaveBeenCalledWith('/documentsets/set-1/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: 'Triage' }),
    })
  })

  it('loads messages for a conversation', async () => {
    mockRequest.mockResolvedValue([])

    await getMessages('set-1', 'conv-1')

    expect(mockRequest).toHaveBeenCalledWith(
      '/documentsets/set-1/conversations/conv-1/messages',
    )
  })

  it('sends a chat message', async () => {
    mockRequest.mockResolvedValue({ conversationId: 'conv-1' })

    await sendChatMessage('set-1', 'conv-1', 'Hello')

    expect(mockRequest).toHaveBeenCalledWith(
      '/documentsets/set-1/conversations/conv-1/chat',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: 'Hello' }),
      },
    )
  })

  it('deletes a conversation', async () => {
    mockRequest.mockResolvedValue(undefined)

    await deleteConversation('set-1', 'conv-1')

    expect(mockRequest).toHaveBeenCalledWith(
      '/documentsets/set-1/conversations/conv-1',
      { method: 'DELETE' },
    )
  })
})