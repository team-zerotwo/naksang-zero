# 낙상제로 안드로이드 (11/3 데모)

공기계에 설치하는 감지 앱입니다. 집에 고정해 둔 폰이 카메라로 자세를 보고, 넘어졌다고 판단하면 30초 동안 「괜찮아요」를 기다립니다. 누르지 않으면 서버에 낙상 이벤트를 보내고, 서버가 보호자 웹으로 푸시를 보냅니다.

영상은 저장하지 않고 전송하지도 않습니다. 포즈 추정과 판정은 기기 안에서만 합니다.

## 빌드

필요: JDK 17, Android SDK 34 (compileSdk 34, minSdk 26).

```bash
cd android
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

서버 주소를 빌드에 박으려면:

```bash
./gradlew assembleDebug -PSERVER_URL=https://soominn.duckdns.org
```

서버 없이 화면만 보려면 `-PDEMO_OFFLINE=true`. 설치 후에도 설정에서 끌 수 있습니다.

MediaPipe Pose Landmarker lite 모델(`pose_landmarker_lite.task`, 약 5.8MB)은 `app/src/main/assets/`에 있습니다. 파일이 없거나 해시가 다르면 빌드가 아래 주소에서 다시 받습니다.

`https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`

SHA-256: `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`

full 모델(`pose_landmarker_full.task`, 약 9.4MB)도 같은 방식으로 받을 수 있습니다.

`https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task`

지금은 lite를 유지합니다. 분석은 CPU이고 해상도를 640×480으로 올렸기 때문에, 더 무거운 네트워크는 발열만 키우고 정확도가 나아지는지는 이 환경에서 확인할 수 없습니다. 인식이 여전히 불안정하면 위 주소의 해시를 `app/build.gradle.kts`의 `downloadPoseModel`에 넣고 `PoseReader.MODEL`을 바꾸면 됩니다.

유닛 테스트:

```bash
./gradlew testDebugUnitTest
```

P1–P5를 390×844로 그려 보려면 `-Proborazzi.test.record=true`를 붙입니다. 이미지는 `app/build/outputs/roborazzi/`에 생기고, 이 옵션이 없으면 그 테스트는 빠집니다.

## 설치

USB 디버깅을 켠 공기계에서:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions(`.github/workflows/android-debug.yml`)가 PR과 `main` 푸시마다 디버그 APK를 만들어 artifact `naksang-debug-apk`로 올립니다. Actions 탭에서 APK를 받아 공기계에 넣으면 됩니다.

## 서버 주소

기본값은 `https://soominn.duckdns.org` 입니다.

- 빌드 기본값: `-PSERVER_URL=...` 또는 `app/build.gradle.kts`의 `SERVER_BASE_URL`
- 설치 후: 연결 화면의 「설정」, 또는 대기 화면의 「설정」에서 주소를 바꿉니다. `https://`를 붙이지 않으면 https로 둡니다. 경로(`/api`)는 붙이지 않습니다.
- 같은 화면의 「오프라인 데모」를 켜면 등록·하트비트·낙상 전송을 건너뛰고 화면 흐름만 갑니다.

폰이 보내는 요청은 계약 그대로입니다.

- `POST /api/devices` `{address, detail, entryMethod?, roomNote?}` → `{deviceId, inviteCode, deviceToken}`
- `PUT /api/devices/me/address` 헤더 `X-Device-Token`
- `POST /api/devices/me/heartbeat` `{battery, status}` 60초마다. status는 `watching` / `countdown` / `idle`
- `POST /api/events/fall` `{detectedAt, outcome: "timeout"|"cancelled", isTest?}`

낙상 전송이 네트워크에서 실패하면 최대 8건을 기기에 남겨 5초·15초·45초 간격으로 다시 보냅니다. 하트비트 실패는 다음 주기에 다시 시도합니다.

## 감지 기준값

11/3 데모는 학습 모델 없이 규칙입니다. MediaPipe 관절(어깨 11·12, 엉덩이 23·24)로 다음이 맞을 때만 낙상으로 봅니다.

1. 어깨·엉덩이 중심이 짧은 시간에 크게 내려감 (화면 좌표에서 y가 커짐). 시작 자세는 서 있어야 합니다.
2. 그다음 몸통이 수평에 가깝거나, 카메라 시선 방향으로 누워 몸통이 세로로 남았는데도 중심이 800ms 안에 0.18 이상 내려감.
3. 그 자세로 2000ms 거의 움직이지 않음. 시선 방향 낙상은 움직임 허용이 0.035로 더 좁습니다.

