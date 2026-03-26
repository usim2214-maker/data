# 📈 Real-time Crypto Data Pipeline

바이낸스(Binance) API를 활용하여 실시간 코인 시세를 수집하고, Kafka와 Spark Streaming을 거쳐 시계열 데이터베이스인 TimescaleDB에 적재하는 엔드투엔드(End-to-End) 데이터 파이프라인입니다.

## 🚀 Project Overview
단순한 데이터 수집을 넘어, 실시간 스트리밍 데이터의 **연속성(Continuity)**과 **정합성(Integrity)**을 확보하는 엔지니어링적 해결책을 구현하는 데 초점을 맞췄습니다.

### Key Features
- **Real-time Ingestion**: WebSocket을 통한 5종 주요 코인(BTC, ETH, BNB, SOL, XRP)의 실시간 시세 수집.
- **Backfill & Gap Fill**: 시스템 중단 시점을 파악해 누락된 과거 데이터를 자동으로 보충하는 로직 구현 (최대 1년치).
- **Stream Processing**: PySpark를 활용한 분 단위 윈도우 집계(Moving Average, Min/Max Price).
- **Time-series Storage**: TimescaleDB의 Hypertable을 활용한 대용량 시계열 데이터 최적화 저장.

---

## 🏗 System Architecture
```text
[Binance API] --(WebSocket/REST)-- [Python Producer]
                                        |
                                   [Apache Kafka]
                                        |
                                [Spark Streaming] --(Window Aggregation)--
                                        |
                                 [TimescaleDB] <--- [Grafana Visualization]
