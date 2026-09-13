import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react'
import { useNavigate } from 'react-router-dom'
import { clearToken, getToken, setToken, UNAUTHORIZED_EVENT } from '../api/client'
import { login as loginApi, logout as logoutApi, me, register as registerApi } from '../api/auth'
import type { AuthUser } from '../api/types'

export interface AuthContextValue {
  user: AuthUser | null
  initializing: boolean
  login: (email: string, password: string) => Promise<void>
  register: (displayName: string, email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [user, setUser] = useState<AuthUser | null>(null)
  const [initializing, setInitializing] = useState(true)

  useEffect(() => {
    let active = true
    if (!getToken()) {
      setInitializing(false)
      return () => {
        active = false
      }
    }
    me()
      .then((u) => {
        if (active) setUser(u)
      })
      .catch(() => {
        // not signed in
      })
      .finally(() => {
        if (active) setInitializing(false)
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    function onUnauthorized() {
      setUser(null)
      navigate('/login', { replace: true })
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [navigate])

  const login = useCallback(async (email: string, password: string) => {
    const res = await loginApi({ email, password })
    setToken(res.token)
    setUser(res.user)
  }, [])

  const register = useCallback(
    async (displayName: string, email: string, password: string) => {
      const res = await registerApi({ displayName, email, password })
      setToken(res.token)
      setUser(res.user)
    },
    [],
  )

  const logout = useCallback(async () => {
    try {
      await logoutApi()
    } finally {
      clearToken()
      setUser(null)
      navigate('/login', { replace: true })
    }
  }, [navigate])

  return (
    <AuthContext.Provider value={{ user, initializing, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return ctx
}