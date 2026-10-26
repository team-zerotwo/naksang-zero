import type { AddressInput, DeviceStatus } from "./types.js";

const MEDIA_KEYS = ["video", "image", "frame", "snapshot", "media", "photo", "clip"];

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    public field?: string,
  ) {
    super(code);
  }
}

export function rejectMedia(body: unknown): void {
  if (!body || typeof body !== "object") return;
  for (const key of Object.keys(body as Record<string, unknown>)) {
    if (MEDIA_KEYS.includes(key.toLowerCase())) {
      throw new HttpError(400, "media_not_allowed", key);
    }
  }
}

function asRecord(body: unknown): Record<string, unknown> {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new HttpError(400, "validation");
  }
  return body as Record<string, unknown>;
}

function requiredText(body: Record<string, unknown>, field: string, max = 200): string {
  const value = body[field];
  if (typeof value !== "string" || value.trim() === "" || value.trim().length > max) {
    throw new HttpError(400, "validation", field);
  }
  return value.trim();
}

function optionalText(body: Record<string, unknown>, field: string, max = 200): string | null {
  if (body[field] === undefined || body[field] === null || body[field] === "") return null;
  if (typeof body[field] !== "string" || (body[field] as string).trim().length > max) {
    throw new HttpError(400, "validation", field);
  }
  return (body[field] as string).trim();
}

export function parseAddress(body: unknown): AddressInput {
  rejectMedia(body);
  const record = asRecord(body);
  return {
    address: requiredText(record, "address"),
    detail: requiredText(record, "detail"),
    entryMethod: optionalText(record, "entryMethod"),
    roomNote: optionalText(record, "roomNote"),
  };
}

export function parseHeartbeat(body: unknown): { battery: number; status: DeviceStatus } {
  rejectMedia(body);
  const record = asRecord(body);
  const battery = record.battery;
  if (typeof battery !== "number" || !Number.isInteger(battery) || battery < 0 || battery > 100) {
    throw new HttpError(400, "validation", "battery");
  }
  const status = record.status;
  if (status !== "watching" && status !== "countdown" && status !== "idle") {
    throw new HttpError(400, "validation", "status");
  }
  return { battery, status };
}

export function parseDetectedAt(value: unknown): string {
  if (typeof value !== "string" || !/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
    throw new HttpError(400, "validation", "detectedAt");
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new HttpError(400, "validation", "detectedAt");
  return date.toISOString();
}

export function parseFall(body: unknown): { detectedAt: string; outcome: "timeout" | "cancelled"; isTest: boolean } {
  rejectMedia(body);
  const record = asRecord(body);
  const detectedAt = parseDetectedAt(record.detectedAt);
  if (record.outcome !== "timeout" && record.outcome !== "cancelled") {
    throw new HttpError(400, "validation", "outcome");
  }
  if (record.isTest !== undefined && typeof record.isTest !== "boolean") {
    throw new HttpError(400, "validation", "isTest");
  }
  return { detectedAt, outcome: record.outcome, isTest: record.isTest === true };
}

export function parseInviteCode(body: unknown): string {
  rejectMedia(body);
  const record = asRecord(body);
  const raw = record.inviteCode;
  if (typeof raw === "number" && Number.isInteger(raw) && raw >= 0 && raw <= 999999) {
    return String(raw).padStart(6, "0");
  }
  if (typeof raw === "string" && /^\d{6}$/.test(raw)) return raw;
  throw new HttpError(400, "validation", "inviteCode");
}

export function parseSubscription(body: unknown): { endpoint: string; p256dh: string; auth: string } {
  rejectMedia(body);
  const record = asRecord(body);
  const subscription = record.subscription;
  if (!subscription || typeof subscription !== "object") throw new HttpError(400, "validation", "subscription");
  const sub = subscription as Record<string, unknown>;
  const keys = sub.keys;
  if (typeof sub.endpoint !== "string" || !sub.endpoint.startsWith("https://")) {
    throw new HttpError(400, "validation", "subscription");
  }
  if (!keys || typeof keys !== "object") throw new HttpError(400, "validation", "subscription");
  const keyRecord = keys as Record<string, unknown>;
  if (typeof keyRecord.p256dh !== "string" || typeof keyRecord.auth !== "string" || !keyRecord.p256dh || !keyRecord.auth) {
    throw new HttpError(400, "validation", "subscription");
  }
  return { endpoint: sub.endpoint, p256dh: keyRecord.p256dh, auth: keyRecord.auth };
}
