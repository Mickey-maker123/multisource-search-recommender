"""Local-only BGE-M3 dense service. Business authentication stays in Java."""
import asyncio
import hmac
import math
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal
from inference_queue import InferenceQueue, QueueExpired, QueueFull, ClientGone, connected_call

BOOT_STARTED = time.perf_counter()

# Set before importing Hugging Face libraries; requests never trigger downloads.
os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'
os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field, field_validator

REVISION = '5617a9f61b028005a4858fdac845db406aefb181'
MODEL_VERSION = f'bge-m3:{REVISION}:dense-fp32:maxlen-{os.getenv("MODEL_MAX_LENGTH", "512")}'
bearer = HTTPBearer(auto_error=False)


class EmbeddingRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=8)
    purpose: Literal['query', 'index'] = 'query'

    @field_validator('texts')
    @classmethod
    def validate_texts(cls, texts):
        if any(not text.strip() or len(text) > 20000 for text in texts):
            raise ValueError('Each text must contain 1..20000 nonblank characters')
        return texts


def authorized(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
    secret = os.getenv('EMBEDDING_API_KEY', '')
    if not secret or credentials is None or not hmac.compare_digest(credentials.credentials.encode('utf-8'), secret.encode('utf-8')):
        raise HTTPException(401, detail='UNAUTHORIZED')


@asynccontextmanager
async def lifespan(app):
    loading_started = time.perf_counter()
    max_tokens = int(os.getenv('MODEL_MAX_LENGTH', '512'))
    if max_tokens != 512:
        raise RuntimeError('Engine text policy requires 512 tokens')
    if len(os.getenv('EMBEDDING_API_KEY', '')) < 32:
        raise RuntimeError('Configure EMBEDDING_API_KEY with at least 32 characters')
    path = Path(os.environ.get('MODEL_PATH', ''))
    if not path.is_dir() or not (path / 'pytorch_model.bin').is_file():
        raise RuntimeError('Download and verify the pinned model snapshot before starting')
    # Verify provenance AND bytes, including tokenizer, before loading a local model.
    import hashlib
    import json
    manifest = json.loads((path / 'snapshot-manifest.json').read_text(encoding='utf-8'))
    if manifest['revision'] != REVISION or manifest['repo'] != 'BAAI/bge-m3':
        raise RuntimeError('Model manifest does not match the pinned revision')
    required_files = {'config.json', 'pytorch_model.bin', 'tokenizer.json', 'tokenizer_config.json',
                      'special_tokens_map.json', 'sentencepiece.bpe.model', 'colbert_linear.pt', 'sparse_linear.pt'}
    if set(manifest['files']) != required_files:
        raise RuntimeError('Incomplete model manifest')
    for name, record in manifest['files'].items():
        file = path / name
        if file.parent.resolve() != path.resolve() or not file.is_file():
            raise RuntimeError('Invalid model manifest path')
        with file.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != record['sha256']:
            raise RuntimeError('Model snapshot checksum mismatch')
    import torch
    from FlagEmbedding import BGEM3FlagModel
    torch.set_num_threads(int(os.getenv('TORCH_NUM_THREADS', '4')))
    app.state.model = BGEM3FlagModel(str(path), use_fp16=False, devices='cpu')
    app.state.inference_queue = InferenceQueue(
        capacity=int(os.getenv('MODEL_QUEUE_CAPACITY', '8')),
        wait_seconds=float(os.getenv('MODEL_QUEUE_WAIT_SECONDS', '5')))
    app.state.cold_load_ms = round((time.perf_counter() - loading_started) * 1000, 2)
    app.state.startup_ms = round((time.perf_counter() - BOOT_STARTED) * 1000, 2)
    yield
    await app.state.inference_queue.close()
    app.state.model = None


app = FastAPI(title='independent engine embeddings', version='0.1.0', lifespan=lifespan)


class SegmentRequest(BaseModel):
    title: str = Field(max_length=512)
    text: str = Field(min_length=1, max_length=20000)


@app.post('/v1/segments', dependencies=[Depends(authorized)])
def segments(payload: SegmentRequest, request: Request):
    """Token-controlled, exhaustive body coverage; original Python codepoint offsets."""
    tokenizer = request.app.state.model.tokenizer
    title_ids = tokenizer.encode(payload.title, add_special_tokens=False)
    title = tokenizer.decode(title_ids[:64], skip_special_tokens=True)
    encoded = tokenizer(payload.text, add_special_tokens=False, return_offsets_mapping=True)
    offsets = encoded['offset_mapping']
    if not offsets or not payload.text.strip():
        return {'policyVersion': 'engine-text-v1', 'segments': []}
    parts = []
    token_start = char_start = 0
    while token_start < len(offsets):
        end = min(token_start + 420, len(offsets))
        while end > token_start:
            char_end = len(payload.text) if end == len(offsets) else offsets[end - 1][1]
            body = payload.text[char_start:char_end]
            text = f'标题：{title}\n正文：{body}'
            count = len(tokenizer.encode(text, add_special_tokens=True, truncation=False))
            if count <= 512 and char_end > char_start:
                break
            end -= 1
        if end <= token_start:
            raise HTTPException(422, detail='UNSEGMENTABLE_INPUT')
        parts.append({'text': body, 'input': text, 'charStart': char_start, 'charEnd': char_end,
                      'inputTokens': count, 'titleTruncated': len(title_ids) > 64})
        token_start, char_start = end, char_end
    return {'policyVersion': 'engine-text-v1', 'segments': parts}


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    # Default FastAPI validation errors can echo submitted text. Never return it.
    return JSONResponse(status_code=422, content={'code': 'INVALID_INPUT', 'message': 'Invalid embedding request'})


@app.middleware('http')
async def bounded_body(request: Request, call_next):
    if request.method == 'POST':
        total = 0
        chunks = []
        async for chunk in request.stream():
            total += len(chunk)
            if total > 128 * 1024:
                return JSONResponse(status_code=413, content={'code': 'PAYLOAD_TOO_LARGE'})
            chunks.append(chunk)
        request._body = b''.join(chunks)
    return await call_next(request)


@app.get('/health')
def health():
    return {'status': 'ok', 'modelVersion': MODEL_VERSION, 'dimensions': 1024,
            'maxTokens': int(os.getenv('MODEL_MAX_LENGTH', '512')), 'device': 'cpu', 'dtype': 'float32',
            'processId': os.getpid(), 'coldLoadMs': app.state.cold_load_ms,
            'moduleToReadyMs': app.state.startup_ms}


@app.post('/v1/embeddings', dependencies=[Depends(authorized)])
async def embeddings(payload: EmbeddingRequest, request: Request):
    async def infer(texts):
        started = time.perf_counter()
        # Report lengths, never text. The tokenizer includes special tokens.
        token_counts = [len(request.app.state.model.tokenizer.encode(text, add_special_tokens=True, truncation=False))
                        for text in texts]
        if any(count > 512 for count in token_counts):
            raise HTTPException(422, detail='TOKEN_LIMIT')
        try:
            result = await asyncio.to_thread(
                request.app.state.model.encode, texts,
                batch_size=int(os.getenv('MODEL_BATCH_SIZE', '2')),
                max_length=int(os.getenv('MODEL_MAX_LENGTH', '512')),
                return_dense=True, return_sparse=False, return_colbert_vecs=False,
            )
        except Exception:
            raise HTTPException(503, detail='MODEL_UNAVAILABLE') from None
        try:
            vectors = result['dense_vecs'].tolist()
        except (KeyError, AttributeError, TypeError):
            raise HTTPException(503, detail='INVALID_MODEL_OUTPUT') from None
        if len(vectors) != len(texts):
            raise HTTPException(503, detail='INVALID_MODEL_OUTPUT')
        for vector in vectors:
            if not isinstance(vector, list) or len(vector) != 1024 or not all(isinstance(x, (float, int)) and math.isfinite(x) for x in vector):
                raise HTTPException(503, detail='INVALID_MODEL_OUTPUT')
            norm = math.hypot(*vector)
            if not math.isfinite(norm) or norm == 0:
                raise HTTPException(503, detail='INVALID_MODEL_OUTPUT')
            vector[:] = [x / norm for x in vector]
        return {
            'modelVersion': MODEL_VERSION, 'dimensions': 1024, 'vectors': vectors,
            'elapsedMs': round((time.perf_counter() - started) * 1000, 2),
            'maxTokens': int(os.getenv('MODEL_MAX_LENGTH', '512')),
            'inputTokens': token_counts,
            'truncated': [False for count in token_counts],
            'policyVersion': 'engine-text-v1',
        }
    async def scheduled():
        # Yield between native index batches; a long import cannot monopolize the slot.
        quantum = 2 if payload.purpose == 'index' else len(payload.texts)
        combined = None
        for offset in range(0, len(payload.texts), quantum):
            texts = payload.texts[offset:offset + quantum]
            part = await request.app.state.inference_queue.run(lambda: infer(texts), payload.purpose)
            if combined is None:
                combined = part
            else:
                for field in ('vectors', 'inputTokens', 'truncated'):
                    combined[field].extend(part[field])
                combined['elapsedMs'] = round(combined['elapsedMs'] + part['elapsedMs'], 2)
        return combined
    try:
        return await connected_call(scheduled, request.receive)
    except ClientGone:
        return Response(status_code=499)
    except QueueFull:
        raise HTTPException(429, detail='MODEL_BUSY', headers={'Retry-After': '1'}) from None
    except QueueExpired:
        raise HTTPException(503, detail='MODEL_QUEUE_TIMEOUT', headers={'Retry-After': '1'}) from None


@app.get('/v1/status', dependencies=[Depends(authorized)])
async def status():
    return {**health(), 'queue': app.state.inference_queue.status()}


