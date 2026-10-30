import { NavLink, Route, Routes } from 'react-router-dom'
import HomePage from './pages/HomePage'
import LessonPage from './pages/LessonPage'
import LibraryPage from './pages/LibraryPage'
import RevisionPage from './pages/RevisionPage'

export default function App() {
  return (
    <>
      <nav className="nav">
        <span className="brand">Kaliyug</span>
        <NavLink to="/" end>
          Home
        </NavLink>
        <NavLink to="/revise">Revise</NavLink>
        <NavLink to="/library">Library</NavLink>
      </nav>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/revise" element={<RevisionPage />} />
        <Route path="/library" element={<LibraryPage />} />
        <Route path="/lesson/:id" element={<LessonPage />} />
      </Routes>
    </>
  )
}
