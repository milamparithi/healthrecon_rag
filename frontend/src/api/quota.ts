import request from './client'
import type { Quota } from './types'

export async function getQuota(): Promise<Quota> {
  return request<Quota>('/quota')
}