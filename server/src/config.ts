import path from "node:path";

export type Config = {
  port: number;
  databasePath: string;
  vapidPublicKey: string;
  vapidPrivateKey: string;
  vapidSubject: string;
  enableDevEndpoints: boolean;
  staticDir: string;
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const port = Number(env.PORT ?? "3000");
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("PORT must be an integer between 1 and 65535");
  }
  return {
    port,
    databasePath: env.DATABASE_PATH ?? "./data/naksang.db",
    vapidPublicKey: env.VAPID_PUBLIC_KEY?.trim() ?? "",
    vapidPrivateKey: env.VAPID_PRIVATE_KEY?.trim() ?? "",
    vapidSubject: env.VAPID_SUBJECT?.trim() || "mailto:demo@naksang.local",
    enableDevEndpoints: env.ENABLE_DEV_ENDPOINTS === "true",
    staticDir: path.resolve(env.STATIC_DIR ?? "../guardian-web/dist"),
  };
}
