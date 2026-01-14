# ☕ Cafe-Data-Analysis: API 기반 매장 정보 및 가상 주문 데이터 분석

> **실제 매장 정보(API)**와 **가상 구매 기록(Synthetic Data)**을 결합하여 데이터 분석 파이프라인 전 과정을 구현한 프로젝트입니다.

---

## 📅 프로젝트 정보
- **수행 기간**: 2024.12.28 ~ 2025.01.05
- **핵심 목표**: 데이터 수집, DB 설계, SQL 가공, 시각화에 이르는 엔드 투 엔드(End-to-End) 프로세스 경험

## 🛠 기술 스택 (Tech Stack)
- **Language**: Python 3.x
- **Database**: SQLite
- **Library**: Pandas, Requests (API), Matplotlib, Seaborn
- **Tools**: GitHub, AI Prompt Engineering (Code Debugging)

---

## 🚀 주요 수행 내용

### 1. 데이터 수집 및 설계 (Data Collection & Design)
- **REST API 연동**: 공공데이터포털 API를 활용하여 실제 서울시 카페 매장 정보를 마스터 데이터로 확보.
- **가상 데이터 생성**: 분석의 깊이를 위해 500명의 가상 고객과 **7,000건의 트랜잭션(주문)** 데이터를 Python으로 직접 생성.
- **DB 적재**: `SQLite`를 활용하여 수집/생성된 데이터를 관계형 데이터베이스 구조로 설계 및 저장.

### 2. 데이터 가공 및 분석 (SQL & Transformation)
- **SQL 활용**: SQLite 환경에서 `JOIN`, `WITH`절, `strftime`, `CAST` 함수 등을 사용하여 분석용 데이터 마트 구축.
- **코호트 분석**: 고객의 첫 방문 월을 기준으로 재방문율을 계산하는 **Cohort Retention** 로직 구현.
- **마케팅 효율 측정**: 가입 경로별 매출 데이터를 집계하여 매체별 **ROAS(광고 성과)** 지표 산출.

### 3. 결과 시각화 (Visualization)
- **Heatmap**: 시간 흐름에 따른 리텐션 변화를 Seaborn 히트맵으로 시각화하여 이탈 지점 파악.
- **Dashboard**: 주요 지표(매출, 유입 채널 등)를 한눈에 확인할 수 있는 시각화 리포트 도출.

---
<img width="970" height="725" alt="1" src="https://github.com/user-attachments/assets/4500d407-b46d-43b1-accb-6c892ada9ca0" />
<img width="945" height="744" alt="2" src="https://github.com/user-attachments/assets/a5a250e5-404e-4936-9a60-1ab0b18aacbc" />

## 📊 분석 결과 요약
1. **리텐션 분석**: 특정 월 유입 고객의 재방문율 추이를 분석하여 고객 유지 전략 필요성 확인.
2. **채널 효율**: 유입 경로별 결제 데이터를 비교하여 마케팅 비용 대비 수익성이 높은 채널 식별.

---

## 🏆 성과 및 배운 점
- **데이터 핸들링 능력**: 7,000건의 데이터를 직접 생성하고 관리하며 **실무 수준의 데이터 처리 과정**을 경험함.
- **문제 해결 역량**: API 응답 데이터 전처리 및 SQL 데이터 타입 오류를 직접 디버깅하며 **데이터 무결성**의 중요성 체감.
- **비즈니스 관점 확보**: 단순 기능 구현을 넘어 **'리텐션', 'ROAS'** 등 실제 기업에서 사용하는 지표를 직접 산출해 본 경험.

---

## 📂 파일 구조
- `api_collector.py`: API 데이터 수집 스크립트
- `data_generator.py`: 가상 고객/주문 데이터 생성 로직
- `db_loader.py`: SQLite 데이터 적재 스크립트
- `analysis_sql.sql`: 데이터 가공에 사용된 SQL 쿼리
- `visualization.ipynb`: 데이터 분석 및 시각화 결과 보고서
