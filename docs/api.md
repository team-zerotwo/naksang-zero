# 낙상제로 API (11/3 데모)

계약(`uploads/naksang-contract.md`와 같은 내용)의 경로·필드 이름은 바꾸지 않는다. 이 문서는 계약이 비워 둔 동작을 정한 기록이다.

영상·이미지·프레임은 받지 않는다. 본문에 `video`, `image`, `frame`, `snapshot`, `media`, `photo`, `clip` 키가 있으면 `400 {"error":"media_not_allowed"}`.

## 공통

- 폰 인증: `X-Device-Token`
- 보호자 인증: `X-Guardian-Token`
- 시각은 ISO 8601. 오프셋이 있으면 UTC `Z`로 저장·응답한다. 시간대 없는 문자열은 `400`.
- 토큰은 32바이트 `base64url`. 초대 코드는 앞자리 0을 포함한 6자리 문자열이다. JSON 숫자로 오면 0 이상 999999를 6자리로 맞춘다.
- 식별자는 UUID.
- 오류 본문: `{"error":"<code>","field":"<이름>"}`. `field`는 검증 실패에만 있다.

| code | HTTP | 뜻 |
|---|---|---|
| validation | 400 | 필드가 비었거나 형식이 아님 |
| media_not_allowed | 400 | 영상·사진 필드 |
| unauthorized | 401 | 토큰 없음 또는 틀림 |
| invite_not_found | 404 | 초대 코드 없음 |
| not_found | 404 | 이벤트 없음, 또는 개발 API가 꺼짐 |
| not_alert | 409 | 본인이 취소한 이벤트는 응답할 수 없음 |
| vapid_not_configured | 503 | 공개키가 없음 |
| internal | 500 | 서버 오류 |

계약에 없는 응답 필드를 더할 때는 기존 이름을 바꾸지 않는다. 모르는 필드는 무시하면 된다.

## 폰 → 서버

### `POST /api/devices`

본문 `{address, detail, entryMethod?, roomNote?}`. `address`(도로명), `detail`(동·호수)는 필수. 나머지는 없으면 `null`. 각 문자열은 200자 이하.

응답 `{deviceId, inviteCode, deviceToken}`.

초대 코드는 기기마다 하나이고, 기기가 있는 동안 유지된다. 같은 코드로 보호자를 여러 명 연결할 수 있다.

### `PUT /api/devices/me/address`

폰 인증. 본문은 등록과 같다. 응답은 주소 객체. 폰이 고친 주소는 `addressConfirmedAt`을 바꾸지 않는다. 확인 시각은 보호자 동작에서만 갱신한다.

### `POST /api/devices/me/heartbeat`

본문 `{battery, status}`. `battery`는 0–100 정수. `status`는 `watching` | `countdown` | `idle`.

`200 {"ok":true}`. `lastSeenAt`은 서버가 받은 시각이다. 약 60초마다 보낸다.

### `POST /api/events/fall`

본문 `{detectedAt, outcome, isTest?}`. `outcome`은 `timeout` | `cancelled`. `isTest`를 생략하면 false.

- `timeout`: 이벤트를 `fall_alert`로 저장하고, 그 기기에 연결된 모든 보호자에게 웹 푸시. `isTest: true`여도 푸시한다. 폰의 데모 테스트가 보호자에게 닿아야 하기 때문이다.
- `cancelled`: `self_cancelled`로 저장하고 푸시하지 않는다.

응답 `{eventId}`. 푸시 전송이 실패해도 저장은 성공이다. 같은 요청을 다시 보내면 새 이벤트가 생긴다.

## 보호자 웹 → 서버

### `POST /api/pair`

본문 `{inviteCode}` → `{guardianToken, deviceId}`. 호출마다 보호자 행이 새로 생긴다. 이전 보호자는 끊기지 않는다.

### `POST /api/push/subscribe`

보호자 인증. 본문 `{subscription}`은 브라우저 `PushSubscription` JSON (`endpoint`는 `https`, `keys.p256dh`, `keys.auth`). 같은 endpoint는 덮어쓴다. 응답 `{ok:true}`.

### `GET /api/vapid-public-key`

`{key}`. 키가 없으면 503.

### `GET /api/guardian/home`

