import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import fastifyStatic from "@fastify/static";
import { Store, openDatabase } from "./db.js";
import { isGoneSubscription } from "./push.js";
import type { Device, FallEvent, Guardian, PushPayload, PushSender } from "./types.js";
import { HttpError, parseAddress, parseFall, parseHeartbeat, parseInviteCode, parseSubscription } from "./validate.js";

export type BuildOptions = {
  databasePath: string;
  enableDevEndpoints: boolean;
  staticDir?: string;
  vapidPublicKey?: string;
  push?: PushSender | null;
  logger?: boolean;
};

function nowIso(): string {
  return new Date().toISOString();
}

function newId(): string {
  return crypto.randomUUID();
}

function newToken(): string {
  return crypto.randomBytes(32).toString("base64url");
}

function publicEvent(event: FallEvent) {
  return {
    id: event.id,
    type: event.type,
    detectedAt: event.detectedAt,
    respondedAt: event.respondedAt,
  };
}

function publicAddress(device: Device) {
  return {
    address: device.address,
    detail: device.detail,
    entryMethod: device.entryMethod,
    roomNote: device.roomNote,
    addressConfirmedAt: device.addressConfirmedAt,
  };
}

async function notify(
  store: Store,
  sender: PushSender | null,
  guardianIds: string[],
  payload: PushPayload,
): Promise<number> {
  if (!sender) return 0;
  let sent = 0;
  for (const guardianId of guardianIds) {
    for (const sub of store.listSubscriptions(guardianId)) {
      const subscription = { endpoint: sub.endpoint, keys: { p256dh: sub.p256dh, auth: sub.auth } };
      try {
        await sender.send(subscription, payload);
        sent += 1;
      } catch (error) {
        if (isGoneSubscription(error)) store.deleteSubscription(sub.endpoint);
      }
    }
  }
  return sent;
}

