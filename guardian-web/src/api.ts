import type { Address, EventDetail, EventSummary, HomeResponse } from "./types";

export class ApiError extends Error {
  status: number;
  code: string;
  field?: string;

  constructor(status: number, code: string, field?: string) {
    super(code);
    this.status = status;
    this.code = code;
    this.field = field;
  }
}

const base = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");

export async function api<T>(
  path: string,
  opts: { method?: string; token?: string | null; body?: unknown } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  if (opts.body !== undefined) headers["content-type"] = "application/json";
  if (opts.token) headers["x-guardian-token"] = opts.token;
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      method: opts.method ?? (opts.body !== undefined ? "POST" : "GET"),
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
  } catch {
    throw new ApiError(0, "network");
  }
  const data = (await response.json().catch(() => ({}))) as { error?: string; field?: string };
  if (!response.ok) throw new ApiError(response.status, data.error || "request_failed", data.field);
  return data as T;
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "invite_not_found") return "코드를 찾지 못했어요. 여섯 자리를 다시 봐 주세요.";
    if (error.code === "unauthorized") return "연결이 만료됐어요. 코드를 다시 입력해 주세요.";
    if (error.code === "network") return "서버에 닿지 못했어요.";
    if (error.code === "vapid_not_configured") return "서버에 알림 키가 아직 없어요.";
    if (error.field === "detail") return "동·호수를 적어 주세요.";
    if (error.field === "address") return "도로명 주소를 적어 주세요.";
    if (error.code === "not_found") return "알림을 찾지 못했어요.";
  }
  return "잠시 뒤 다시 해 주세요.";
}

function urlBase64ToUint8Array(value: string): Uint8Array {
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  const base64 = (value + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  return Uint8Array.from([...raw].map((char) => char.charCodeAt(0)));
}

export async function enablePush(token: string): Promise<void> {
  if (!("Notification" in window) || !("serviceWorker" in navigator) || !("PushManager" in window)) {
    throw new Error("unsupported");
  }
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error("denied");
  const { key } = await api<{ key: string }>("/api/vapid-public-key");
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(key) as BufferSource,
  });
  await api("/api/push/subscribe", { token, method: "POST", body: { subscription: subscription.toJSON() } });
}

export const guardianApi = {
  pair: (inviteCode: string) => api<{ guardianToken: string; deviceId: string }>("/api/pair", { body: { inviteCode } }),
  home: (token: string) => api<HomeResponse>("/api/guardian/home", { token }),
  events: (token: string) => api<EventSummary[]>("/api/guardian/events", { token }),
  event: (token: string, id: string) => api<EventDetail>(`/api/guardian/events/${id}`, { token }),
  ack: (token: string, id: string) => api<EventSummary>(`/api/guardian/events/${id}/ack`, { token, method: "POST", body: {} }),
  address: (token: string) => api<Address>("/api/guardian/address", { token }),
  confirmAddress: (token: string) => api<Address>("/api/guardian/address/confirm", { token, method: "POST", body: {} }),
  updateAddress: (token: string, body: Omit<Address, "addressConfirmedAt">) =>
    api<Address>("/api/guardian/address", { token, method: "PUT", body }),
  triggerReconfirm: () => api<{ ok: boolean; sent: number }>("/api/dev/trigger-reconfirm", { method: "POST", body: {} }),
};
