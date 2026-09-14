import { Navigate, Route, Routes } from 'react-router-dom'
import AppLayout from './components/AppLayout'
import { RedirectIfAuthed, RequireAuth } from './auth/RequireAuth'
import DashboardPage from './pages/DashboardPage'
import DocumentSetDetailPage from './pages/DocumentSetDetailPage'
import EvalsPage from './pages/EvalsPage'
import GoldenCasesPage from './pages/GoldenCasesPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'

export default function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RedirectIfAuthed>
            <LoginPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        path="/register"
        element={
          <RedirectIfAuthed>
            <RegisterPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/documentsets/:id" element={<DocumentSetDetailPage />} />
        <Route path="/documentsets/:id/evaluation" element={<GoldenCasesPage />} />
        <Route path="/documentsets/:id/reviews" element={<EvalsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}