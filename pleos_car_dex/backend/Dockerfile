# CarDex 백엔드 (FastAPI)
#   가벼운 이미지 (차량 감지는 목업):  docker build -t cardex-backend backend
#   YOLOv8 실제 차량 감지 포함:        docker build -t cardex-backend --build-arg VISION=1 backend
FROM python:3.12-slim
ARG VISION=0
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DATA_DIR=/data
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt requirements-vision.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
 && if [ "$VISION" = "1" ]; then \
      pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu \
      && pip install --no-cache-dir -r requirements-vision.txt; \
    fi \
 && if [ "$VISION" = "1" ]; then echo yolo > /app/.detector; else echo mock > /app/.detector; fi
COPY app ./app
# YOLO 가중치(yolov8n.pt)는 첫 실행 때 ultralytics 가 자동으로 받는다 (VISION=1)
VOLUME ["/data"]
EXPOSE 8000
# DETECTOR_MODE 를 따로 주지 않으면 이미지 종류에 맞게 (VISION=1 → yolo, 아니면 mock)
CMD ["sh", "-c", "export DETECTOR_MODE=${DETECTOR_MODE:-$(cat /app/.detector)}; exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
