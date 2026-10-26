# 낙상제로 final4 디자인 시스템
## 색
palette1.css 그대로(tokens.css). 새 색 없음. 파생 톤은 일러스트·면 전용이고 모두 기존 토큰 혼합:
- sage600: #305742 = mix(btn 80%, text)
- sage300: #9FB2A2 = mix(btn 45%, bg)
- sage150: #D2D8CD = mix(btn 18%, bg)
- clay500: #B79960 = mix(warn 55%, warnBg)
- clay250: #E0CCA7 = mix(warn 22%, warnBg)
- sky: #DBE5ED = mix(link 16%, surface)
- night: #1E3A46 = mix(link 35%, text)
- blush: #EECBB5 = mix(danger 18%, warnBg)
- paper: #FAF8F4 = mix(bg 50%, surface)- 위험색(--c-danger)은 G3(배경 블록·119 버튼·핀)에서만 사용.
## 타이포
- 디스플레이: Gmarket Sans Bold. 제목 24~52px, 숫자 40~150px, 자간 -0.03em, 행간 1.12
- 본문: Pretendard 15~18px (최소 15px), 라벨 15px Bold
- 대비 규칙: 한 화면에 가장 큰 글자는 그다음 큰 글자의 1.5배 이상
## 위계 규칙 (카드 대신)
- 카드 컴포넌트 없음. 정보 묶음은 1.5px 구분선(.row), 숫자 크기, 색 면(곡선/물결 모양)으로 나눔
- 배지는 한 화면에 최대 2개(실제 0~1개). 칩 없음 → 방 선택은 밑줄 탭
- 상태 = 아이콘 + 색 + 글자(예: ✓ 켜져 있어요, ⚠ 확인 안 됨)
## 구도
- 화면마다 일러스트 1개 이상, 대부분 가장자리 밖으로 잘림(P1·P3·P4·G1·G2·G3·G4·G5)
- 색 면은 물결(P3·P5·G3), 큰 반경 한쪽 모서리(G2·G4·G5·P1 코드 블록)로 비대칭
- 시트는 뒤에 한 겹 더 보이는 겹침(P2·G3)
## 터치
버튼 높이 50~72px, 텍스트 행동 영역 최소 44px.
## DEV
P3 숨은 낙상 테스트는 HTML 주석으로만(집 일러스트 5초 길게 누르기).
