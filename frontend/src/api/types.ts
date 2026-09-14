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

export type GoldenCaseStatus = 'DRAFT' | 'GOLDEN'

export interface GoldenSource {
  filename: string
  docId: string | null
  section: string | null
}

export interface GoldenCase {
  id: string
  docSetId: string
  sourceDocId: string | null
  question: string
  referenceAnswer: string
  expectedSources: GoldenSource[]
  status: GoldenCaseStatus
  createdAt: string
  updatedAt: string
}

export interface GoldenCaseInput {
  question: string
  answer: string
  expectedSources: GoldenSource[] | null
  status?: GoldenCaseStatus | null
}

export interface CaseEvalResult {
  question: string
  expected: string[]
  retrieved: string[]
  hit: boolean
  rank: number
  recall: number
}

export interface GoldenEvalReport {
  casesEvaluated: number
  recallAtK: number
  mrr: number
  hitRate: number
  cases: CaseEvalResult[]
  warnings: string[]
}

export type ReviewStatus = 'PENDING' | 'REVIEWED' | 'DISMISSED'

export type Verdict = 'ACCEPT' | 'REWORD' | 'REJECT'

export interface AnswerEval {
  id: string
  docSetId: string
  chatMessageId: string | null
  conversationId: string | null
  question: string
  answer: string
  sources: Source[]
  coverageScore: number | null
  autoFlags: string[]
  sampled: boolean
  origin: string
  reviewStatus: ReviewStatus
  verdict: Verdict | null
  rating: number | null
  comment: string | null
  correctedAnswer: string | null
  reviewedAt: string | null
  createdAt: string
}

export interface AnswerEvalPage {
  content: AnswerEval[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface EvalMetrics {
  totalCaptured: number
  flagged: number
  sampled: number
  pending: number
  reviewed: number
  dismissed: number
  accepted: number
  reworded: number
  rejected: number
  averageRating: number | null
}

export interface ReviewInput {
  verdict: Verdict
  rating?: number | null
  comment?: string | null
  correctedAnswer?: string | null
}

export interface ApiErrorBody {
  error: string
  message: string
  timestamp: string
}