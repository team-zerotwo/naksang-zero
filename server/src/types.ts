export type DeviceStatus = "watching" | "countdown" | "idle";

export type EventType = "fall_alert" | "self_cancelled" | "responded";

export type AddressInput = {
  address: string;
  detail: string;
  entryMethod: string | null;
  roomNote: string | null;
};

export type Device = AddressInput & {
  id: string;
  token: string;
  inviteCode: string;
  battery: number | null;
  status: DeviceStatus | null;
  lastSeenAt: string | null;
  addressConfirmedAt: string | null;
  createdAt: string;
};

export type Guardian = {
  id: string;
  token: string;
  deviceId: string;
  createdAt: string;
};

export type PushSubscriptionRow = {
  id: string;
  guardianId: string;
  endpoint: string;
  p256dh: string;
  auth: string;
};

export type FallEvent = {
  id: string;
  deviceId: string;
  type: EventType;
  detectedAt: string;
  respondedAt: string | null;
  isTest: boolean;
  createdAt: string;
};

export type PushPayload = {
  type: "fall" | "reconfirm" | "responded";
  title: string;
  body: string;
  url: string;
  eventId?: string;
  detectedAt?: string;
  respondedAt?: string;
};

export type StoredSubscription = {
  endpoint: string;
  keys: { p256dh: string; auth: string };
};

export interface PushSender {
  send(subscription: StoredSubscription, payload: PushPayload): Promise<void>;
}
