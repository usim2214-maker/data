# 📊 Yelp Review Analysis Project  
### 고평점·저평점 패턴 분석 + 텍스트 길이 영향 분석 + 사용자 ROI 분석  

---

## 🚀 프로젝트 개요

Yelp 데이터셋을 기반으로 고객 리뷰의 패턴을 분석하여  
- **별점과 리뷰 길이의 관계**,  
- **고객 행동 패턴**,  
- **마케팅 효율(ROI) 높은 유저 탐색**  
을 수행하는 데이터 분석 프로젝트입니다.

본 프로젝트는 다음과 같은 질문(Hypothesis)을 중심으로 진행되었습니다:

- **H1. 리뷰 길이는 별점에 영향을 미치는가?**  
- **H2. 리뷰 텍스트 특성이 유용도(useful)에 영향을 주는가?**  
- **H3. 마케팅 ROI가 높은 사용자는 특정 패턴을 보이는가?**

---

## 🗂️ 프로젝트 구조

```
yelp_data/
│── data/
│     └── yelp_academic_dataset_review.json
│
├── src/
│     ├── preprocess.py
│     ├── visualize.py
│     ├── user_analysis.py
│     └── __init__.py
│
├── main.py
└── README.md
```

---

## 🔧 주요 기능

### 1️⃣ 데이터 전처리
텍스트 길이 계산, 길이 그룹 분류(Short/Long), 별점 그룹(Low/Mid/High) 생성.

### 2️⃣ 시각화
프로젝트 전반의 시각화 그래프들을 **일관된 스타일로 통일**하여 가독성과 시각적 일관성 확보
- 별점 분포 히스토그램  
- 리뷰 길이 그룹별 박스플롯  
- 상관관계 히트맵 

### 3️⃣ 사용자 ROI 분석
`useful / review_count` 기반 영향력 높은 사용자 식별.

### 4️⃣ 통계 검정 (Mann-Whitney U Test)
리뷰 특성과 별점 관계의 유의성 분석.

---

## ▶️ 실행 방법

```
pip install -r requirements.txt
python main.py
```

---

## 📌 개선 사항
보상·인센티브 제공

  -긴 리뷰 작성자에게 배지, 포인트, 할인 쿠폰 제공

  -예: 200자 이상 작성 시 ‘Top Reviewer’ 배지 지급

---

## 👤 만든이
Sim WooSeock  
