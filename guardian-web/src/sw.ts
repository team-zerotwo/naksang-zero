/// <reference lib="webworker" />

import { precacheAndRoute } from "workbox-precaching";

type PushData = {
  title?: string;
  body?: string;
  url?: string;
  type?: string;
  eventId?: string;
};

declare const self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: Array<{ url: string; revision: string | null }>;
};

precacheAndRoute(self.__WB_MANIFEST);

function targetUrl(data: PushData): string {
  if (data.url) return data.url;
  if (data.type === "reconfirm") return "/reconfirm";
  if (data.eventId) return `/alert/${data.eventId}`;
  return "/home";
}

self.addEventListener("push", (event) => {
  let data: PushData = {};
  try {
    data = event.data?.json() ?? {};
  } catch {
    data = { body: event.data?.text() };
  }
  const title = data.title || (data.type === "reconfirm" ? "주소 재확인" : "낙상 알림");
  const url = targetUrl(data);
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || "",
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      data: { url },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const path = String((event.notification.data as { url?: string } | undefined)?.url || "/home");
  const target = new URL(path, self.location.origin).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin !== self.location.origin) continue;
        if ("navigate" in client) {
          await client.navigate(target);
          return client.focus();
        }
      }
      await self.clients.openWindow(target);
    })(),
  );
});
