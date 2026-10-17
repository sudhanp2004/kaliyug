import { NavLink, Route, Routes } from 'react-router-dom'
import HomePage from './pages/HomePage'

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
      </Routes>
    </>
  )
}
