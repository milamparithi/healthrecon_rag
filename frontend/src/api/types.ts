export interface HealthStatus {
  status: string
  service: string
  time?: string
}

export interface AuthUser {
  id: string
  email: string
  displayName: string
  createdAt: string
}

export type DocumentSetStatus = 'EMPTY' | 'UPLOADING' | 'READY' | 'FAILED'

export type DocumentStatus =
  | 'UPLOADED'
  | 'PENDING'
  | 'EXTRACTING'
  | 'READY'
  | 'FAILED'

export interface DocumentSet {
  id: string
  name: string
  description: string | null
  status: DocumentSetStatus
  documentCount: number
  createdAt: string
  updatedAt: string
}

export interface DocumentListItem {
  id: string
  filename: string
  contentType: string
  contentLength: number
  status: DocumentStatus
  error: string | null
  createdAt: string
}

export interface DocumentPage {
  content: DocumentListItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Quota {
  usedBytes: number
  limitBytes: number
}

export interface DocumentDetail extends DocumentListItem {
  extractedText: string | null
  extractedTextLength: number
}

export interface UploadResult {
  docId: string | null
  filename: string
  status: 'UPLOADED' | 'DUPLICATE' | 'FAILED'
  message: string | null
}

export interface Conversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface Source {
  docId: string
  filename: string
  section: string | null
  snippet: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: Source[] | null
  createdAt: string
}

export interface ChatReply {
  conversationId: string
  messageId: string
  answer: string
  sources: Source[]
  title?: string
}

export interface ApiErrorBody {
  error: string
  message: string
  timestamp: string
}