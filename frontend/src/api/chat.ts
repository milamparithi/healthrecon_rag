import request from './client'
import type { ChatMessage, ChatReply, Conversation } from './types'

export async function listConversations(docSetId: string): Promise<Conversation[]> {
  return request<Conversation[]>(`/documentsets/${docSetId}/conversations`)
}

export async function createConversation(docSetId: string, title?: string): Promise<Conversation> {
  return request<Conversation>(`/documentsets/${docSetId}/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(title ? { title } : {}),
  })
}

export async function getMessages(docSetId: string, conversationId: string): Promise<ChatMessage[]> {
  return request<ChatMessage[]>(
    `/documentsets/${docSetId}/conversations/${conversationId}/messages`,
  )
}

export async function sendChatMessage(
  docSetId: string,
  conversationId: string,
  message: string,
): Promise<ChatReply> {
  return request<ChatReply>(
    `/documentsets/${docSetId}/conversations/${conversationId}/chat`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    },
  )
}

export async function deleteConversation(docSetId: string, conversationId: string): Promise<void> {
  return request<void>(`/documentsets/${docSetId}/conversations/${conversationId}`, {
    method: 'DELETE',
  })
}