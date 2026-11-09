# 공식 차량 이미지 URL 수집 스크립트

`android/app/src/main/assets/vehicle_images.json` 을 만들 때 쓴 스크립트입니다. 각 브랜드 공식 사이트를 브라우저(Playwright)로 일반 방문하면서
페이지가 불러오는 이미지 주소만 기록합니다 (이미지 파일은 저장하지 않음).

- `vr.js <현대 모델 slug...>` — 현대 `/contents/vr360/<모델코드>/exterior/.../NNN.png` (360°, 60프레임)
- `kia.js <기아 모델 slug...>` — 기아 `/configurator/<모델>/trim/exterior/.../<색>_NN.png` (360°, 72프레임)
- `cap.js <URL>` — 페이지가 요청하는 이미지/API 주소 목록 (제네시스·캐스퍼 등 확인용)
- `links.js <URL> <정규식>` — 라인업 페이지의 모델 링크

실행: `node vr.js ioniq5 palisade` (HTTPS_PROXY 환경이면 자동 사용)

⚠️ 이미지 저작권은 각 제조사에 있습니다. 비상업 프로토타입 용도로만 사용하고, 상업화 전에는 사용 허락을 받거나 교체하세요.
