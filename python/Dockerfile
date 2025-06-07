FROM python:3.12-alpine

RUN pip install poetry==2.1.3
ENV POETRY_NO_INTERACTION=1 \
    POETRY_VIRTUALENVS_IN_PROJECT=1 \
    POETRY_VIRTUALENVS_CREATE=1 \
    POETRY_CACHE_DIR=/tmp/poetry_cache

WORKDIR /app
COPY pyproject.toml poetry.lock ./
RUN poetry install --no-root

ENV VIRTUAL_ENV=/app/.venv \
    PATH="/app/.venv/bin:$PATH"

ENV FLASK_HOST=0.0.0.0 \
    FLASK_PORT=8080 \
    FLASK_DEBUG=False \
    KAFKA_BROKER=kafka:9093 \
    KAFKA_TOPIC=sensor_data

COPY . .

ENTRYPOINT ["python", "-m", "generator.main"]