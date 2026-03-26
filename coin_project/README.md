# 📈 Real-time Crypto Data Pipeline

바이낸스(Binance) API로 실시간 시세를 가져와 Kafka, Spark Streaming을 거쳐 TimescaleDB에 쌓고 Grafana로 시각화하는 엔드투엔드(End-to-End) 데이터 파이프라인입니다.

---

## 🏗 1. 시스템 아키텍처 (System Architecture)
단순한 데이터 수집을 넘어, 각 단계가 독립적으로 작동하면서도 유기적으로 연결된 **분산 처리 구조**를 지향했습니다.

<img width="841" height="558" alt="파이프라인 구조" src="https://github.com/user-attachments/assets/71c48cad-a818-44a7-a481-fb57409b9633" />


### 아키텍처 설계 핵심
- **디커플링(Decoupling)**: Kafka를 중심에 두어 수집(Producer)과 가공(Consumer) 레이어를 분리, 시스템 안정성을 높였습니다.
- **확장성**: 추후 클라우드(AWS MSK, Glue 등) 환경으로 전환하기 쉬운 유연한 구조를 갖추고 있습니다.

---

## 📊 2. 실시간 모니터링 (Grafana Dashboard)
데이터 엔지니어링의 핵심은 **데이터의 흐름을 관찰하는 것**입니다. 실제 파이프라인이 정상적으로 가동되는지 대시보드를 통해 실시간으로 관리했습니다.

<img width="2220" height="1386" alt="monitoring" src="https://github.com/user-attachments/assets/7ca507c4-9c76-48ec-b990-ca021f4ee667" />


- **가격 트래킹**: 주요 코인 5종(BTC, ETH, XRP, BNB, SOL)의 가격 변동 실시간 시각화.
- **헬스 체크(Health Check)**: 우측 하단의 **'분당 수집 건수'** 차트를 통해 데이터 누락이나 지연(Lag) 발생 여부를 상시 모니터링합니다.

---

## 💡 3. 기술 스택 선택 이유 (Rationale)

| 레이어 | 선택 기술 | 선택 이유 (Why?) |
| :--- | :---: | :--- |
| **수집** | **Python (asyncio)** | **비동기 I/O**를 써서 대량의 데이터 요청을 지연 없이 처리하기 위해 선택했습니다. |
| **전송** | **Apache Kafka** | 데이터가 일시적으로 몰려도 유실 없이 받아주는 **버퍼(Buffer)** 역할로 최적이라 판단했습니다. |
| **가공** | **Spark Streaming** | 대량의 스트리밍 데이터를 분 단위로 집계하고 정제하는 **병렬 처리** 성능을 위해 도입했습니다. |
| **저장** | **TimescaleDB** | **Hypertable** 기능을 통해 시계열 데이터 저장 효율과 쿼리 성능을 동시에 잡기 위해 선택했습니다. |
| **시각화** | **Grafana** | 실시간 데이터를 대시보드로 구성하기 가장 직관적이며 DB 연동이 우수해 채택했습니다. |

---

## 🚀 4. 문제 해결: 데이터 연속성 보장 (Gap Fill)

실제 운영 환경에서 발생할 수 있는 데이터 공백 문제를 **'자동 보충'** 로직으로 해결했습니다.

- **비어있는 시간 자동 감지**: 실행 시 DB의 마지막 데이터 시점(`MAX(time)`)을 파악합니다.
- **백필(Backfill) 수행**: 중단되었던 시점부터 현재까지의 데이터를 Binance API로 호출하여 누락된 구간을 자동으로 메웁니다.
- **정합성 확보**: 이를 통해 시스템 재시작이나 일시적인 장애 후에도 데이터의 **완벽한 연속성**을 보장합니다.

---

## 📂 5. 프로젝트 실행 방법
본 프로젝트는 **Docker-Compose** 환경에서 원클릭으로 인프라를 구축할 수 있도록 설계되었습니다.

1. `docker-compose up -d` (Kafka, DB, Grafana 실행)
2. `01_Binance_producer.ipynb` 실행 (데이터 수집 시작)
3. `02_Spark_streaming.ipynb` 실행 (데이터 가공 및 적재 시작)
