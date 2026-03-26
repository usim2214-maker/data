# 📈 Real-time Crypto Data Pipeline

바이낸스(Binance) API로 실시간 시세를 가져와 Kafka, Spark Streaming을 거쳐 TimescaleDB에 쌓고 Grafana로 시각화하는 엔드투엔드(End-to-End) 데이터 파이프라인입니다.

## 🏗 시스템 아키텍처
단순한 데이터 수집을 넘어, 데이터가 끊기지 않고 안정적으로 흐를 수 있는 구조를 만드는 데 집중했습니다. (이미지: `arch.png`)

<img src="https://raw.githubusercontent.com/usim2214-maker/data/main/coin_project/arch.png" width="100%" alt="System Architecture Overview">

- **수집**: 비동기(asyncio) 기반의 Python Producer가 실시간 시세 수집
- **전송**: Kafka를 활용해 수집과 가공 단계 사이의 의존성 제거(Decoupling)
- **가공**: Spark Streaming으로 실시간 윈도우 집계 및 데이터 정제
- **저장/시각화**: 시계열 DB인 TimescaleDB 적재 및 Grafana 모니터링

---

## 💡 기술 스택 선택 이유 (Rationale)

"왜 이 기술인가?"에 대해 고민하며 프로젝트를 구성했습니다.

<img width="841" height="558" alt="파이프라인 구조" src="https://github.com/user-attachments/assets/f386dd89-d463-4888-ab13-bac65423ecbe" />


| 레이어 | 선택 기술 | 선택 이유 (Why?) |
| :--- | :---: | :--- |
| **수집** | **Python (asyncio)** | **비동기 I/O**를 써서 대량의 WebSocket 데이터를 지연 없이 받기 위해 선택했습니다. 수집기와 가공기를 별도 프로세스로 분리해 안정성을 높였습니다. |
| **전송** | **Apache Kafka** | 데이터가 갑자기 몰려도 유실되지 않도록 **완충 역할(Buffer)**을 하고, 여러 시스템이 데이터를 동시에 쓸 수 있게 확장성을 고려했습니다. |
| **가공** | **Spark Streaming** | 단순 적재만 하는 게 아니라, 실시간으로 **분 단위 평균(Moving Average)** 같은 복잡한 연산을 병렬로 처리하기 위해 도입했습니다. |
| **저장** | **TimescaleDB** | PostgreSQL 기반이라 익숙하면서도, 시계열 데이터 전용 기능인 **Hypertable** 덕분에 대용량 로그 조회 속도가 빨라 선택했습니다. |
| **시각화** | **Grafana** | 실시간 데이터를 직관적으로 모니터링하기 가장 좋고, TimescaleDB와 연동이 매끄러워 채택했습니다. |

---

## 🚀 핵심 엔지니어링 포인트: 데이터 연속성 (Gap Fill)

실제 운영 환경에서는 네트워크 장애나 시스템 재시작으로 데이터가 빌 수 있습니다. 저는 이 문제를 **'자동 보충'** 방식으로 해결했습니다.

- **자동 시점 파악**: 프로그램이 켜질 때 DB에서 마지막으로 저장된 시간(`MAX(time)`)을 먼저 확인합니다.
- **데이터 메우기(Backfill)**: 마지막 시점부터 현재까지 비어있는 구간을 계산해, Binance REST API로 과거 데이터를 긁어와 자동으로 채워 넣습니다.
- **결과**: 시스템이 잠시 멈추더라도 데이터가 끊기지 않고 이어지는 **연속성과 정합성**을 확보했습니다.

---

## 📂 프로젝트 구조
- `coin_project/01_Binance_producer.ipynb`: 실시간 수집 및 데이터 보충(Backfill) 로직
- `coin_project/02_Spark_streaming.ipynb`: Kafka 데이터 가공 및 DB 적재 로직
- `coin_project/docker-compose.yml`: 전체 인프라(Kafka, DB 등) 구축 스크립트

---
> 본 프로젝트는 Docker 기반으로 설계되어 어디서든 즉시 실행 가능하며, 추후 AWS MSK나 Glue 같은 클라우드 환경으로 쉽게 확장할 수 있는 유연한 구조를 갖추고 있습니다.
