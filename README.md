# 낙상제로 (Naksang Zero)

남는 스마트폰 공기계 카메라로 고령자 낙상을 감지하고, 30초 동안 반응이 없으면 보호자에게 알리는 서비스. 팀 제로투.

## 구조
- `android/` 공기계 감지 앱 (Android 네이티브, Kotlin, CameraX + 포즈 추정 + TFLite)
- `guardian-web/` 보호자 PWA. 시안은 `docs/design/`
- `server/` 기기 등록·보호자 연결·heartbeat·낙상 이벤트·웹 푸시 (API 계약은 `docs/api.md`)
- `ml/` 낙상 모델 실험·변환 (포즈 키포인트 시계열 → 낙상 확률)
- `docs/` 기획·스펙 문서

## 원칙
영상은 기기 밖으로 보내지 않고, 판정 결과와 시각, 주소 정보만 서버에 남긴다.

## 보호자 웹·서버
로컬 실행은 `server/README.md`, `guardian-web/README.md`. 한 도메인으로 띄울 Dockerfile은 저장소 루트에 있다. 웹 푸시는 HTTPS가 필수다.

Gmarket Sans 파일은 공식 배포본이 아닌 미러에서 받은 것이다. **배포 전 공식 파일로 교체**할 것. 출처는 `docs/design/asset-credits.md`.

## 일정
- 11/3 상세기획서 + 데모 앱·시연 영상 (낙상 감지만)
- 12월 초 본선 발표·시연