한 프레임에서 네 관절이 다 보일 필요는 없습니다. 왼쪽·오른쪽 중 보이는 어깨를 평균하고, 보이는 엉덩이를 평균합니다. 어깨 하나와 엉덩이 하나가 최소입니다. 방금까지 보이던 관절은 약 1.2초 동안 마지막 위치를 유지해서, 넘어지는 끝에 몸이 반만 보여도 판정이 끊기지 않습니다. 그보다 오래 사람이 사라지면 가만히 있는 시간은 다시 셉니다. 관절 좌표는 짧은 지수 평활을 거친 뒤 위 세 규칙에 들어갑니다.

포즈 모델은 `RunningMode.IMAGE`, CPU, 한 사람(`setNumPoses(1)`)입니다. 검출·존재·추적 신뢰도 기본은 0.35입니다 (`PoseReader`의 `MIN_POSE_*`). IMAGE 모드에서는 추적 신뢰도를 프레임 사이에 쓰지 않고, 사람 놓침 유예는 `FallDetector`가 합니다.

기본값(떨어지는 시간·크기·눕는 각도·가만히 있는 시간·움직임·초당 분석은 설정에서 바꿈. 나머지는 `FallConfig` / `FallCameraEngine` / `PoseReader` 상수):

| 항목 | 기본 | 어디를 고치나 |
|---|---|---|
| 떨어지는 시간 | 1600ms | 설정, 또는 `FallConfig.dropWindowMs`. 시선 방향 경로는 800ms |
| 떨어지는 크기 | 0.10 | 설정, 또는 `FallConfig.minDrop`. 시선 방향 경로는 0.18 |
| 눕는 각도 | 45° | 설정. 0°가 수평. 이 값 이하면 누운 것으로 봅니다. 풀림은 이 값 +17° |
| 가만히 있는 시간 | 2000ms | 설정, 또는 `FallConfig.stillDurationMs` |
| 움직임 허용 | 0.050 | 설정. 이보다 크게 움직이면 정지 시간을 다시 잽니다. 시선 방향 경로는 0.035 |
| 초당 분석 | 5 | 설정. 2–10. 배터리·발열을 위해 5 근처를 유지합니다 |
| 분석 해상도 | 640×480 | `FallCameraEngine.ANALYSIS_WIDTH` / `ANALYSIS_HEIGHT`. 기기가 가진 가장 가까운 크기를 씁니다 |
| 관절 가시성 | 0.35 | `FallConfig.minVisibility`. 이 값 미만인 관절은 버리고, 뼈대에서도 숨깁니다 |
| 평활 | 0.55 | `FallConfig.smoothAlpha`. 1이면 원본 좌표. 낮출수록 떨림에 둔합니다 |
| 사람 놓침 유예 | 1200ms | `FallConfig.personLostGraceMs`. 이 안의 빈 프레임은 넘어지는 중 상태를 유지합니다 |
| 포즈 신뢰도 | 0.35 | `PoseReader.MIN_POSE_DETECTION_CONFIDENCE` 등 세 상수 |

프레임은 메모리에서만 디코딩하고 포즈를 읽은 뒤 바로 버립니다. 저장하거나 보내지 않습니다.

앉기·웅크리기는 몸이 내려가도 시선 방향 기준(800ms에 0.18)보다 느리거나 몸통이 수평·세로 어느 쪽 낙상 조건도 아니어서 낙상으로 보지 않습니다. 천천히 눕기는 1600ms 안의 하강이 0.10보다 작습니다. 몸통 길이 비율과 키 대비 높이 비율은 코드에 있지만 기본값은 0입니다. 켜면 무릎 꿇기·스트레칭·눕기가 오경보가 났습니다. 시연 촬영 때 오탐이 나면 눕는 각도를 낮추거나 떨어지는 크기를 키우고, 놓치면 반대로 조절합니다. 관절이 자주 끊기면 가시성이나 포즈 신뢰도를 0.3까지 낮추고, 떨림 때문에 정지 시간이 리셋되면 `smoothAlpha`를 0.4 쪽으로 내립니다. 설정에서 바꾸는 값은 앱을 다시 켜도 남습니다.

