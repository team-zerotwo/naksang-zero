# server

낙상제로 API. 폰 등록, 보호자 연결, 심박, 낙상 이벤트, 웹 푸시. 영상은 받지 않는다. 계약과 정한 동작은 [docs/api.md](../docs/api.md).

## 로컬 실행

Node.js 22.

```bash
cd server
npm install
npm test
npm run generate-vapid   # 나온 값을 .env 에 붙여 넣기. 키는 커밋하지 않음
cp .env.example .env
npm run dev              # http://127.0.0.1:3000
```

보호자 웹을 같이 쓰려면 `guardian-web`에서 `npm run build` 한 뒤 서버를 띄운다. `STATIC_DIR`의 `index.html`이 있으면 그 빌드를 같이 서빙하고, `/home` 같은 경로는 `index.html`로 넘긴다.

개발 중 웹만 따로 보려면 `guardian-web`의 `npm run dev`가 `/api`를 이 서버로 프록시한다.

## 환경 변수

| 이름 | 기본 | 설명 |
|---|---|---|
| PORT | 3000 | 수신 포트 |
| DATABASE_PATH | ./data/naksang.db | SQLite 파일 |
| VAPID_PUBLIC_KEY | 비움 | 웹 푸시 공개키 |
| VAPID_PRIVATE_KEY | 비움 | 웹 푸시 비밀키. 커밋 금지 |
| VAPID_SUBJECT | mailto:demo@naksang.local | VAPID 연락처 |
| ENABLE_DEV_ENDPOINTS | false | `true`일 때만 `/api/dev/*` |
| STATIC_DIR | ../guardian-web/dist | 보호자 웹 빌드 |

키가 없어도 API는 뜬다. 그때는 푸시를 보내지 않고 `GET /api/vapid-public-key`가 503이다.

## 테스트

`npm test`. 기기 등록, 페어링, 심박, 낙상 `timeout`/`cancelled`, 응답(ack), 주소 확인, 다른 기기 격리, 만료된 푸시 구독 삭제, SPA 폴백. 푸시 전송은 모킹한다.

## HTTPS로 올리기

웹 푸시는 HTTPS(또는 localhost)에서만 동작한다. 보호자 웹과 API는 한 출처여야 서비스 워커가 알림 클릭으로 `/alert/:eventId`를 열 수 있다.

기존 `soominn.duckdns.org`에는 저장소 루트의 Dockerfile로 올릴 수 있다. Podman과 Docker 둘 다 된다.

```bash
# 서버 .env 에 VAPID 키와 ENABLE_DEV_ENDPOINTS=false
podman build -t naksang .
podman run --rm -p 8080:8080 --env-file server/.env -v naksang-data:/data naksang
```

앞단에 Caddy나 nginx가 `https://soominn.duckdns.org`를 컨테이너 8080으로 넘기면 된다. `PORT`는 이미지 안에서 8080이다. 리버스 프록시가 경로를 잘라 `/api`를 다른 곳으로 보내면 PWA와 API 출처가 갈라지니, 이 서비스는 한 호스트 전체를 받는 편이 맞다.

데모가 끝나면 `ENABLE_DEV_ENDPOINTS`는 끄고, VAPID 비밀키는 저장소 밖에만 둔다.
