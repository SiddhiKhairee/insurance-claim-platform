FROM python:3.11-slim
WORKDIR /app

COPY requirements.txt requirements-ml.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r requirements-ml.txt

# Bake both model weights into the image so the container never downloads at runtime
# (reproducible, works offline). Cost: a larger image. Kept before the code COPY so this
# expensive layer is cached across code changes.
ARG NLI_MODEL=cross-encoder/nli-deberta-v3-small
ENV NLI_MODEL=${NLI_MODEL} HF_HOME=/opt/hf
RUN python -c "import os; from sentence_transformers import SentenceTransformer, CrossEncoder; SentenceTransformer('all-MiniLM-L6-v2'); CrossEncoder(os.environ['NLI_MODEL'])"
ENV HF_HUB_OFFLINE=1

COPY main.py .
COPY assistant/ assistant/
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
