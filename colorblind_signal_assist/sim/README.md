# 실제 도로 시뮬레이션 — 부산 해운대구 센텀시티

OpenStreetMap 실제 도로망 위에서 SUMO 교통 시뮬레이션을 돌려 '내 차' 주행 기록을 만들고, 앱 엔진(core)이 그 기록으로 알림을 판단합니다.

```bash
pip install eclipse-sumo==1.21.0 traci==1.21.0 sumolib==1.21.0 pyproj
python3 sim/run_centum.py          # data/centum_trace.csv, data/centum_map.txt
./gradlew :core:test               # TraceDriveTest: 주행 기록으로 엔진 전체 검증
./gradlew :demo:renderCentum       # 센텀시티 시연 영상 (3D 항공·운전석 시점)
./gradlew :demo:preview3d          # 3D 시점 미리보기 PNG + 인식 결과
```

| 파일 | 내용 |
|---|---|
| `data/centum.osm` | OpenStreetMap 원본 (해운대로·센텀중앙로·APEC로 일대, 약 2.0×2.0km) |
| `data/centum.net.xml` | SUMO 도로망 (netconvert 로 변환, 신호 51곳) |
| `data/background.rou.xml` | 주변 차량 (무작위 통행) |
| `data/centum_trace.csv` | 내 차 주행 기록 (0.1초 간격: 차속, 다음 신호 거리·상태, 도로명, 교차로명, 위치, 주변 차량) |
| `data/centum_map.txt` | 영상용 지도 (도로·건물 높이·물·역·도로명·경로 신호·경로) |

**경로**: 해운대로(센텀북대로 교차로 부근)에서 남동쪽으로 → 센텀5로·센텀4로·센텀남대로 교차로 → APEC로, 1.76km, 경로상 신호 12개

**연출된 장면**
- 두 번째로 만나는 빨간불을 운전자가 처음엔 못 보고 50km/h 로 계속 접근 → 긴급 경고 조건 1초 뒤 제동
- 세 번째 정차에서 초록불이 되고 4초 늦게 출발
- 그 밖에는 사람처럼 일찍·부드럽게(1.6 m/s²) 정지하는 운전자 모델

**주의**
- 도로·차로·신호등 위치는 실제 데이터지만, **신호 주기는 SUMO가 자동 생성한 값으로 실제 부산 신호 주기가 아닙니다.**
- 지도 데이터 © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright)
