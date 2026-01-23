🎵 Lyrical-Insight: AI Agent Based Music Sentiment Analysis Pipeline
비정형 가사 데이터를 정밀 감성 지표로 정형화하는 AI 에이전트 기반 E2E 파이프라인

기존의 단순 키워드 매칭 방식 뮤직비디오 생성 시스템의 한계를 극복하기 위해, LLM(Large Language Model) 기반의 맥락 분석을 도입한 고도화 프로젝트입니다.

📌 Project Overview
개발 기간: 2026.01 (진행 중)

핵심 목표: 가사의 맥락을 파악하여 감성 지표(Mood, Score)를 도출하고, 시각적 생성 AI를 위한 프롬프트(Visual Keyword)를 자동 생성.

주요 해결 과제:

비정형 텍스트의 100% 정형화 (JSON Parsing)

동적 웹 페이지의 안정적 크롤링 및 예외 처리

분석 결과의 실시간 웹 서빙

🏗 System Architecture
Extraction: Selenium을 이용해 동적 웹(Melon)에서 실시간 차트 가사 데이터 수집.

Transformation: LangChain과 Llama 3.3 모델을 연동하여 텍스트 맥락 분석 및 지표 정형화.

Validation: JsonOutputParser를 통해 데이터 정합성을 검증하고 JSON 포맷으로 변환.

Serving: FastAPI를 통해 분석된 데이터를 비즈니스 리포트 형태로 시각화.

🛠 Tech Stack
Languages: Python 3.10+

Scraping: Selenium, Webdriver-manager

AI/LLM: LangChain, Groq Cloud (Llama 3.3), Prompt Engineering

Web Framework: FastAPI, Uvicorn

Data Manipulation: Pandas

🔥 Key Technical Accomplishments (My Role)
1️⃣ AI 기반 비정형 데이터 정형화 (Agentic Workflow)
Problem: LLM의 응답이 일관되지 않아 데이터베이스 적재 시 오류 발생.

Solution: LangChain의 구조화된 출력(Structured Output) 기법을 도입하여 AI가 반드시 지정된 JSON 스키마로만 응답하도록 강제. 데이터 정합성 100% 달성.

2️⃣ 동적 수집 자동화 및 방어적 코드 설계
Problem: 검색 결과 부재, 가사 비공개 등 다양한 런타임 에러로 파이프라인 중단.

Solution: WebDriverWait와 Try-Except를 활용한 예외 처리 로직을 구축하여 수집 성공률 극대화.

3️⃣ 시각적 키워드 생성 로직 최적화
Solution: 가사의 분위기를 단순히 '슬픔', '기쁨'으로 나누는 것을 넘어, Stable Diffusion 등 생성 AI 모델이 즉시 활용할 수 있는 형태의 프롬프트 자동 추천 기능 구현.
