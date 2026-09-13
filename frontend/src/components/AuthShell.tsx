import type { FormEvent, ReactNode } from 'react'

interface AuthShellProps {
  title: string
  subtitle: ReactNode
  onSubmit: (e: FormEvent) => void
  error: string | null
  submitLabel: string
  busy: boolean
  children: ReactNode
}

export default function AuthShell({
  title,
  subtitle,
  onSubmit,
  error,
  submitLabel,
  busy,
  children,
}: AuthShellProps) {
  return (
    <div className="auth-wrap">
      <form className="card auth-card" onSubmit={onSubmit}>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        {error && <div className="alert alert-error">{error}</div>}
        {children}
        <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
          {busy ? 'Please wait…' : submitLabel}
        </button>
      </form>
    </div>
  )
}