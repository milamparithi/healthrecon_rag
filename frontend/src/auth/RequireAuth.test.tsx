import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { AuthContextValue } from './AuthContext'
import { useAuth } from './AuthContext'
import { RedirectIfAuthed, RequireAuth } from './RequireAuth'

vi.mock('./AuthContext', () => ({
  useAuth: vi.fn(),
}))

const mockedUseAuth = vi.mocked(useAuth)

function authValue(over: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    user: null,
    initializing: false,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    ...over,
  }
}

function renderAt(path: string, children: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>{children}</Routes>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('shows a loading state while initializing', () => {
    mockedUseAuth.mockReturnValue(authValue({ initializing: true }))

    renderAt(
      '/protected',
      <Route
        path="/protected"
        element={
          <RequireAuth>
            <div>protected-page</div>
          </RequireAuth>
        }
      />,
    )

    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('renders children when authenticated', () => {
    mockedUseAuth.mockReturnValue(
      authValue({ user: { id: 'u1', email: 'a@b.c', displayName: 'Ada', createdAt: 'x' } }),
    )

    renderAt(
      '/protected',
      <Route
        path="/protected"
        element={
          <RequireAuth>
            <div>protected-page</div>
          </RequireAuth>
        }
      />,
    )

    expect(screen.getByText('protected-page')).toBeInTheDocument()
  })

  it('redirects to /login when not authenticated', () => {
    mockedUseAuth.mockReturnValue(authValue())

    renderAt(
      '/protected',
      <>
        <Route path="/protected" element={<RequireAuth><div>protected-page</div></RequireAuth>} />
        <Route path="/login" element={<div>login-page</div>} />
      </>,
    )

    expect(screen.getByText('login-page')).toBeInTheDocument()
  })
})

describe('RedirectIfAuthed', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('redirects to / when an authenticated user visits /login', () => {
    mockedUseAuth.mockReturnValue(
      authValue({ user: { id: 'u1', email: 'a@b.c', displayName: 'Ada', createdAt: 'x' } }),
    )

    renderAt(
      '/login',
      <>
        <Route
          path="/login"
          element={
            <RedirectIfAuthed>
              <div>login-form</div>
            </RedirectIfAuthed>
          }
        />
        <Route path="/" element={<div>home-page</div>} />
      </>,
    )

    expect(screen.getByText('home-page')).toBeInTheDocument()
  })

  it('renders children when not authenticated', () => {
    mockedUseAuth.mockReturnValue(authValue())

    renderAt(
      '/login',
      <Route
        path="/login"
        element={
          <RedirectIfAuthed>
            <div>login-form</div>
          </RedirectIfAuthed>
        }
      />,
    )

    expect(screen.getByText('login-form')).toBeInTheDocument()
  })
})