import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="layout">
      <header className="topbar">
        <Link to="/" className="brand">
          RAG Generator
        </Link>
        <div className="topbar-right">
          {user && <span className="topbar-user">{user.email}</span>}
          <button className="btn btn-ghost" type="button" onClick={handleLogout}>
            Log out
          </button>
        </div>
      </header>
      <main className="container">
        <Outlet />
      </main>
    </div>
  )
}