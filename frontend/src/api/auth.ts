import request from './client'
import type { AuthUser, HealthStatus } from './types'

export interface AuthResponse {
  token: string
  user: AuthUser
}

export async function fetchHealth(): Promise<HealthStatus> {
  const res = await fetch('/api/health')
  if (!res.ok) {
    throw new Error(`Health check failed: ${res.status} ${res.statusText}`)
  }
  return res.json()
}

export async function me(): Promise<AuthUser> {
  return request<AuthUser>('/auth/me')
}

export async function register(input: {
  email: string
  password: string
  displayName: string
}): Promise<AuthResponse> {
  return request<AuthResponse>('/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function login(input: {
  email: string
  password: string
}): Promise<AuthResponse> {
  return request<AuthResponse>('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export async function logout(): Promise<void> {
  return request<void>('/auth/logout', { method: 'POST' })
}