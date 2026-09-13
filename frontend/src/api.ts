export interface HealthStatus {
  status: string
  service: string
  time?: string
}

export async function fetchHealth(): Promise<HealthStatus> {
  const res = await fetch('/api/health')
  if (!res.ok) {
    throw new Error(`Health check failed: ${res.status} ${res.statusText}`)
  }
  return res.json()
}