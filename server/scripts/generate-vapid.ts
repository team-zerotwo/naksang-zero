import webpush from "web-push";

const keys = webpush.generateVAPIDKeys();
console.log("아래 두 줄을 .env 에 넣으세요. 이 값은 커밋하지 않습니다.");
console.log(`VAPID_PUBLIC_KEY=${keys.publicKey}`);
console.log(`VAPID_PRIVATE_KEY=${keys.privateKey}`);
console.log("VAPID_SUBJECT=mailto:demo@naksang.local");
