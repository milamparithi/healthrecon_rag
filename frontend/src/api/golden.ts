import request from './client'
import type {
  GoldenCase,
  GoldenCaseInput,
  GoldenEvalReport,
} from './types'

export async function listGoldenCases(docSetId: string): Promise<GoldenCase[]> {
  return request<GoldenCase[]>(`/documentsets/${docSetId}/golden`)
}

export async function createGoldenCase(
  docSetId: string,
  input: GoldenCaseInput,
): Promise<GoldenCase> {
  return request<GoldenCase>(`/documentsets/${docSetId}/golden`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function updateGoldenCase(
  docSetId: string,
  caseId: string,
  input: GoldenCaseInput,
): Promise<GoldenCase> {
  return request<GoldenCase>(`/documentsets/${docSetId}/golden/${caseId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function deleteGoldenCase(docSetId: string, caseId: string): Promise<void> {
  return request<void>(`/documentsets/${docSetId}/golden/${caseId}`, {
    method: 'DELETE',
  })
}

export async function runGoldenEvaluation(docSetId: string): Promise<GoldenEvalReport> {
  return request<GoldenEvalReport>(`/documentsets/${docSetId}/golden/run`, {
    method: 'POST',
  })
}