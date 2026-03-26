# 📈 Real-time Crypto Data Pipeline

바이낸스(Binance) API를 활용하여 실시간 코인 시세를 수집하고, Kafka와 Spark Streaming을 거쳐 시계열 데이터베이스인 TimescaleDB에 적재 및 Grafana로 시각화하는 엔드투엔드(End-to-End) 데이터 파이프라인입니다.

## 🏗 System Architecture

본 프로젝트는 데이터의 수집, 전송, 가공, 저장/시각화 단계를 분리하여 확장성과 유연성을 확보했습니다. (이미지 제공: `image_0.png`)

<img src="https://raw.githubusercontent.com/usim2214-maker/data/main/coin_project/arch.png" width="100%" alt="System Architecture Overview">

*위 이미지는 프로젝트 폴더 내 `arch.png` 파일로 저장되어야 정상적으로 표시됩니다.*

### Data Pipeline Stages
- **수집 레레이어 (Ingestion)**: Binance API에서 실시간 및 과거 데이터 수집.
- **전송 레이어 (Transportation)**: Kafka를 통한 데이터 메시지 큐 구성.
- **가공 레이어 (Processing)**: Spark Streaming을 통한 윈도우 집계.
- **저장/시각화 레이어 (Storage/Vis.)**: TimescaleDB 최적화 저장 및 Grafana 대시보드.

---

## 💡 Technology Choice Rationale (왜 이 기술을 택했는가?)

본 프로젝트는 단순히 기능 구현을 넘어, **데이터 엔지니어링적 효율성**과 **성능 최적화**를 고려하여 각 기술 스택을 선택했습니다.

| 레이어 | 선택 기술 | 기술 선택의 이유 (Rationale) | (대안/확장) |
| :--- | :---: | :--- | :--- |
| **수집** | **Python (asyncio)** | 싱글 스레드에서도 WebSocket의 **수천 개의 동시 연결**을 비동기적으로 처리하기 위해 선택했습니다. | (AWS Kinesis) |
| **전송** | **Apache Kafka** | 초당 발생하는 대량의 트래픽을 처리할 수 있는 **높은 처리량(Throughput)**과 가공 레리어와의 **디커플링(Decoupling)**을 위해 사용했습니다. | (AWS MSK) |
| **가공** | **Spark Streaming** | 단순 데이터 적재를 넘어, Moving Average 등 **복잡한 윈도우 집계 연산**을 분산 컴퓨팅 환경에서 효율적으로 처리하기 위해 선택했습니다. | (AWS Glue) |
| **저장** | **TimescaleDB** | PostgreSQL 기반이라 익숙한 SQL 쿼리를 사용하면서도, **Hypertable** 기능을 통해 대용량 시계열 데이터의 **조회 속도(Indexing)**를 최적화하기 위해 선택했습니다. | (AWS RDS / S3 + Athena) |
| **시각화** | **Grafana** | TimescaleDB와 **플러그인 연동**이 매우 쉽고, **실시간 대시보드**를 통해 데이터의 흐름을 직관적으로 모니터링하기 위해 선택했습니다. | (AWS QuickSight) |

---

## 🚀 Core Engineering Point: Gap Fill & Continuity

네트워크 장애나 시스템 재시작으로 인한 데이터 공백을 해결하는 것은 엔지니어에게 중요한 도전입니다. 본 프로젝트는 이를 **자동화**했습니다.

1.  **시점 파악**: `01_Binance_producer.ipynb`는 실행 시 DB에서 마지막 타임스탬프(`MAX(time)`)를 조회합니다.
2.  **공백 메우기**: 조회된 마지막 시점 이후부터 현재까지의 데이터를 **자동으로 계산**하여 Binance REST API(`klines`)로 **Backfill**을 수행합니다.
3.  **연속성 확보**: 이 과정을 통해 WebSocket의 끊김이 있어도 **데이터의 완벽한 연속성**을 보장합니다.

---

## 📂 Project Structure
- `coin_project/arch.png`: 아키텍처 다이어그램 이미지 파일.
- `coin_project/01_Binance_producer.ipynb`: 실시간 수집(WebSocket) 및 Gap Fill(Backfill) 로직.
- `coin_project/02_Spark_streaming.ipynb`: Kafka 데이터 소비, 전처리 및 DB 적재 로직.
- `coin_project/docker-compose.yml`: Kafka, Zookeeper, TimescaleDB 환경 구축 스크립트.

---

> 본 프로젝트는 **Docker-Compose** 환경에서 원클릭으로 핵심 환경을 구축할 수 있도록 설계되었으며, 향후 **이미지 속 대안 기술(AWS Kinesis, MSK, Glue)**을 활용한 클라우드 네이티브 아키텍처로의 전환까지 고려했습니다.