숨은 낙상 테스트: 대기 화면의 집 그림을 5초간 길게 누르면 30초 카운트로 갑니다. 이때 서버에는 `isTest: true`가 붙습니다. 화면에는 「테스트」라고 표시됩니다. 버튼은 따로 없습니다.

## 화면

- P1 연결. 주소·동·호수는 필수, 공동현관과 폰을 둔 방(안방·거실·작은방·주방)은 선택. 주소는 보호자에게만 보입니다. 연결되면 6자리 코드를 복사하거나 보낼 수 있습니다.
- P2 카메라 위치. 어깨와 엉덩이가 보이면 「사람이 잘 보여요」와 「사람 인식」, 그리고 카메라 위 뼈대(관절 점과 연결선). 사람이 없으면 뼈대도 사라집니다. 대기 화면에는 미리보기가 없어서 뼈대를 그리지 않습니다.
- P3 대기. 감지 중, 배터리, 서버 연결, 주소 수정, 설정.
- P4 30초. 「괜찮아요」는 `cancelled`(푸시 없음). 시간이 다 되면 `timeout`.
- P5 전송 결과. 아직 서버에 닿지 않았으면 「전송 대기」로 표시하고 이어서 보냅니다.

`deviceToken`, 주소, 감지 여부는 저장합니다. 앱을 다시 열면 지켜보던 상태로 이어지고 포그라운드 서비스가 다시 붙습니다. 앱이 앞에 있는 동안 화면은 꺼지지 않습니다. 카운트가 시작되면 화면을 켭니다. 알림음과 연결 끊김 알림은 만들지 않습니다. 감지 중 알림은 소리 없는 포그라운드 알림입니다.

## 카메라 거치

- 잠금화면이 없는 공기계가 좋습니다. 잠금이 있으면 30초 화면이 가려질 수 있습니다.
- 화면이 방을 향하게 세웁니다. 기본 카메라는 전면입니다. 뒷면이 방을 보면 설정에서 후면으로 바꿉니다.
- 허리에서 가슴 높이, 벽이나 선반에 고정합니다. 넘어져도 어깨와 엉덩이가 화면 안에 남아야 합니다.
- 바닥이 화면 아래쪽에 보이게 살짝 아래로 기울입니다. 침대 옆 바닥까지 들어오는 구도가 기준입니다.
- 역광과 강한 그림자는 피합니다. 전신이 너무 가까우면 누웠을 때 몸이 잘립니다.
- 충전기를 꽂아 둡니다. 배터리가 40% 이하면 대기 화면에 충전 안내가 나옵니다.
- 분석은 640×480 근처에서 초당 몇 장만 처리합니다. 그래도 제조사 절전 정책이 포그라운드 서비스와 카메라를 끊을 수 있습니다.
- 전면 카메라는 미리보기가 좌우로 뒤집혀 있으므로 뼈대도 같이 뒤집습니다. 미리보기와 분석은 같은 ViewPort(FILL_CENTER)로 잘라 위치가 맞도록 합니다.

## 폰트

- 제목·큰 숫자: Gmarket Sans Bold/Medium. 파일은 github.com/fonts-archive/GmarketSans 미러입니다. **배포 전에 공식 파일로 교체**하세요. 공식 배포는 G마켓(corp.gmarket.com/fonts), 상업적 이용 가능, 재판매 금지.
- 본문: Pretendard 1.3.9, SIL Open Font License 1.1. https://github.com/orioncactus/pretendard
- 본문 최소 15sp. 상태 표시는 아이콘과 색과 글자를 같이 씁니다.

## 이 환경에서 확인하지 못한 것

클라우드 빌드에서 디버그 APK, 낙상 규칙 유닛 테스트, 그리고 Robolectric으로 그린 P1–P5 화면까지 확인할 수 있습니다. 실제 공기계의 카메라 정확도, 뼈대가 사람 위에 겹치는지, 640×480에서의 발열, 제조사별 포그라운드 서비스 제한(화면이 꺼진 뒤에도 카메라가 계속 도는지)은 이 환경에 기기가 없어 확인하지 못했습니다. 시연 전에 공기계에서 P2 뼈대 정렬, 어두운 방의 인식, 숨은 테스트, 화면 꺼짐, 충전기 연결을 봐 주세요. 어긋나기 쉬운 곳은 전면 미러와 FILL_CENTER 크롭, 신뢰도 0.35, 가시성 0.35, 평활 0.55, 놓침 유예 1.2초입니다.
