import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setToken, UNAUTHORIZED_EVENT } from '../api/client'
import { login, logout, me, register } from '../api/auth'
import type { AuthUser } from '../api/types'
import { AuthProvider, useAuth } from './AuthContext'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
  register: vi.fn(),
}))

const mockLogin = vi.mocked(login)
const mockLogout = vi.mocked(logout)
const mockMe = vi.mocked(me)
const mockRegister = vi.mocked(register)

const userA: AuthUser = {
  id: 'u1',
  email: 'a@example.com',
  displayName: 'Ada',
  createdAt: '2026-01-01T00:00:00Z',
}

function Harness() {
  const { user, initializing, login, register, logout } = useAuth()
  return (
    <div>
      <span data-testid="user">{user?.email ?? 'none'}</span>
      <span data-testid="init">{initializing ? 'init' : 'ready'}</span>
      <button onClick={() => void login('a@example.com', 'password1')}>signin</button>
      <button onClick={() => void register('Ada', 'a@example.com', 'password1')}>signup</button>
      <button onClick={() => void logout()}>signout</button>
    </div>
  )
}

function renderProvider() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<Harness />} />
          <Route path="/login" element={<div>login-page</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  afterEach(() => {
    cleanup()
  })

  it('restores the session when a token is stored', async () => {
    setToken('tok-stored')
    mockMe.mockResolvedValue(userA)

    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('user').textContent).toBe('a@example.com'),
    )
    expect(mockMe).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('init').textContent).toBe('ready')
  })

  it('skips the me() call and becomes ready when no token exists', async () => {
    renderProvider()

    await waitFor(() => expect(screen.getByTestId('init').textContent).toBe('ready'))
    expect(mockMe).not.toHaveBeenCalled()
    expect(screen.getByTestId('user').textContent).toBe('none')
  })

  it('stores the token and user on login', async () => {
    mockLogin.mockResolvedValue({ token: 'tok-1', user: userA })
    renderProvider()

    fireEvent.click(screen.getByText('signin'))

    await waitFor(() =>
      expect(screen.getByTestId('user').textContent).toBe('a@example.com'),
    )
    expect(mockLogin).toHaveBeenCalledWith({ email: 'a@example.com', password: 'password1' })
    expect(localStorage.getItem('hr_token')).toBe('tok-1')
  })

  it('stores the token and user on register', async () => {
    mockRegister.mockResolvedValue({ token: 'tok-2', user: userA })
    renderProvider()

    fireEvent.click(screen.getByText('signup'))

    await waitFor(() =>
      expect(screen.getByTestId('user').textContent).toBe('a@example.com'),
    )
    expect(mockRegister).toHaveBeenCalledWith({
      displayName: 'Ada',
      email: 'a@example.com',
      password: 'password1',
    })
    expect(localStorage.getItem('hr_token')).toBe('tok-2')
  })

  it('clears the token, user, and navigates to /login on logout', async () => {
    mockLogin.mockResolvedValue({ token: 'tok-1', user: userA })
    mockLogout.mockResolvedValue(undefined)
    renderProvider()

    fireEvent.click(screen.getByText('signin'))
    await waitFor(() =>
      expect(screen.getByTestId('user').textContent).toBe('a@example.com'),
    )

    fireEvent.click(screen.getByText('signout'))

    await waitFor(() => expect(screen.getByText('login-page')).toBeInTheDocument())
    expect(mockLogout).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('hr_token')).toBeNull()
  })

  it('logs the user out and redirects on an auth:unauthorized event', async () => {
    mockLogin.mockResolvedValue({ token: 'tok-1', user: userA })
    renderProvider()

    fireEvent.click(screen.getByText('signin'))
    await waitFor(() =>
      expect(screen.getByTestId('user').textContent).toBe('a@example.com'),
    )

    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))

    await waitFor(() => expect(screen.getByText('login-page')).toBeInTheDocument())
  })
})