import type { ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { useSession, SessionProvider } from "./session";
import { ConnectScreen } from "./screens/Connect";
import { HomeScreen } from "./screens/Home";
import { AlertScreen } from "./screens/Alert";
import { ReconfirmScreen } from "./screens/Reconfirm";
import { HistoryScreen } from "./screens/History";
import { SettingsScreen } from "./screens/Settings";

function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useSession();
  if (!token) return <Navigate to="/" replace />;
  return children;
}

function RoutesView() {
  return (
    <div className="app">
      <Routes>
        <Route path="/" element={<ConnectScreen />} />
        <Route path="/home" element={<RequireAuth><HomeScreen /></RequireAuth>} />
        <Route path="/history" element={<RequireAuth><HistoryScreen /></RequireAuth>} />
        <Route path="/settings" element={<RequireAuth><SettingsScreen /></RequireAuth>} />
        <Route path="/alert/:eventId" element={<RequireAuth><AlertScreen /></RequireAuth>} />
        <Route path="/reconfirm" element={<RequireAuth><ReconfirmScreen /></RequireAuth>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <SessionProvider>
        <RoutesView />
      </SessionProvider>
    </BrowserRouter>
  );
}