export async function buildApp(opts: BuildOptions): Promise<FastifyInstance> {
  const app = Fastify({ logger: opts.logger ?? false });
  const store = new Store(openDatabase(opts.databasePath));
  const sender = opts.push ?? null;
  app.addHook("onClose", async () => {
    store.close();
  });

  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.status).send(error.field ? { error: error.code, field: error.field } : { error: error.code });
    }
    if ((error as { statusCode?: number }).statusCode === 400) {
      return reply.code(400).send({ error: "validation" });
    }
    app.log.error(error);
    return reply.code(500).send({ error: "internal" });
  });

  function deviceFrom(request: FastifyRequest): Device {
    const token = request.headers["x-device-token"];
    if (typeof token !== "string" || !token) throw new HttpError(401, "unauthorized");
    const device = store.getDeviceByToken(token);
    if (!device) throw new HttpError(401, "unauthorized");
    return device;
  }

  function guardianFrom(request: FastifyRequest): { guardian: Guardian; device: Device } {
    const token = request.headers["x-guardian-token"];
    if (typeof token !== "string" || !token) throw new HttpError(401, "unauthorized");
    const guardian = store.getGuardianByToken(token);
    if (!guardian) throw new HttpError(401, "unauthorized");
    const device = store.getDeviceById(guardian.deviceId);
    if (!device) throw new HttpError(401, "unauthorized");
    return { guardian, device };
  }

  async function devOnly(_request: FastifyRequest, reply: FastifyReply) {
    if (!opts.enableDevEndpoints) return reply.code(404).send({ error: "not_found" });
  }

  app.get("/api/health", async () => ({ ok: true }));

  app.get("/api/vapid-public-key", async (_request, reply) => {
    if (!opts.vapidPublicKey) return reply.code(503).send({ error: "vapid_not_configured" });
    return { key: opts.vapidPublicKey };
  });

  app.post("/api/devices", async (request) => {
    const address = parseAddress(request.body);
    let inviteCode = "";
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const candidate = crypto.randomInt(0, 1_000_000).toString().padStart(6, "0");
      if (!store.inviteTaken(candidate)) {
        inviteCode = candidate;
        break;
      }
    }
    if (!inviteCode) throw new HttpError(500, "internal");
    const device = store.createDevice({
      ...address,
      id: newId(),
      token: newToken(),
      inviteCode,
      createdAt: nowIso(),
    });
    return { deviceId: device.id, inviteCode: device.inviteCode, deviceToken: device.token };
  });

  app.put("/api/devices/me/address", async (request) => {
    const device = deviceFrom(request);
    const address = parseAddress(request.body);
    const updated = store.updateAddress(device.id, address, null);
    return publicAddress(updated);
  });

  app.post("/api/devices/me/heartbeat", async (request, reply) => {
    const device = deviceFrom(request);
    const beat = parseHeartbeat(request.body);
    store.heartbeat(device.id, beat.battery, beat.status, nowIso());
    return reply.code(200).send({ ok: true });
  });

  app.post("/api/events/fall", async (request) => {
    const device = deviceFrom(request);
    const fall = parseFall(request.body);
    const event = store.createEvent({
      id: newId(),
      deviceId: device.id,
      type: fall.outcome === "timeout" ? "fall_alert" : "self_cancelled",
      detectedAt: fall.detectedAt,
      isTest: fall.isTest,
      createdAt: nowIso(),
    });
    if (fall.outcome === "timeout") {
      const guardians = store.listGuardians(device.id).map((guardian) => guardian.id);
      await notify(store, sender, guardians, {
        type: "fall",
        eventId: event.id,
        detectedAt: event.detectedAt,
        title: "낙상 알림",
        body: "넘어짐이 감지됐어요",
        url: `/alert/${event.id}`,
      });
    }
    return { eventId: event.id };
  });

  app.post("/api/pair", async (request, reply) => {
    const inviteCode = parseInviteCode(request.body);
    const device = store.getDeviceByInvite(inviteCode);
    if (!device) return reply.code(404).send({ error: "invite_not_found" });
    const guardian = store.createGuardian({
      id: newId(),
      token: newToken(),
      deviceId: device.id,
      createdAt: nowIso(),
    });
    return { guardianToken: guardian.token, deviceId: device.id };
  });

  app.post("/api/push/subscribe", async (request) => {
    const { guardian } = guardianFrom(request);
    const subscription = parseSubscription(request.body);
    store.upsertSubscription({
      id: newId(),
      guardianId: guardian.id,
      endpoint: subscription.endpoint,
      p256dh: subscription.p256dh,
      auth: subscription.auth,
      createdAt: nowIso(),
    });
    return { ok: true };
  });

  app.get("/api/guardian/home", async (request) => {
    const { device } = guardianFrom(request);
    const latest = store.latestEvent(device.id);
    return {
      device: {
        status: device.status,
        battery: device.battery,
        lastSeenAt: device.lastSeenAt,
        addressConfirmedAt: device.addressConfirmedAt,
      },
      latestEvent: latest ? publicEvent(latest) : null,
    };
  });

  app.get("/api/guardian/events", async (request) => {
    const { device } = guardianFrom(request);
    return store.listEvents(device.id).map(publicEvent);
  });

  app.get("/api/guardian/events/:id", async (request, reply) => {
    const { device } = guardianFrom(request);
    const { id } = request.params as { id: string };
    const event = store.getEvent(id);
    if (!event || event.deviceId !== device.id) return reply.code(404).send({ error: "not_found" });
    return {
      ...publicEvent(event),
      isTest: event.isTest,
      address: device.address,
      detail: device.detail,
      entryMethod: device.entryMethod,
      roomNote: device.roomNote,
    };
  });

  app.post("/api/guardian/events/:id/ack", async (request, reply) => {
    const { guardian, device } = guardianFrom(request);
    const { id } = request.params as { id: string };
    const event = store.getEvent(id);
    if (!event || event.deviceId !== device.id) return reply.code(404).send({ error: "not_found" });
    if (event.type === "self_cancelled") return reply.code(409).send({ error: "not_alert" });
    if (event.type === "responded") return publicEvent(event);
    const respondedAt = nowIso();
    const updated = store.markResponded(event.id, respondedAt);
    const others = store
      .listGuardians(device.id)
      .filter((item) => item.id !== guardian.id)
      .map((item) => item.id);
    await notify(store, sender, others, {
      type: "responded",
      eventId: updated.id,
      respondedAt,
      title: "보호자가 가고 있어요",
      body: "다른 보호자가 가고 있다고 했어요",
      url: `/alert/${updated.id}`,
    });
    return publicEvent(updated);
  });

  app.get("/api/guardian/address", async (request) => {
    const { device } = guardianFrom(request);
    return publicAddress(device);
  });

  app.post("/api/guardian/address/confirm", async (request) => {
    const { device } = guardianFrom(request);
    return publicAddress(store.confirmAddress(device.id, nowIso()));
  });

  app.put("/api/guardian/address", async (request) => {
    const { device } = guardianFrom(request);
    const address = parseAddress(request.body);
    return publicAddress(store.updateAddress(device.id, address, nowIso()));
  });

  app.post("/api/dev/trigger-reconfirm", { preHandler: devOnly }, async () => {
    const guardians = store.listAllGuardians().map((guardian) => guardian.id);
    const sent = await notify(store, sender, guardians, {
      type: "reconfirm",
      title: "주소 재확인",
      body: "주소가 그대로인지 확인해 주세요",
      url: "/reconfirm",
    });
    return { ok: true, sent };
  });

  app.post("/api/dev/reset", { preHandler: devOnly }, async () => {
    store.reset();
    return { ok: true };
  });

  const staticDir = opts.staticDir;
  if (staticDir && fs.existsSync(path.join(staticDir, "index.html"))) {
    await app.register(fastifyStatic, { root: staticDir, prefix: "/" });
    app.setNotFoundHandler((request, reply) => {
      if (request.method === "GET" && !request.url.startsWith("/api")) {
        return reply.sendFile("index.html");
      }
      return reply.code(404).send({ error: "not_found" });
    });
  }

  return app;
}
