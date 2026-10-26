# guardian-web

낙상제로 보호자 PWA. 시안은 `docs/design/screens/G1~G5.html`과 `docs/design/png/G1~G5.png`다.

- G1 `/` 초대 코드, 알림 허용, 아이폰 홈 화면 안내
- G2 `/home` 폰 상태
- G3 `/alert/:eventId` 낙상 알림. 위험 빨강은 이 화면의 미응답 낙상에만 쓴다
- G4 `/reconfirm` 주소 재확인
- G5 `/history` 기록

설정 탭은 시안에 이름만 있어서, 같은 글자와 색으로 알림·주소·연결 해제만 둔다. 연결 끊김 알림, 「괜찮았어요(오탐)」, 오탐 유형은 없다.

## 로컬 실행

Node.js 22.

```bash
cd guardian-web
npm install
npm run dev     # http://127.0.0.1:5173  API는 127.0.0.1:3000 으로 프록시
npm run build
npm run preview
```

서버 없이 시안 데이터로 보려면 `http://127.0.0.1:5173/?mock=1`. 그 탭에서는 `sessionStorage`에 목 모드가 남아 화면을 오가도 유지된다. 끄려면 `?mock=0`.

알림을 실제로 받으려면 서버의 VAPID 키가 필요하고, 연결 화면에서 「허용」을 누른다. 서비스 워커는 푸시의 `url`로 열고, 없으면 `type`이 `reconfirm`일 때 `/reconfirm`, 그 외에는 `/alert/:eventId`를 연다.

`VITE_API_BASE`를 비우면 같은 출처의 `/api`를 쓴다. 웹과 API를 다른 출처에 나눌 때만 넣는다. 배포는 한 HTTPS 출처를 권한다.

## 폰트

Pretendard는 SIL Open Font License 1.1. Gmarket Sans 파일은 공식 배포본이 아니라 미러에서 받은 것이다. **배포 전 공식 파일로 교체**해야 한다. 공식 안내는 `docs/design/asset-credits.md`와 [G마켓 폰트](https://corp.gmarket.com/fonts)를 본다. 파일 위치는 `public/fonts/`와 `docs/design/fonts/`.

## 설치

manifest와 서비스 워커가 있어 홈 화면에 추가할 수 있다. 아이폰은 추가하기 전에는 웹 푸시가 오지 않는다. 연결 화면에 그 순서를 적어 두었다. 웹 푸시는 HTTPS가 필수다. localhost에서만 HTTP로 시험할 수 있다.

화면을 서버와 한 포트로 서빙하는 방법은 `server/README.md`와 저장소 루트 Dockerfile을 본다.
