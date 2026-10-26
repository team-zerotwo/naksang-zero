import fs from "node:fs";
import path from "node:path";
import Database from "better-sqlite3";
import type {
  AddressInput,
  Device,
  DeviceStatus,
  EventType,
  FallEvent,
  Guardian,
  PushSubscriptionRow,
} from "./types.js";

type DeviceRow = {
  id: string;
  token: string;
  invite_code: string;
  address: string;
  detail: string;
  entry_method: string | null;
  room_note: string | null;
  battery: number | null;
  status: DeviceStatus | null;
  last_seen_at: string | null;
  address_confirmed_at: string | null;
  created_at: string;
};

type EventRow = {
  id: string;
  device_id: string;
  type: EventType;
  detected_at: string;
  responded_at: string | null;
  is_test: number;
  created_at: string;
};

function mapDevice(row: DeviceRow): Device {
  return {
    id: row.id,
    token: row.token,
    inviteCode: row.invite_code,
    address: row.address,
    detail: row.detail,
    entryMethod: row.entry_method,
    roomNote: row.room_note,
    battery: row.battery,
    status: row.status,
    lastSeenAt: row.last_seen_at,
    addressConfirmedAt: row.address_confirmed_at,
    createdAt: row.created_at,
  };
}

function mapEvent(row: EventRow): FallEvent {
  return {
    id: row.id,
    deviceId: row.device_id,
    type: row.type,
    detectedAt: row.detected_at,
    respondedAt: row.responded_at,
    isTest: row.is_test === 1,
    createdAt: row.created_at,
  };
}

