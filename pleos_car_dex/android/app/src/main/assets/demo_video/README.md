시연용 주행 영상을 `demo_drive.mp4` 라는 이름으로 이 폴더에 넣으세요.

앱은 영상을 스캔 화면에서 재생하면서, 현재 재생 위치의 프레임을 주기적으로 백엔드
`POST /api/v1/recognize` 로 보내 YOLOv8 로 차량을 인식합니다.
영상이 없으면 `assets/demo_frames/` 의 이미지를 대신 사용합니다.
