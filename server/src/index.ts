import "dotenv/config";
import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { createWebPushSender } from "./push.js";

const config = loadConfig();
const push = createWebPushSender({
  publicKey: config.vapidPublicKey,
  privateKey: config.vapidPrivateKey,
  subject: config.vapidSubject,
});

if (!push) {
  console.warn("VAPID 키가 없어 웹 푸시를 보내지 않습니다. npm run generate-vapid 후 .env 에 넣으세요.");
}

const app = await buildApp({
  databasePath: config.databasePath,
  enableDevEndpoints: config.enableDevEndpoints,
  staticDir: config.staticDir,
  vapidPublicKey: config.vapidPublicKey,
  push,
  logger: true,
});

await app.listen({ port: config.port, host: "0.0.0.0" });
