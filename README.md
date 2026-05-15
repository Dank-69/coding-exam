# On-Call Assistant (Coding Exam Project 1)

This project implements three phases required by the exam:

- `v1`: keyword search engine
- `v2`: semantic search (AI embedding first, local fallback second)
- `v3`: On-Call agent with `readFile(filename)` tool trace

## Tech Stack

- Java 21
- Spring Boot 3.4
- Thymeleaf
- Jsoup
- Spring WebFlux `WebClient` (Moonshot API calls)

## Run

```bash
mvn spring-boot:run
```

Service starts at `http://localhost:8080`.

## Required Environment Variables

For full AI-native behavior in `v2`/`v3`:

```bash
MOONSHOT_API_KEY=sk-xxx
```

Optional:

```bash
AI_PROVIDER=moonshot
MOONSHOT_BASE_URL=https://api.moonshot.cn/v1
MOONSHOT_CHAT_MODEL=moonshot-v1-8k
AI_TIMEOUT_SECONDS=20
AI_MAX_TOOL_ROUNDS=4
```

Embedding options:

```bash
AI_EMBEDDINGS_ENABLED=true
AI_EMBEDDING_MODEL=text-embedding-v1
```

If `MOONSHOT_API_KEY` is missing, the system automatically degrades:

- `v2` uses local semantic fallback ranking
- `v3` uses local fallback agent strategy

If embedding is disabled, `v2` also degrades to local fallback ranking.

## Data Bootstrap

By default, service startup auto-imports `data/*.html` into in-memory store:

```bash
ONCALL_BOOTSTRAP_ENABLED=true
ONCALL_BOOTSTRAP_DATA_DIR=data
```

If you need manual upload mode:

```powershell
$files = Get-ChildItem .\data\*.html
foreach ($f in $files) {
  $id = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
  $html = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
  $payload = @{ id = $id; html = $html } | ConvertTo-Json -Compress
  Invoke-RestMethod -Uri "http://localhost:8080/v1/documents" -Method Post -ContentType "application/json; charset=utf-8" -Body $payload | Out-Null
}
```

## Pages

- `http://localhost:8080/v1`
- `http://localhost:8080/v2`
- `http://localhost:8080/v3`

## API Summary

### Phase 1

- `POST /v1/documents`
- `GET /v1/search?q=...`

### Phase 2

- `GET /v2/search?q=...`

### Phase 3

- `GET /v3/chat?message=...` (SSE stream with `thinking/tool_call/tool_result/message/done`)
- `POST /v3/chat` (JSON)

## Validation Commands

```bash
mvn -q -DskipTests compile
mvn -q -Dtest=Phase1OfficialAcceptanceTest test
mvn -q -Dtest=Phase2OfficialAcceptanceTest test
mvn -q -Dtest=Phase3ControllerTest test
```

## Notes

- Error format is unified by global exception handler.
- External AI API errors return `503` with code `EXTERNAL_SERVICE_UNAVAILABLE`.
- Logging includes request path, query, result count, and timing for major paths.
