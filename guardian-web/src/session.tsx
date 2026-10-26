import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Address } from "./types";

const TOKEN_KEY = "naksang.guardianToken";
const MOCK_KEY = "naksang.mock";
const MOCK_STATE_KEY = "naksang.mockState";

export function captureMockFlag(): void {
  const params = new URLSearchParams(window.location.search);
  if (params.get("mock") === "1") sessionStorage.setItem(MOCK_KEY, "1");
  if (params.get("mock") === "0") sessionStorage.removeItem(MOCK_KEY);
}

export function mockEnabled(): boolean {
  return sessionStorage.getItem(MOCK_KEY) === "1";
}

export const sampleAddress: Address = {
  address: "서울시 ○○구 ○○로 12",
  detail: "101동 502호",
  entryMethod: "#1234",
  roomNote: "안방",
  addressConfirmedAt: "2026-10-01T00:00:00.000Z",
};

type MockState = {
  address: Address;
  confirmedLabel: string;
  nextLabel: string;
  ackedIds: string[];
  notifGranted: boolean;
};

const initialMock: MockState = {
  address: sampleAddress,
  confirmedLabel: "10월 1일",
  nextLabel: "11월 1일",
  ackedIds: [],
  notifGranted: false,
};

type SessionValue = {
  mock: boolean;
  token: string | null;
  setToken: (token: string | null) => void;
  notif: NotificationPermission | "unknown";
  setNotif: (value: NotificationPermission) => void;
  mockState: MockState;
  grantMockNotif: () => void;
  ackMock: (id: string) => void;
  confirmMock: (confirmedLabel: string, nextLabel: string) => void;
  updateMockAddress: (address: Address) => void;
};

const SessionContext = createContext<SessionValue | null>(null);

function readToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

function readMockState(): MockState {
  const raw = sessionStorage.getItem(MOCK_STATE_KEY);
  if (!raw) return initialMock;
  try {
    return { ...initialMock, ...JSON.parse(raw) };
  } catch {
    return initialMock;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const mock = useMemo(() => mockEnabled(), []);
  const [token, setTokenState] = useState<string | null>(() => (mock ? "mock" : readToken()));
  const [notif, setNotif] = useState<NotificationPermission | "unknown">("unknown");
  const [mockState, setMockState] = useState<MockState>(() => (mock ? readMockState() : initialMock));

  useEffect(() => {
    if (!("Notification" in window)) return;
    setNotif(Notification.permission);
  }, []);

  useEffect(() => {
    if (mock) sessionStorage.setItem(MOCK_STATE_KEY, JSON.stringify(mockState));
  }, [mock, mockState]);

  const value = useMemo<SessionValue>(
    () => ({
      mock,
      token,
      setToken: (next) => {
        if (mock) {
          setTokenState(next ? "mock" : null);
          return;
        }
        if (next) localStorage.setItem(TOKEN_KEY, next);
        else localStorage.removeItem(TOKEN_KEY);
        setTokenState(next);
      },
      notif,
      setNotif,
      mockState,
      grantMockNotif: () => setMockState((current) => ({ ...current, notifGranted: true })),
      ackMock: (id) =>
        setMockState((current) => ({
          ...current,
          ackedIds: current.ackedIds.includes(id) ? current.ackedIds : [...current.ackedIds, id],
        })),
      confirmMock: (confirmedLabel, nextLabel) => setMockState((current) => ({ ...current, confirmedLabel, nextLabel })),
      updateMockAddress: (address) => setMockState((current) => ({ ...current, address })),
    }),
    [mock, token, notif, mockState],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("session missing");
  return value;
}
