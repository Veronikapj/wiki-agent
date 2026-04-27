# LLM Wiki Knowledge Base 설계

> Karpathy의 llm-wiki 패턴을 wiki-agent에 적용 — 임의 소스 ingest + LLM 컴파일 + 구조화된 로컬 지식베이스

**Goal:** Confluence 외부의 URL·텍스트를 받아 LLM으로 컴파일하고, 마크다운(.wiki/knowledge/)과 ChromaDB에 동시 저장해 검색 품질을 높인다.

**Architecture:** 신규 `knowledge/` 패키지(IngestAgent, LintAgent, KnowledgeStore, KnowledgeTool)를 추가하고, OrchestratorAgent가 Confluence보다 KnowledgeBase를 먼저 조회하도록 검색 우선순위를 변경한다.

**Tech Stack:** Kotlin, Koog AIAgent, Ktor Client, ChromaDB, kotlinx.serialization

---

## 전체 아키텍처

```
Slack (mention / DM)
    │
    ├── @wiki ingest <URL>  ──► IngestAgent
    ├── DM 텍스트 붙여넣기  ──► IngestAgent (자동 감지)
    └── @wiki <질문>        ──► OrchestratorAgent
                                    │
                          ┌─────────┼──────────────┐
                          ▼         ▼              ▼
                   KnowledgeTool  ConfluenceTool  GitHubWikiTool
                          │
                   KnowledgeBase
                    ├── .wiki/knowledge/   (마크다운)
                    └── ChromaDB           (벡터 검색)

LintAgent ◄── /wiki lint | 10건 ingest마다 자동
```

## 신규 컴포넌트

### knowledge/ 패키지

| 파일 | 역할 |
|------|------|
| `IngestAgent.kt` | URL fetch 또는 텍스트 수신 → LLM 컴파일 → 마크다운 + ChromaDB 저장 |
| `LintAgent.kt` | 전체 wiki 페이지 순회 → 모순·고아·오래된 내용 감지 및 보고 |
| `KnowledgeStore.kt` | `.wiki/knowledge/` 파일 I/O, index.md·log.md 관리 |
| `KnowledgeTool.kt` | `@Tool fun knowledgeSearch(query)` — OrchestratorAgent 연결 |

### `.wiki/knowledge/` 디렉터리 구조

```
.wiki/
└── knowledge/
    ├── index.md              # 전체 페이지 카탈로그 (카테고리별 1줄 요약)
    ├── log.md                # ingest/lint 이력 (append-only)
    ├── entities/             # 사람·팀·시스템 고유명사
    │   └── 배포-시스템.md
    ├── concepts/             # 개념·프로세스
    │   └── 배포-프로세스.md
    └── sources/              # 원본 메타데이터 (URL, 날짜, 요약)
        └── techblog-배포.md
```

### 기존 파일 변경

| 파일 | 변경 내용 |
|------|---------|
| `SlackBotGateway.kt` | DM ingest 자동 감지 (500자↑ 또는 URL 포함), `@wiki ingest` 처리 |
| `SlackConfigHandler.kt` | `/wiki lint`, `/wiki ingest <URL>` 커맨드 추가 |
| `OrchestratorAgent.kt` | `KnowledgeTool` 등록, 검색 우선순위 변경 |
| `Main.kt` | `IngestAgent`, `LintAgent`, `KnowledgeStore` 생성 및 와이어링 |

## 데이터 흐름

### Ingest

```
1. 입력 수신
   @wiki ingest <URL> 또는 DM 텍스트 (500자↑ 자동 감지)

2. 소스 fetch
   URL  → Ktor HTTP GET → HTML → 텍스트 추출
   텍스트 → 그대로 사용

3. LLM 컴파일 (IngestAgent)
   기존 index.md를 컨텍스트로 제공
   → entities/*.md, concepts/*.md, sources/*.md 생성/업데이트
   → index.md, log.md 업데이트

4. 저장
   KnowledgeStore → .wiki/knowledge/ 마크다운
   ChromaDB       → 새 페이지 벡터 인덱싱

5. Slack 피드백
   ":white_check_mark: concepts/배포-프로세스.md 생성
    연결된 페이지: 배포-시스템, Jenkins"
```

### 검색 우선순위 변경

```
기존: Confluence → GitHub Wiki → ChromaDB(RAG)
변경: KnowledgeBase → Confluence → GitHub Wiki → ChromaDB(RAG)
```

### Lint

```
트리거: /wiki lint 또는 10건 ingest 누적 시 자동

1. KnowledgeStore 전체 페이지 로드
2. LLM 분석
   - 모순: 서로 다른 페이지의 충돌 내용
   - 고아: 아무도 참조하지 않는 페이지
   - 오래됨: 이후 ingest 내용과 충돌하는 클레임
3. 결과를 Slack DM 또는 호출 채널에 보고
```

### DM 자동 감지 로직

```kotlin
when {
    text.startsWith("http")          -> ingest(url = text)
    text.length >= 500               -> suggestIngest(text)  // "지식베이스에 저장할까요?"
    else                             -> 기존 검색
}
```

## 에러 처리

| 상황 | 처리 |
|------|------|
| URL fetch 실패 | Slack 오류 메시지, log.md 실패 기록 |
| LLM 컴파일 실패 | raw 텍스트를 sources/에만 저장 후 "일부 저장됨" 알림 |
| ChromaDB 미실행 | 마크다운만 저장 (degraded mode) |
| 중복 ingest | sources/ URL 기준 감지 → "이미 등록된 소스" |
| lint LLM 실패 | 부분 결과 보고, 다음 lint 재시도 |

## 테스트 전략

```
단위 테스트:
├── KnowledgeStoreTest   — 파일 I/O, index.md·log.md 포맷
├── IngestAgentTest      — URL fetch mock, LLM 컴파일 결과
└── LintAgentTest        — 모순·고아 감지 로직

통합 테스트 (@Tag("eval")):
└── KnowledgeBaseEvalTest — 실제 URL ingest 후 검색 품질
```

## 비기능 요건

- ingest 비동기 처리 — 기존 `messageExecutor` 재사용
- `.wiki/knowledge/` `.gitignore` 추가 (팀별 지식, 커밋 제외)
- lint 자동 트리거는 `KnowledgeStore.ingestCount` 카운터로 관리
- ChromaDB collection명: `knowledge_base` (기존 `wiki_pages`와 분리)
