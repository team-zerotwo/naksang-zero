import webpush from "web-push";
import type { PushPayload, PushSender, StoredSubscription } from "./types.js";

export function createWebPushSender(opts: {
  publicKey: string;
  privateKey: string;
  subject: string;
}): PushSender | null {
  if (!opts.publicKey || !opts.privateKey) return null;
  webpush.setVapidDetails(opts.subject, opts.publicKey, opts.privateKey);
  return {
    async send(subscription: StoredSubscription, payload: PushPayload) {
      const urgency = payload.type === "fall" ? "high" : "normal";
      await webpush.sendNotification(subscription, JSON.stringify(payload), {
        TTL: 60 * 60,
        urgency,
      });
    },
  };
}

export function isGoneSubscription(error: unknown): boolean {
  const status = (error as { statusCode?: number }).statusCode;
  return status === 404 || status === 410;
}
