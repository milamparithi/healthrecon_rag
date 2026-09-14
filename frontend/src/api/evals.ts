import request from './client'
import type {
  AnswerEval,
  AnswerEvalPage,
  EvalMetrics,
  GoldenCase,
  ReviewInput,
} from './types'

export interface EvalFilters {
  page?: number
  size?: number
  status?: string
  sampled?: boolean
  flagged?: boolean
}

export async function listEvals(docSetId: string, filters: EvalFilters = {}): Promise<AnswerEvalPage> {
  const params = new URLSearchParams()
  if (filters.page !== undefined) params.set('page', String(filters.page))
  if (filters.size !== undefined) params.set('size', String(filters.size))
  if (filters.status) params.set('status', filters.status)
  if (filters.sampled) params.set('sampled', 'true')
  if (filters.flagged) params.set('flagged', 'true')
  const query = params.toString()
  return request<AnswerEvalPage>(
    `/documentsets/${docSetId}/evals${query ? `?${query}` : ''}`,
  )
}

export async function getEval(docSetId: string, evalId: string): Promise<AnswerEval> {
  return request<AnswerEval>(`/documentsets/${docSetId}/evals/${evalId}`)
}

export async function reviewEval(
  docSetId: string,
  evalId: string,
  input: ReviewInput,
): Promise<AnswerEval> {
  return request<AnswerEval>(`/documentsets/${docSetId}/evals/${evalId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function dismissEval(docSetId: string, evalId: string): Promise<void> {
  return request<void>(`/documentsets/${docSetId}/evals/${evalId}/dismiss`, {
    method: 'POST',
  })
}

export async function promoteEval(docSetId: string, evalId: string): Promise<GoldenCase> {
  return request<GoldenCase>(`/documentsets/${docSetId}/evals/${evalId}/promote`, {
    method: 'POST',
  })
}

export async function getEvalMetrics(docSetId: string): Promise<EvalMetrics> {
  return request<EvalMetrics>(`/documentsets/${docSetId}/evals/metrics`)
}