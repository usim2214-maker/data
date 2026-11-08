from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.schemas import CourseRequest, DriveCourse
from app.services.courses import CourseService
from app.services.deps import get_courses

router = APIRouter(prefix="/api/v1/courses", tags=["courses (AI 드라이브 코스)"])


@router.post("/recommend", response_model=DriveCourse)
async def recommend(req: CourseRequest, svc: CourseService = Depends(get_courses)):
    """미획득 지역 스탬프 + 축제 + 명소를 엮은 드라이브 코스. 앱은 결과를 Pleos NaviHelper 경로 요청으로 넘긴다."""
    return await run_in_threadpool(svc.recommend, req)
