export type DeviceStatus = "watching" | "countdown" | "idle";

export type EventType = "fall_alert" | "self_cancelled" | "responded";

export type EventSummary = {
  id: string;
  type: EventType;
  detectedAt: string;
  respondedAt: string | null;
};

export type HomeResponse = {
  device: {
    status: DeviceStatus | null;
    battery: number | null;
    lastSeenAt: string | null;
    addressConfirmedAt: string | null;
  };
  latestEvent: EventSummary | null;
};

export type EventDetail = EventSummary & {
  isTest: boolean;
  address: string;
  detail: string;
  entryMethod: string | null;
  roomNote: string | null;
};

export type Address = {
  address: string;
  detail: string;
  entryMethod: string | null;
  roomNote: string | null;
  addressConfirmedAt: string | null;
};
