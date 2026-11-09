from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.schemas import CourseBuildRequest, CourseCandidates, CourseRequest, DriveCourse
from app.services.courses import CourseService
from app.services.deps import get_courses

router = APIRouter(prefix="/api/v1/courses", tags=["courses (AI 드라이브 코스)"])


@router.post("/recommend", response_model=DriveCourse)
async def recommend(req: CourseRequest, svc: CourseService = Depends(get_courses)):
    """미획득 지역 스탬프 + 축제 + 명소를 엮은 드라이브 코스. 앱은 결과를 Pleos NaviHelper 경로 요청으로 넘긴다."""
    return await run_in_threadpool(svc.recommend, req)


@router.post("/candidates", response_model=CourseCandidates)
async def candidates(req: CourseRequest, svc: CourseService = Depends(get_courses)):
    """앱의 Pleos LLM 용: 후보 목록 + 프롬프트 + 규칙 기반 대체 선택"""
    return await run_in_threadpool(svc.candidates, req)


@router.post("/build", response_model=DriveCourse)
async def build(req: CourseBuildRequest, svc: CourseService = Depends(get_courses)):
    """앱(Pleos LLM)이 고른 후보 ID 로 코스 조립 — 후보 밖 ID 제거, 이동 시간·점수는 서버 계산"""
    return await run_in_threadpool(svc.build, req)
