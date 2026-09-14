import request from './client'
import type {
  DocumentDetail,
  DocumentListItem,
  DocumentPage,
  DocumentSet,
  UploadResult,
} from './types'

export async function listDocumentSets(): Promise<DocumentSet[]> {
  return request<DocumentSet[]>('/documentsets')
}

export async function createDocumentSet(
  name: string,
  description: string,
): Promise<DocumentSet> {
  return request<DocumentSet>('/documentsets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  })
}

export async function getDocumentSet(id: string): Promise<DocumentSet> {
  return request<DocumentSet>(`/documentsets/${id}`)
}

export async function updateDocumentSet(
  id: string,
  body: { name: string; description: string | null },
): Promise<DocumentSet> {
  return request<DocumentSet>(`/documentsets/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function deleteDocumentSet(id: string): Promise<void> {
  return request<void>(`/documentsets/${id}`, { method: 'DELETE' })
}

export async function listDocuments(
  id: string,
  page = 0,
  size = 20,
): Promise<DocumentPage> {
  return request<DocumentPage>(`/documentsets/${id}/documents?page=${page}&size=${size}`)
}

const MAX_PAGE_SIZE = 100
const MAX_PAGES = 1000

export async function listAllDocuments(id: string): Promise<DocumentListItem[]> {
  const content: DocumentListItem[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages && page < MAX_PAGES) {
    const result = await listDocuments(id, page, MAX_PAGE_SIZE)
    content.push(...result.content)
    totalPages = result.totalPages
    page += 1
  }
  return content
}

export async function uploadDocuments(id: string, files: File[]): Promise<UploadResult[]> {
  const form = new FormData()
  for (const file of files) {
    form.append('files', file)
  }
  return request<UploadResult[]>(`/documentsets/${id}/documents`, {
    method: 'POST',
    body: form,
  })
}

export async function deleteAllDocuments(id: string): Promise<void> {
  return request<void>(`/documentsets/${id}/documents`, { method: 'DELETE' })
}

export async function getDocument(id: string, docId: string): Promise<DocumentDetail> {
  return request<DocumentDetail>(`/documentsets/${id}/documents/${docId}`)
}

export async function deleteDocument(id: string, docId: string): Promise<void> {
  return request<void>(`/documentsets/${id}/documents/${docId}`, { method: 'DELETE' })
}