import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.config import get_settings
from app.routers import challenge, collection, courses, landmarks, preferences, rankings, recognize, showroom, stamps

logging.basicConfig(level=logging.INFO)

settings = get_settings()
app = FastAPI(title="Pleos CarDex API", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

app.include_router(recognize.router)
app.include_router(collection.router)
app.include_router(preferences.router)
app.include_router(showroom.router)
app.include_router(stamps.router)
app.include_router(landmarks.router)
app.include_router(courses.router)
app.include_router(challenge.router)
app.include_router(rankings.router)
app.mount("/static/crops", StaticFiles(directory=settings.crops_dir), name="crops")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "detector": settings.detector_mode,
        "identifier": settings.identifier_mode if settings.llm_enabled else "mock",
        "llm": settings.llm_model if settings.llm_enabled else "mock",
        "tour": "tourapi" if settings.tour_enabled else "mock",
    }