export function openDatabase(filename: string): Database.Database {
  if (filename !== ":memory:") {
    fs.mkdirSync(path.dirname(path.resolve(filename)), { recursive: true });
  }
  const db = new Database(filename);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      token TEXT NOT NULL UNIQUE,
      invite_code TEXT NOT NULL UNIQUE,
      address TEXT NOT NULL,
      detail TEXT NOT NULL,
      entry_method TEXT,
      room_note TEXT,
      battery INTEGER,
      status TEXT,
      last_seen_at TEXT,
      address_confirmed_at TEXT,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS guardians (
      id TEXT PRIMARY KEY,
      token TEXT NOT NULL UNIQUE,
      device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS push_subscriptions (
      id TEXT PRIMARY KEY,
      guardian_id TEXT NOT NULL REFERENCES guardians(id) ON DELETE CASCADE,
      endpoint TEXT NOT NULL UNIQUE,
      p256dh TEXT NOT NULL,
      auth TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS events (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      detected_at TEXT NOT NULL,
      responded_at TEXT,
      is_test INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_events_device_detected
      ON events (device_id, detected_at DESC);
  `);
  return db;
}

export class Store {
  constructor(private readonly db: Database.Database) {}

  close(): void {
    this.db.close();
  }

  reset(): void {
    this.db.exec(`
      DELETE FROM push_subscriptions;
      DELETE FROM guardians;
      DELETE FROM events;
      DELETE FROM devices;
    `);
  }

  createDevice(input: AddressInput & { id: string; token: string; inviteCode: string; createdAt: string }): Device {
    this.db
      .prepare(
        `INSERT INTO devices (
          id, token, invite_code, address, detail, entry_method, room_note, created_at
        ) VALUES (
          @id, @token, @inviteCode, @address, @detail, @entryMethod, @roomNote, @createdAt
        )`,
      )
      .run(input);
    return this.getDeviceById(input.id)!;
  }

  inviteTaken(code: string): boolean {
    const row = this.db.prepare(`SELECT 1 AS ok FROM devices WHERE invite_code = ?`).get(code);
    return Boolean(row);
  }

  getDeviceByToken(token: string): Device | null {
    const row = this.db.prepare(`SELECT * FROM devices WHERE token = ?`).get(token) as DeviceRow | undefined;
    return row ? mapDevice(row) : null;
  }

  getDeviceById(id: string): Device | null {
    const row = this.db.prepare(`SELECT * FROM devices WHERE id = ?`).get(id) as DeviceRow | undefined;
    return row ? mapDevice(row) : null;
  }

  getDeviceByInvite(code: string): Device | null {
    const row = this.db.prepare(`SELECT * FROM devices WHERE invite_code = ?`).get(code) as DeviceRow | undefined;
    return row ? mapDevice(row) : null;
  }

  updateAddress(deviceId: string, input: AddressInput, confirmedAt: string | null): Device {
    this.db
      .prepare(
        `UPDATE devices
         SET address = @address, detail = @detail, entry_method = @entryMethod, room_note = @roomNote,
             address_confirmed_at = COALESCE(@confirmedAt, address_confirmed_at)
         WHERE id = @deviceId`,
      )
      .run({ ...input, deviceId, confirmedAt });
    return this.getDeviceById(deviceId)!;
  }

  confirmAddress(deviceId: string, confirmedAt: string): Device {
    this.db.prepare(`UPDATE devices SET address_confirmed_at = ? WHERE id = ?`).run(confirmedAt, deviceId);
    return this.getDeviceById(deviceId)!;
  }

  heartbeat(deviceId: string, battery: number, status: DeviceStatus, lastSeenAt: string): void {
    this.db
      .prepare(`UPDATE devices SET battery = ?, status = ?, last_seen_at = ? WHERE id = ?`)
      .run(battery, status, lastSeenAt, deviceId);
  }

  createGuardian(input: { id: string; token: string; deviceId: string; createdAt: string }): Guardian {
    this.db
      .prepare(`INSERT INTO guardians (id, token, device_id, created_at) VALUES (?, ?, ?, ?)`)
      .run(input.id, input.token, input.deviceId, input.createdAt);
    return this.getGuardianByToken(input.token)!;
  }

  getGuardianByToken(token: string): Guardian | null {
    const row = this.db
      .prepare(`SELECT id, token, device_id, created_at FROM guardians WHERE token = ?`)
      .get(token) as { id: string; token: string; device_id: string; created_at: string } | undefined;
    if (!row) return null;
    return { id: row.id, token: row.token, deviceId: row.device_id, createdAt: row.created_at };
  }

  listGuardians(deviceId: string): Guardian[] {
    const rows = this.db
      .prepare(`SELECT id, token, device_id, created_at FROM guardians WHERE device_id = ?`)
      .all(deviceId) as { id: string; token: string; device_id: string; created_at: string }[];
    return rows.map((row) => ({ id: row.id, token: row.token, deviceId: row.device_id, createdAt: row.created_at }));
  }

  listAllGuardians(): Guardian[] {
    const rows = this.db.prepare(`SELECT id, token, device_id, created_at FROM guardians`).all() as {
      id: string;
      token: string;
      device_id: string;
      created_at: string;
    }[];
    return rows.map((row) => ({ id: row.id, token: row.token, deviceId: row.device_id, createdAt: row.created_at }));
  }

  upsertSubscription(input: {
    id: string;
    guardianId: string;
    endpoint: string;
    p256dh: string;
    auth: string;
    createdAt: string;
  }): void {
    this.db
      .prepare(
        `INSERT INTO push_subscriptions (id, guardian_id, endpoint, p256dh, auth, created_at)
         VALUES (@id, @guardianId, @endpoint, @p256dh, @auth, @createdAt)
         ON CONFLICT(endpoint) DO UPDATE SET
           guardian_id = excluded.guardian_id,
           p256dh = excluded.p256dh,
           auth = excluded.auth`,
      )
      .run(input);
  }

  listSubscriptions(guardianId: string): PushSubscriptionRow[] {
    const rows = this.db
      .prepare(`SELECT id, guardian_id, endpoint, p256dh, auth FROM push_subscriptions WHERE guardian_id = ?`)
      .all(guardianId) as { id: string; guardian_id: string; endpoint: string; p256dh: string; auth: string }[];
    return rows.map((row) => ({
      id: row.id,
      guardianId: row.guardian_id,
      endpoint: row.endpoint,
      p256dh: row.p256dh,
      auth: row.auth,
    }));
  }

  deleteSubscription(endpoint: string): void {
    this.db.prepare(`DELETE FROM push_subscriptions WHERE endpoint = ?`).run(endpoint);
  }

  createEvent(input: {
    id: string;
    deviceId: string;
    type: EventType;
    detectedAt: string;
    isTest: boolean;
    createdAt: string;
  }): FallEvent {
    this.db
      .prepare(
        `INSERT INTO events (id, device_id, type, detected_at, responded_at, is_test, created_at)
         VALUES (?, ?, ?, ?, NULL, ?, ?)`,
      )
      .run(input.id, input.deviceId, input.type, input.detectedAt, input.isTest ? 1 : 0, input.createdAt);
    return this.getEvent(input.id)!;
  }

  getEvent(id: string): FallEvent | null {
    const row = this.db.prepare(`SELECT * FROM events WHERE id = ?`).get(id) as EventRow | undefined;
    return row ? mapEvent(row) : null;
  }

  listEvents(deviceId: string): FallEvent[] {
    const rows = this.db
      .prepare(`SELECT * FROM events WHERE device_id = ? ORDER BY detected_at DESC, created_at DESC`)
      .all(deviceId) as EventRow[];
    return rows.map(mapEvent);
  }

  latestEvent(deviceId: string): FallEvent | null {
    const row = this.db
      .prepare(`SELECT * FROM events WHERE device_id = ? ORDER BY detected_at DESC, created_at DESC LIMIT 1`)
      .get(deviceId) as EventRow | undefined;
    return row ? mapEvent(row) : null;
  }

  markResponded(id: string, respondedAt: string): FallEvent {
    this.db.prepare(`UPDATE events SET type = 'responded', responded_at = ? WHERE id = ?`).run(respondedAt, id);
    return this.getEvent(id)!;
  }
}