```json
{
  "device": {
    "status": "watching",
    "battery": 40,
    "lastSeenAt": "2026-10-26T06:40:00.000Z",
    "addressConfirmedAt": null
  },
  "latestEvent": null
}
```

심박 전이면 `status`, `battery`, `lastSeenAt`은 `null`. `latestEvent`는 가장 최근 이벤트 요약이거나 `null`. 요약 필드는 `id`, `type`, `detectedAt`, `respondedAt`.

### `GET /api/guardian/events`

`[{id, type, detectedAt, respondedAt}]`, `detectedAt` 최신순. `type`은 `fall_alert` | `self_cancelled` | `responded`. 오탐 유형은 없다.

### `GET /api/guardian/events/:id`

요약에 더해 `isTest`, `address`, `detail`, `entryMethod`, `roomNote`. 연결된 기기의 보호자에게만. 다른 기기 이벤트는 404. `isTest`는 계약 목록에는 없고 상세에만 있다.

### `POST /api/guardian/events/:id/ack`

「제가 가고 있어요」. `fall_alert`만 `responded`로 바꾸고 `respondedAt`을 서버 시각으로 남긴다. 응답은 이벤트 요약.

이미 `responded`면 200이고 푸시를 다시 보내지 않는다. `self_cancelled`는 409 `not_alert`.

다른 보호자에게만 아래 `responded` 푸시를 보낸다. 누른 사람에게는 보내지 않는다.

### 주소

- `GET /api/guardian/address` → `{address, detail, entryMethod, roomNote, addressConfirmedAt}`
- `POST /api/guardian/address/confirm` → 같은 모양. `addressConfirmedAt`만 지금으로 갱신. 「그대로예요」.
- `PUT /api/guardian/address` → 본문은 폰 주소 수정과 같다. 저장하면서 `addressConfirmedAt`도 지금으로 둔다. 고친 주소가 지금 맞다는 뜻이다.

## 웹 푸시

페이로드는 JSON이다. 계약의 낙상 필드는 그대로 두고, 서비스 워커가 쓰도록 `title`, `body`, `url`을 더한다. `url`이 없으면 워커가 `type`으로 경로를 정한다.

| type | 필수 필드 | url | 눌렀을 때 |
|---|---|---|---|
| fall | type, eventId, detectedAt | `/alert/:eventId` | G3 |
| reconfirm | type | `/reconfirm` | G4 |
| responded | type, eventId, respondedAt | `/alert/:eventId` | G3의 응답 후 상태 |

`responded`와 `reconfirm`은 화면 문구(다른 보호자에게 알림, 한 달 재확인)를 위해 정한 추가 타입이다. 낙상 `fall` 필드는 계약과 같다.

구독이 404/410을 돌려주면 그 구독을 지운다. 낙상 푸시 urgency는 `high`.

## 개발 엔드포인트

`ENABLE_DEV_ENDPOINTS=true` 일 때만 있다. 아니면 404. 인증은 없다. 공개 배포에서는 끈다.

- `POST /api/dev/trigger-reconfirm` → 모든 보호자에게 재확인 푸시. `{ok:true, sent}`
- `POST /api/dev/reset` → 기기·보호자·구독·이벤트 삭제. `{ok:true}`

## 운용

- `GET /api/health` → `{ok:true}`. 계약 밖.
- 정적 파일은 `guardian-web/dist`. `/api`가 아닌 GET은 `index.html`로 넘어간다.
- 연결 끊김 푸시는 만들지 않는다. 홈의 「연결 %」는 서버 값이 아니다. 보호자 웹이 `lastSeenAt`으로 계산한다. 90초 안 99, 5분 안 70, 30분 안 40, 그 외 10, 없음 0.
- 「확인했어요」는 서버를 바꾸지 않고 홈으로 간다. 상태를 바꾸는 것은 「제가 가고 있어요」뿐이다.
- 어르신 전화번호는 계약에 없다. G3의 「어르신께 전화 걸기」는 번호를 걸지 않고, 번호가 없다는 안내만 한다. 119는 `tel:119`로 전화 앱만 연다.
- 기록의 큰 숫자는 최근 90일의 `fall_alert`와 `responded`만 센다. `self_cancelled`는 전체 목록에 「본인이 괜찮다고 하셨어요」로만 나온다.
