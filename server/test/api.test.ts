import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import type { PushPayload, PushSender, StoredSubscription } from "../src/types.js";

const detectedAt = "2026-10-26T06:42:00.000Z";

function addressBody(over: Record<string, unknown> = {}) {
  return {
    address: "서울시 ○○구 ○○로 12",
    detail: "101동 502호",
    entryMethod: "#1234",
    roomNote: "안방",
    ...over,
  };
}

async function registerDevice(app: FastifyInstance, over: Record<string, unknown> = {}) {
  const response = await app.inject({ method: "POST", url: "/api/devices", payload: addressBody(over) });
  assert.equal(response.statusCode, 200);
  return response.json() as { deviceId: string; inviteCode: string; deviceToken: string };
}

test("device registration, pairing, heartbeat, fall outcomes, ack, and address", async () => {
  const sent: { endpoint: string; payload: PushPayload }[] = [];
  const push: PushSender = {
    async send(subscription: StoredSubscription, payload: PushPayload) {
      sent.push({ endpoint: subscription.endpoint, payload });
    },
  };
  const app = await buildApp({
    databasePath: ":memory:",
    enableDevEndpoints: true,
    vapidPublicKey: "test-public-key",
    push,
  });

  const created = await registerDevice(app);
  assert.match(created.inviteCode, /^\d{6}$/);
  assert.ok(created.deviceId);
  assert.ok(created.deviceToken);

  const missing = await app.inject({
    method: "POST",
    url: "/api/devices",
    payload: { address: "서울시 ○○구 ○○로 12" },
  });
  assert.equal(missing.statusCode, 400);
  assert.equal(missing.json().field, "detail");

  const media = await app.inject({
    method: "POST",
    url: "/api/devices",
    payload: { ...addressBody(), video: "nope" },
  });
  assert.equal(media.statusCode, 400);
  assert.equal(media.json().error, "media_not_allowed");

  const unauthAddress = await app.inject({
    method: "PUT",
    url: "/api/devices/me/address",
    payload: addressBody({ detail: "102동 1호" }),
  });
  assert.equal(unauthAddress.statusCode, 401);

  const phoneAddress = await app.inject({
    method: "PUT",
    url: "/api/devices/me/address",
    headers: { "x-device-token": created.deviceToken },
    payload: addressBody({ detail: "102동 1호", entryMethod: null }),
  });
  assert.equal(phoneAddress.statusCode, 200);
  assert.equal(phoneAddress.json().detail, "102동 1호");
  assert.equal(phoneAddress.json().entryMethod, null);
  assert.equal(phoneAddress.json().addressConfirmedAt, null);

  const badBeat = await app.inject({
    method: "POST",
    url: "/api/devices/me/heartbeat",
    headers: { "x-device-token": created.deviceToken },
    payload: { battery: 120, status: "watching" },
  });
  assert.equal(badBeat.statusCode, 400);

  const beat = await app.inject({
    method: "POST",
    url: "/api/devices/me/heartbeat",
    headers: { "x-device-token": created.deviceToken },
    payload: { battery: 40, status: "watching" },
  });
  assert.equal(beat.statusCode, 200);

  const wrongCode = created.inviteCode === "111111" ? "222222" : "111111";
  const badPair = await app.inject({ method: "POST", url: "/api/pair", payload: { inviteCode: wrongCode } });
  assert.equal(badPair.statusCode, 404);
  assert.equal(badPair.json().error, "invite_not_found");

  const pair = await app.inject({ method: "POST", url: "/api/pair", payload: { inviteCode: created.inviteCode } });
  assert.equal(pair.statusCode, 200);
  const guardian = pair.json() as { guardianToken: string; deviceId: string };
  assert.equal(guardian.deviceId, created.deviceId);

  const pairNumeric = await app.inject({
    method: "POST",
    url: "/api/pair",
    payload: { inviteCode: Number(created.inviteCode) },
  });
  assert.equal(pairNumeric.statusCode, 200);
  const second = pairNumeric.json() as { guardianToken: string; deviceId: string };
  assert.notEqual(second.guardianToken, guardian.guardianToken);

  const sub = await app.inject({
    method: "POST",
    url: "/api/push/subscribe",
    headers: { "x-guardian-token": guardian.guardianToken },
    payload: {
      subscription: {
        endpoint: "https://push.example/guardian-a",
        keys: { p256dh: "p256", auth: "auth" },
      },
    },
  });
  assert.equal(sub.statusCode, 200);

  const subB = await app.inject({
    method: "POST",
    url: "/api/push/subscribe",
    headers: { "x-guardian-token": second.guardianToken },
    payload: {
      subscription: {
        endpoint: "https://push.example/guardian-b",
        keys: { p256dh: "p256b", auth: "authb" },
      },
    },
  });
  assert.equal(subB.statusCode, 200);

  const home = await app.inject({
    method: "GET",
    url: "/api/guardian/home",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(home.statusCode, 200);
  const homeBody = home.json();
  assert.equal(homeBody.device.status, "watching");
  assert.equal(homeBody.device.battery, 40);
  assert.ok(homeBody.device.lastSeenAt.endsWith("Z"));
  assert.equal(homeBody.device.addressConfirmedAt, null);
  assert.equal(homeBody.latestEvent, null);

  const cancelled = await app.inject({
    method: "POST",
    url: "/api/events/fall",
    headers: { "x-device-token": created.deviceToken },
    payload: { detectedAt, outcome: "cancelled" },
  });
  assert.equal(cancelled.statusCode, 200);
  assert.equal(sent.length, 0);

  const timeout = await app.inject({
    method: "POST",
    url: "/api/events/fall",
    headers: { "x-device-token": created.deviceToken },
    payload: { detectedAt: "2026-10-26T07:00:00.000Z", outcome: "timeout", isTest: true },
  });
  assert.equal(timeout.statusCode, 200);
  const timeoutId = timeout.json().eventId as string;
  assert.equal(sent.length, 2);
  for (const message of sent) {
    assert.equal(message.payload.type, "fall");
    assert.equal(message.payload.eventId, timeoutId);
    assert.equal(message.payload.detectedAt, "2026-10-26T07:00:00.000Z");
    assert.equal(message.payload.url, `/alert/${timeoutId}`);
  }
  sent.length = 0;

  const events = await app.inject({
    method: "GET",
    url: "/api/guardian/events",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  const list = events.json() as { id: string; type: string; respondedAt: string | null }[];
  assert.deepEqual(
    list.map((item) => item.type),
    ["fall_alert", "self_cancelled"],
  );
  assert.equal(list[0].respondedAt, null);
  assert.equal("falsePositive" in list[0], false);

  const detail = await app.inject({
    method: "GET",
    url: `/api/guardian/events/${timeoutId}`,
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(detail.statusCode, 200);
  assert.equal(detail.json().detail, "102동 1호");
  assert.equal(detail.json().roomNote, "안방");
  assert.equal(detail.json().isTest, true);
  assert.equal(detail.json().address, "서울시 ○○구 ○○로 12");

  const other = await registerDevice(app, { detail: "다른 집" });
  const otherPair = await app.inject({
    method: "POST",
    url: "/api/pair",
    payload: { inviteCode: other.inviteCode },
  });
  const hidden = await app.inject({
    method: "GET",
    url: `/api/guardian/events/${timeoutId}`,
    headers: { "x-guardian-token": otherPair.json().guardianToken },
  });
  assert.equal(hidden.statusCode, 404);

  const ack = await app.inject({
    method: "POST",
    url: `/api/guardian/events/${timeoutId}/ack`,
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(ack.statusCode, 200);
  assert.equal(ack.json().type, "responded");
  assert.ok(ack.json().respondedAt.endsWith("Z"));
  assert.equal(sent.length, 1);
  assert.equal(sent[0].endpoint, "https://push.example/guardian-b");
  assert.equal(sent[0].payload.type, "responded");
  assert.equal(sent[0].payload.eventId, timeoutId);

  const ackAgain = await app.inject({
    method: "POST",
    url: `/api/guardian/events/${timeoutId}/ack`,
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(ackAgain.statusCode, 200);
  assert.equal(sent.length, 1);

  const cancelledDetail = await app.inject({
    method: "GET",
    url: "/api/guardian/events",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  const cancelledId = (cancelledDetail.json() as { id: string; type: string }[]).find(
    (item) => item.type === "self_cancelled",
  )!.id;
  const badAck = await app.inject({
    method: "POST",
    url: `/api/guardian/events/${cancelledId}/ack`,
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(badAck.statusCode, 409);

  const beforeConfirm = await app.inject({
    method: "GET",
    url: "/api/guardian/address",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(beforeConfirm.json().addressConfirmedAt, null);

  const confirm = await app.inject({
    method: "POST",
    url: "/api/guardian/address/confirm",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(confirm.statusCode, 200);
  assert.ok(confirm.json().addressConfirmedAt.endsWith("Z"));

  const edited = await app.inject({
    method: "PUT",
    url: "/api/guardian/address",
    headers: { "x-guardian-token": guardian.guardianToken },
    payload: addressBody({ detail: "103동 3호", roomNote: "거실" }),
  });
  assert.equal(edited.statusCode, 200);
  assert.equal(edited.json().detail, "103동 3호");
  assert.equal(edited.json().roomNote, "거실");
  assert.ok(edited.json().addressConfirmedAt >= confirm.json().addressConfirmedAt);

  const key = await app.inject({ method: "GET", url: "/api/vapid-public-key" });
  assert.deepEqual(key.json(), { key: "test-public-key" });

  const reconfirm = await app.inject({ method: "POST", url: "/api/dev/trigger-reconfirm" });
  assert.equal(reconfirm.statusCode, 200);
  assert.equal(reconfirm.json().sent, 2);
  assert.ok(sent.some((item) => item.payload.type === "reconfirm" && item.payload.url === "/reconfirm"));

  const reset = await app.inject({ method: "POST", url: "/api/dev/reset" });
  assert.equal(reset.statusCode, 200);
  const afterReset = await app.inject({
    method: "GET",
    url: "/api/guardian/home",
    headers: { "x-guardian-token": guardian.guardianToken },
  });
  assert.equal(afterReset.statusCode, 401);

  await app.close();
});

test("dev endpoints stay hidden and expired subscriptions are dropped", async () => {
  const calls: string[] = [];
  const push: PushSender = {
    async send(subscription) {
      calls.push(subscription.endpoint);
      const error = new Error("gone") as Error & { statusCode?: number };
      error.statusCode = 410;
      throw error;
    },
  };
  const disabled = await buildApp({ databasePath: ":memory:", enableDevEndpoints: false, push });
  const hidden = await disabled.inject({ method: "POST", url: "/api/dev/reset" });
  assert.equal(hidden.statusCode, 404);
  const noKey = await disabled.inject({ method: "GET", url: "/api/vapid-public-key" });
  assert.equal(noKey.statusCode, 503);
  await disabled.close();

  const app = await buildApp({ databasePath: ":memory:", enableDevEndpoints: false, push });
  const created = await registerDevice(app);
  const pair = await app.inject({ method: "POST", url: "/api/pair", payload: { inviteCode: created.inviteCode } });
  const token = pair.json().guardianToken as string;
  await app.inject({
    method: "POST",
    url: "/api/push/subscribe",
    headers: { "x-guardian-token": token },
    payload: { subscription: { endpoint: "https://push.example/old", keys: { p256dh: "a", auth: "b" } } },
  });
  await app.inject({
    method: "POST",
    url: "/api/events/fall",
    headers: { "x-device-token": created.deviceToken },
    payload: { detectedAt, outcome: "timeout" },
  });
  await app.inject({
    method: "POST",
    url: "/api/events/fall",
    headers: { "x-device-token": created.deviceToken },
    payload: { detectedAt, outcome: "timeout" },
  });
  assert.deepEqual(calls, ["https://push.example/old"]);
  await app.close();
});

test("spa fallback serves the guardian app and leaves /api alone", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "naksang-static-"));
  fs.writeFileSync(path.join(dir, "index.html"), "<!doctype html><title>낙상제로</title>");
  fs.writeFileSync(path.join(dir, "app.js"), "console.log(1)");
  const app = await buildApp({ databasePath: ":memory:", enableDevEndpoints: false, staticDir: dir });
  const page = await app.inject({ method: "GET", url: "/alert/abc" });
  assert.equal(page.statusCode, 200);
  assert.match(page.body, /낙상제로/);
  const asset = await app.inject({ method: "GET", url: "/app.js" });
  assert.equal(asset.body, "console.log(1)");
  const missingApi = await app.inject({ method: "GET", url: "/api/nope" });
  assert.equal(missingApi.statusCode, 404);
  assert.equal(missingApi.json().error, "not_found");
  await app.close();
  fs.rmSync(dir, { recursive: true, force: true });
});
