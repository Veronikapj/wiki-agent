# LLM Wiki Knowledge Base Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Confluence 외부의 URL·텍스트를 LLM으로 컴파일해 마크다운(`.wiki/knowledge/`)과 ChromaDB에 저장하고, OrchestratorAgent 검색 우선순위에 KnowledgeBase를 추가한다.

**Architecture:** 신규 `knowledge/` 패키지(KnowledgeStore, IngestAgent, LintAgent, KnowledgeTool)를 추가하고, SlackConfigHandler/SlackBotGateway/OrchestratorAgent/Main을 순서대로 업데이트한다.

**Tech Stack:** Kotlin, Koog AIAgent `@Tool`, Ktor Client, ChromaClient (기존), kotlinx.serialization, JUnit 5

---

## Task 1: KnowledgeStore — 파일 I/O

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStore.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStoreTest.kt`

**Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStoreTest.kt
package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class KnowledgeStoreTest {

    private val baseDir = "build/test-wiki-knowledge-${System.nanoTime()}"
    private val store = KnowledgeStore(baseDir)

    @AfterEach fun cleanup() { File(baseDir).deleteRecursively() }

    @Test fun `savePage writes markdown file`() {
        store.savePage("concepts/배포-프로세스.md", "# 배포 프로세스\n내용")
        val file = File("$baseDir/concepts/배포-프로세스.md")
        assertTrue(file.exists())
        assertTrue(file.readText().contains("배포 프로세스"))
    }

    @Test fun `savePage creates parent directories`() {
        store.savePage("entities/sub/항목.md", "내용")
        assertTrue(File("$baseDir/entities/sub/항목.md").exists())
    }

    @Test fun `appendLog records entry`() {
        store.appendLog("ingest", "URL https://example.com 완료")
        val log = File("$baseDir/log.md").readText()
        assertTrue(log.contains("ingest"))
        assertTrue(log.contains("https://example.com"))
    }

    @Test fun `updateIndex appends line`() {
        store.updateIndex("concepts/배포.md", "배포 프로세스 관련 개념")
        val index = File("$baseDir/index.md").readText()
        assertTrue(index.contains("concepts/배포.md"))
        assertTrue(index.contains("배포 프로세스"))
    }

    @Test fun `loadAll returns saved pages`() {
        store.savePage("concepts/a.md", "# A")
        store.savePage("entities/b.md", "# B")
        val pages = store.loadAll()
        assertEquals(2, pages.size)
        assertTrue(pages.any { it.first == "concepts/a.md" })
    }

    @Test fun `pageExists returns true for saved page`() {
        store.savePage("sources/x.md", "url: https://example.com")
        assertTrue(store.pageExists("sources/x.md"))
        assertFalse(store.pageExists("sources/missing.md"))
    }

    @Test fun `incrementAndGetIngestCount returns sequential values`() {
        assertEquals(1, store.incrementAndGetIngestCount())
        assertEquals(2, store.incrementAndGetIngestCount())
        assertEquals(3, store.incrementAndGetIngestCount())
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.KnowledgeStoreTest" 2>&1 | tail -20
```
Expected: FAIL — `KnowledgeStore` 클래스 없음

**Step 3: Write minimal implementation**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStore.kt
package io.github.veronikapj.wiki.knowledge

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class KnowledgeStore(private val baseDir: String = ".wiki/knowledge") {

    private val lock = ReentrantLock()
    private val ingestCount = AtomicInteger(0)

    fun savePage(relativePath: String, content: String) = lock.withLock {
        val file = File("$baseDir/$relativePath")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun appendLog(action: String, detail: String) = lock.withLock {
        val file = File("$baseDir/log.md")
        file.parentFile?.mkdirs()
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        file.appendText("- [$ts] $action: $detail\n")
    }

    fun updateIndex(relativePath: String, summary: String) = lock.withLock {
        val file = File("$baseDir/index.md")
        file.parentFile?.mkdirs()
        file.appendText("- [$relativePath]($relativePath) — $summary\n")
    }

    // Returns list of (relativePath, content) pairs for all .md pages except index.md and log.md
    fun loadAll(): List<Pair<String, String>> {
        val root = File(baseDir)
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { it.name != "index.md" && it.name != "log.md" }
            .map { file ->
                val rel = file.relativeTo(root).path
                rel to file.readText()
            }.toList()
    }

    fun pageExists(relativePath: String): Boolean =
        File("$baseDir/$relativePath").exists()

    fun incrementAndGetIngestCount(): Int = ingestCount.incrementAndGet()

    fun loadIndex(): String? {
        val f = File("$baseDir/index.md")
        return if (f.exists()) f.readText() else null
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.KnowledgeStoreTest" 2>&1 | tail -10
```
Expected: PASS — 7 tests green

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStore.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeStoreTest.kt
git commit -m "feat: add KnowledgeStore for .wiki/knowledge/ file I/O"
```

---

## Task 2: IngestAgent — URL fetch + LLM compile + ChromaDB 저장

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgentTest.kt`

**Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgentTest.kt
package io.github.veronikapj.wiki.knowledge

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class IngestAgentTest {

    private val baseDir = "build/test-ingest-${System.nanoTime()}"
    private val store = KnowledgeStore(baseDir)
    private val llmFn: suspend (String) -> String = mockk()
    private val chromaIndexFn: (suspend (String, String, String) -> Unit)? = null
    private val agent = IngestAgent(store, llmFn, chromaIndexFn)

    @AfterEach fun cleanup() { File(baseDir).deleteRecursively() }

    @Test fun `ingest text compiles and saves concept page`() = runBlocking {
        coEvery { llmFn(any()) } returns """
            PAGES:
            concepts/테스트-개념.md:
            # 테스트 개념
            테스트 설명입니다.
            ---
        """.trimIndent()

        val result = agent.ingestText("테스트 개념에 대한 긴 설명 텍스트입니다.")

        assertTrue(result.contains("테스트-개념.md") || result.contains("저장"))
        assertTrue(store.pageExists("concepts/테스트-개념.md"))
    }

    @Test fun `ingest detects duplicate URL via sources dir`() = runBlocking {
        store.savePage("sources/example-com.md", "url: https://example.com\n날짜: 2024-01")
        coEvery { llmFn(any()) } returns "PAGES:\n"

        val result = agent.ingestUrl("https://example.com")

        assertTrue(result.contains("이미 등록") || result.contains("duplicate"))
    }

    @Test fun `ingest empty LLM response saves to sources only`() = runBlocking {
        coEvery { llmFn(any()) } returns ""

        val result = agent.ingestText("짧은 텍스트")

        assertTrue(result.contains("일부 저장") || result.contains("sources"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.IngestAgentTest" 2>&1 | tail -20
```
Expected: FAIL — `IngestAgent` 클래스 없음

**Step 3: Write minimal implementation**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgent.kt
package io.github.veronikapj.wiki.knowledge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

class IngestAgent(
    private val store: KnowledgeStore,
    private val llmFn: suspend (String) -> String,
    private val chromaIndexFn: (suspend (String, String, String) -> Unit)? = null,
) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun ingestUrl(url: String): String {
        // 중복 감지: sources/ 디렉터리의 url: 필드 확인
        val sourceKey = urlToSourceKey(url)
        if (store.pageExists("sources/$sourceKey.md")) {
            return "이미 등록된 소스입니다: $url"
        }

        log.info("Fetching URL: {}", url)
        val rawText = runCatching {
            val html = httpClient.get(url).bodyAsText()
            extractText(html)
        }.getOrElse { e ->
            store.appendLog("ingest-error", "URL fetch 실패: $url — ${e.message}")
            return "URL 가져오기 실패: ${e.message}"
        }

        // sources/ 메타데이터 저장
        store.savePage(
            "sources/$sourceKey.md",
            "url: $url\n날짜: ${java.time.LocalDate.now()}\n요약: (컴파일 중)\n"
        )

        return compileAndSave(rawText, "sources/$sourceKey.md")
    }

    suspend fun ingestText(text: String): String {
        return compileAndSave(text, null)
    }

    private suspend fun compileAndSave(text: String, sourcePath: String?): String {
        val existingIndex = store.loadIndex() ?: ""
        val prompt = buildCompilePrompt(text, existingIndex)

        val llmOutput = runCatching { llmFn(prompt) }.getOrElse { e ->
            log.error("LLM compile failed", e)
            if (sourcePath != null) {
                store.appendLog("ingest-partial", "LLM 실패, sources만 저장: $sourcePath")
                return "일부 저장됨 (sources only): ${e.message}"
            }
            return "LLM 컴파일 실패: ${e.message}"
        }

        if (llmOutput.isBlank()) {
            store.appendLog("ingest-empty", "LLM 출력 없음, sources만 저장")
            return "일부 저장됨 (sources only)"
        }

        val savedPages = parsePagesFromLlmOutput(llmOutput)
        if (savedPages.isEmpty()) {
            store.appendLog("ingest-empty", "파싱된 페이지 없음")
            return "일부 저장됨 (sources only)"
        }

        savedPages.forEach { (path, content) ->
            store.savePage(path, content)
            chromaIndexFn?.invoke(path, content, path)
            val firstLine = content.lines().firstOrNull { it.startsWith("#") }?.removePrefix("#")?.trim() ?: path
            store.updateIndex(path, firstLine)
        }

        val count = store.incrementAndGetIngestCount()
        store.appendLog("ingest", "저장: ${savedPages.map { it.first }.joinToString(", ")}")

        return ":white_check_mark: ${savedPages.size}개 페이지 저장됨:\n" +
            savedPages.joinToString("\n") { "• ${it.first}" } +
            if (count % 10 == 0) "\n_자동 lint 추천: `/wiki lint`_" else ""
    }

    // LLM 출력 파싱: "PAGES:\npath/to/file.md:\n# 내용\n---\n" 형식
    private fun parsePagesFromLlmOutput(output: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val pageRegex = Regex("""((?:concepts|entities|sources)/[^\n:]+\.md):\s*\n([\s\S]*?)(?=(?:concepts|entities|sources)/[^\n:]+\.md:|$)""")
        pageRegex.findAll(output).forEach { match ->
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2].replace(Regex("^---\\s*$", RegexOption.MULTILINE), "").trim()
            if (path.isNotBlank() && content.isNotBlank()) {
                result += path to content
            }
        }
        return result
    }

    private fun buildCompilePrompt(text: String, existingIndex: String): String = buildString {
        appendLine("당신은 지식 컴파일러입니다. 주어진 텍스트를 분석해 마크다운 위키 페이지로 변환하세요.")
        appendLine()
        appendLine("기존 지식베이스 인덱스 (중복 방지용):")
        appendLine(existingIndex.take(1000).ifBlank { "(비어있음)" })
        appendLine()
        appendLine("규칙:")
        appendLine("1. 고유명사(사람·팀·시스템)는 entities/, 개념·프로세스는 concepts/ 에 저장")
        appendLine("2. 파일명은 한글 제목을 하이픈(-) 구분으로 (예: 배포-프로세스.md)")
        appendLine("3. 기존 인덱스에 유사 항목이 있으면 새 파일 대신 기존 파일 경로를 사용")
        appendLine("4. 각 페이지는 # 제목 + 내용 형식")
        appendLine()
        appendLine("출력 형식 (이 형식만 출력):")
        appendLine("PAGES:")
        appendLine("concepts/파일명.md:")
        appendLine("# 제목")
        appendLine("내용...")
        appendLine("---")
        appendLine()
        appendLine("변환할 텍스트:")
        appendLine(text.take(4000))
    }

    private fun extractText(html: String): String {
        // 태그 제거, 공백 정리
        return html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun urlToSourceKey(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .replace(Regex("[^a-zA-Z0-9가-힣]"), "-")
            .take(80)

    companion object {
        private val log = LoggerFactory.getLogger(IngestAgent::class.java)
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.IngestAgentTest" 2>&1 | tail -10
```
Expected: PASS — 3 tests green

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/IngestAgentTest.kt
git commit -m "feat: add IngestAgent for URL/text ingest with LLM compile"
```

---

## Task 3: LintAgent — 모순·고아 감지

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/LintAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/LintAgentTest.kt`

**Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/knowledge/LintAgentTest.kt
package io.github.veronikapj.wiki.knowledge

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class LintAgentTest {

    private val baseDir = "build/test-lint-${System.nanoTime()}"
    private val store = KnowledgeStore(baseDir)
    private val llmFn: suspend (String) -> String = mockk()
    private val agent = LintAgent(store, llmFn)

    @AfterEach fun cleanup() { File(baseDir).deleteRecursively() }

    @Test fun `lint returns no issues when store is empty`() = runBlocking {
        coEvery { llmFn(any()) } returns "이슈 없음"
        val result = agent.lint()
        assertTrue(result.contains("이슈") || result.isEmpty() || result.contains("없음"))
    }

    @Test fun `lint includes page content in llm prompt`() = runBlocking {
        store.savePage("concepts/a.md", "# A\n내용A")
        store.savePage("concepts/b.md", "# B\n내용B")
        var capturedPrompt = ""
        coEvery { llmFn(any()) } answers { capturedPrompt = firstArg(); "이슈 없음" }

        agent.lint()

        assertTrue(capturedPrompt.contains("내용A"))
        assertTrue(capturedPrompt.contains("내용B"))
    }

    @Test fun `lint returns llm analysis result`() = runBlocking {
        store.savePage("concepts/a.md", "# A\n내용")
        coEvery { llmFn(any()) } returns "모순: A 페이지와 B 페이지 충돌"

        val result = agent.lint()

        assertTrue(result.contains("모순") || result.contains("A 페이지"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.LintAgentTest" 2>&1 | tail -20
```
Expected: FAIL — `LintAgent` 없음

**Step 3: Write minimal implementation**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/knowledge/LintAgent.kt
package io.github.veronikapj.wiki.knowledge

import org.slf4j.LoggerFactory

class LintAgent(
    private val store: KnowledgeStore,
    private val llmFn: suspend (String) -> String,
) {
    suspend fun lint(): String {
        val pages = store.loadAll()
        if (pages.isEmpty()) return "지식베이스가 비어있습니다."

        log.info("Linting {} knowledge pages", pages.size)

        val allContent = pages.joinToString("\n\n---\n\n") { (path, content) ->
            "## $path\n$content"
        }

        val prompt = buildString {
            appendLine("당신은 위키 품질 검사 전문가입니다. 아래 지식베이스 페이지들을 분석하세요.")
            appendLine()
            appendLine("다음 항목을 검사하세요:")
            appendLine("1. 모순: 서로 다른 페이지에서 동일 사실에 대해 충돌하는 내용")
            appendLine("2. 고아: 어떤 페이지에서도 참조되지 않는 페이지")
            appendLine("3. 오래됨: 다른 페이지의 최신 내용과 충돌하는 오래된 클레임")
            appendLine()
            appendLine("출력 형식:")
            appendLine("각 이슈를 '유형: 설명 (관련 페이지)' 형식으로 줄바꿈으로 나열하세요.")
            appendLine("이슈가 없으면 '이슈 없음'으로 답하세요.")
            appendLine()
            appendLine("페이지 목록:")
            appendLine(allContent.take(8000))
        }

        val result = runCatching { llmFn(prompt) }.getOrElse { e ->
            log.error("Lint LLM failed", e)
            return "Lint LLM 오류: ${e.message}"
        }

        store.appendLog("lint", "완료 — ${pages.size}개 페이지 검사")
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(LintAgent::class.java)
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.LintAgentTest" 2>&1 | tail -10
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/LintAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/LintAgentTest.kt
git commit -m "feat: add LintAgent for knowledge base contradiction/orphan detection"
```

---

## Task 4: KnowledgeTool — @Tool 래퍼

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeTool.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeToolTest.kt`

**Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeToolTest.kt
package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class KnowledgeToolTest {

    private val baseDir = "build/test-knowledge-tool-${System.nanoTime()}"
    private val store = KnowledgeStore(baseDir)
    private val tool = KnowledgeTool(store)

    @AfterEach fun cleanup() { File(baseDir).deleteRecursively() }

    @Test fun `knowledgeSearch returns page content when keyword matches`() {
        store.savePage("concepts/배포-프로세스.md", "# 배포 프로세스\nJenkins로 배포합니다.")
        val result = tool.knowledgeSearch("배포")
        assertTrue(result.contains("배포 프로세스") || result.contains("Jenkins"))
    }

    @Test fun `knowledgeSearch returns not-found message when empty`() {
        val result = tool.knowledgeSearch("없는키워드xyz")
        assertTrue(result.contains("찾을 수 없습니다") || result.contains("없습니다"))
    }

    @Test fun `knowledgeSearch is case-insensitive for english terms`() {
        store.savePage("concepts/ci-cd.md", "# CI/CD\nGitHub Actions 사용")
        val result = tool.knowledgeSearch("ci")
        assertTrue(result.contains("CI") || result.contains("GitHub"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.KnowledgeToolTest" 2>&1 | tail -20
```
Expected: FAIL — `KnowledgeTool` 없음

**Step 3: Write minimal implementation**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeTool.kt
package io.github.veronikapj.wiki.knowledge

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.agent.tool.SourceTracker

class KnowledgeTool(
    private val store: KnowledgeStore,
    private val tracker: SourceTracker? = null,
) {

    @Tool("knowledgeSearch")
    @LLMDescription("로컬 지식베이스(ingest된 URL·텍스트)에서 키워드로 검색합니다. Confluence보다 먼저 검색하세요.")
    fun knowledgeSearch(
        @LLMDescription("검색할 키워드 또는 질문")
        query: String,
    ): String {
        tracker?.record("KnowledgeBase")
        val pages = store.loadAll()
        if (pages.isEmpty()) return "지식베이스가 비어있습니다."

        val terms = query.lowercase().split(" ", "　").filter { it.length >= 2 }
        val matched = pages.filter { (path, content) ->
            val target = (path + " " + content).lowercase()
            terms.any { target.contains(it) }
        }

        if (matched.isEmpty()) return "지식베이스에서 관련 내용을 찾을 수 없습니다."

        return matched.take(3).joinToString("\n\n---\n\n") { (path, content) ->
            "*[$path]*\n${content.take(500)}"
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.KnowledgeToolTest" 2>&1 | tail -10
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/KnowledgeToolTest.kt
git commit -m "feat: add KnowledgeTool @Tool wrapper for OrchestratorAgent"
```

---

## Task 5: SlackConfigHandler — /wiki ingest + /wiki lint 커맨드

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandlerTest.kt`

**Step 1: Write the failing test**

`SlackConfigHandlerTest.kt`에 아래 테스트를 추가한다:

```kotlin
// SlackConfigHandlerTest.kt에 추가할 테스트들
@Test fun `handle ingest URL triggers async ingest`() {
    var ingestedUrl: String? = null
    val handler = SlackConfigHandler(
        config = testConfig,
        asyncExecutor = { r -> r.run() },   // 동기 실행
        onIngest = { url -> ingestedUrl = url; ":white_check_mark: 저장 완료" },
    )
    val result = handler.handle("/wiki ingest https://example.com/page")
    assertTrue(result.contains("인덱싱") || result.contains("진행") || result.contains("시작"))
    assertEquals("https://example.com/page", ingestedUrl)
}

@Test fun `handle ingest without URL returns usage message`() {
    val handler = SlackConfigHandler(config = testConfig)
    val result = handler.handle("/wiki ingest")
    assertTrue(result.contains("사용법") || result.contains("URL"))
}

@Test fun `handle lint triggers async lint`() {
    var lintCalled = false
    val handler = SlackConfigHandler(
        config = testConfig,
        asyncExecutor = { r -> r.run() },
        onLint = { lintCalled = true; "이슈 없음" },
    )
    val result = handler.handle("/wiki lint")
    assertTrue(result.contains("lint") || result.contains("검사") || result.contains("진행"))
    assertTrue(lintCalled)
}
```

`SlackConfigHandler`에 `onIngest`/`onLint` 파라미터가 없으므로 처음에는 컴파일 오류로 실패한다.

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.slack.SlackConfigHandlerTest" 2>&1 | tail -20
```
Expected: FAIL — 컴파일 오류

**Step 3: Modify SlackConfigHandler**

`SlackConfigHandler.kt` 생성자에 두 파라미터 추가:

```kotlin
// 생성자 파라미터 추가
private val onIngest: (suspend (String) -> String)? = null,
private val onLint: (suspend () -> String)? = null,
```

`handle()` 함수의 `when` 블록에 두 케이스 추가:

```kotlin
parts.size >= 3 && parts[1] == "ingest" -> triggerIngest(parts[2])
parts.size >= 2 && parts[1] == "ingest" -> "사용법: /wiki ingest <URL>"
parts.size >= 2 && parts[1] == "lint" -> triggerLint()
```

새 private 함수 추가:

```kotlin
private fun triggerIngest(url: String): String {
    val fn = onIngest ?: return "Ingest 기능이 비활성화 상태입니다."
    asyncExecutor.execute {
        runCatching { runBlocking { fn(url) } }
            .onFailure { e -> log.error("Ingest failed: {}", url, e) }
    }
    return ":hourglass_flowing_sand: ingest를 시작했습니다: $url"
}

private fun triggerLint(): String {
    val fn = onLint ?: return "Lint 기능이 비활성화 상태입니다."
    asyncExecutor.execute {
        runCatching { runBlocking { fn() } }
            .onFailure { e -> log.error("Lint failed", e) }
    }
    return ":hourglass_flowing_sand: lint 검사를 시작했습니다."
}
```

`helpMessage()`에 새 커맨드 설명 추가:

```kotlin
:books: *지식베이스*
• `/wiki ingest <URL>` — URL을 지식베이스에 ingest
• `/wiki lint` — 지식베이스 품질 검사
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.slack.SlackConfigHandlerTest" 2>&1 | tail -10
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandlerTest.kt
git commit -m "feat: add /wiki ingest and /wiki lint commands to SlackConfigHandler"
```

---

## Task 6: SlackBotGateway — DM 자동 감지

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/slack/SlackBotGatewayTest.kt`

**Step 1: Write the failing test**

`SlackBotGatewayTest.kt`에 아래 테스트를 추가한다:

```kotlin
@Test fun `classifyDmInput detects URL`() {
    assertEquals(DmInputType.URL, classifyDmInput("https://example.com/page"))
    assertEquals(DmInputType.URL, classifyDmInput("http://blog.example.com"))
}

@Test fun `classifyDmInput detects long text`() {
    val longText = "가".repeat(500)
    assertEquals(DmInputType.LONG_TEXT, classifyDmInput(longText))
}

@Test fun `classifyDmInput returns NORMAL for short text`() {
    assertEquals(DmInputType.NORMAL, classifyDmInput("배포 절차가 뭐야?"))
}
```

`classifyDmInput`은 테스트 가능하도록 `internal fun`으로 작성한다.

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.slack.SlackBotGatewayTest" 2>&1 | tail -20
```
Expected: FAIL — `DmInputType`, `classifyDmInput` 없음

**Step 3: Modify SlackBotGateway**

파일 상단에 `DmInputType` enum 추가:

```kotlin
enum class DmInputType { URL, LONG_TEXT, NORMAL }

internal fun classifyDmInput(text: String): DmInputType = when {
    text.startsWith("http://") || text.startsWith("https://") -> DmInputType.URL
    text.length >= 500 -> DmInputType.LONG_TEXT
    else -> DmInputType.NORMAL
}
```

DM 처리 로직(`handleDm` 또는 해당 분기)에서 `classifyDmInput`을 호출해 URL은 `ingestAgent?.ingestUrl(text)`, LONG_TEXT는 확인 메시지(`suggestIngest`), NORMAL은 기존 검색으로 라우팅한다:

```kotlin
// DM 처리 분기 예시
when (classifyDmInput(userText)) {
    DmInputType.URL -> {
        val ingest = ingestAgent
        if (ingest != null) {
            val result = runBlocking { ingest.ingestUrl(userText) }
            slackClient.chatPostMessage { it.channel(channel).text(result) }
        } else {
            // ingest 미설정 — 기존 검색으로 처리
            handleSearch(channel, sessionId, userText, listener)
        }
    }
    DmInputType.LONG_TEXT -> {
        val msg = "긴 텍스트를 감지했습니다. 지식베이스에 저장할까요? 저장하려면 `/wiki ingest` 명령어를 사용하거나 텍스트를 그대로 DM에 붙여넣기하면 저장됩니다."
        slackClient.chatPostMessage { it.channel(channel).text(msg) }
    }
    DmInputType.NORMAL -> handleSearch(channel, sessionId, userText, listener)
}
```

기존 DM 처리 함수 시그니처에 `ingestAgent: IngestAgent? = null` 파라미터를 추가한다.

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.slack.SlackBotGatewayTest" 2>&1 | tail -10
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/SlackBotGatewayTest.kt
git commit -m "feat: DM auto-detection for URL ingest and long text suggestion"
```

---

## Task 7: OrchestratorAgent — KnowledgeTool 등록 및 검색 우선순위

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: Write the failing test**

`OrchestratorAgentTest.kt`에 아래 테스트를 추가한다:

```kotlin
@Test fun `knowledgeTool is used before confluenceTool`() {
    var toolOrder = mutableListOf<String>()
    val knowledgeTool = KnowledgeTool(mockk<KnowledgeStore>().also {
        every { it.loadAll() } returns listOf("concepts/배포.md" to "# 배포\n내용")
        toolOrder += "knowledge"
    })
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>().also {
        every { runBlocking { it.search(any(), any()) } } answers {
            toolOrder += "confluence"
            "Confluence 결과"
        }
    })
    // knowledgeTool이 있을 때 confluenceTool보다 먼저 호출되는지 확인
    // OrchestratorAgent의 availableTools 리스트 순서 검증
    val agent = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        executor = mockExecutor,
        useManualLoop = true,
    )
    // availableTools에서 knowledgeSearch가 confluenceSearch 앞에 오는지 확인
    // (실제 tool 호출은 LLM 결정에 따르므로, 여기서는 툴 등록 순서만 검증)
    assertTrue(agent.toolPriorities().indexOf("knowledgeSearch") < agent.toolPriorities().indexOf("confluenceSearch"))
}
```

`toolPriorities()`는 `internal fun`으로 툴 이름 순서를 반환하는 테스트용 함수.

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest" 2>&1 | tail -20
```
Expected: FAIL — `knowledgeTool` 파라미터 없음

**Step 3: Modify OrchestratorAgent**

생성자에 `knowledgeTool` 파라미터 추가 (optional):

```kotlin
private val knowledgeTool: KnowledgeTool? = null,
```

`require()` 조건 업데이트:

```kotlin
require(knowledgeTool != null || confluenceTool != null || githubWikiTool != null || vectorSearchTool != null) {
    "At least one tool must be enabled"
}
```

`answerWithManualLoop()`의 `availableTools` 리스트에서 `knowledgeSearch`를 **맨 앞**에 추가:

```kotlin
val availableTools = listOfNotNull(
    knowledgeTool?.let { "knowledgeSearch" },   // 항상 첫 번째
    confluenceTool?.let { "confluenceSearch" },
    githubWikiTool?.let { "githubWikiSearch" },
    vectorSearchTool?.let { "vectorSearch" },
)
```

`executeFromDecision()`의 `when` 블록에 케이스 추가:

```kotlin
"knowledgeSearch" -> knowledgeTool?.knowledgeSearch(query)
```

`executeDefault()`에도 `knowledgeSearch` 케이스 추가.

`buildAgent()`의 `ToolRegistry` 블록에 추가:

```kotlin
if (knowledgeTool != null) tool(knowledgeTool::knowledgeSearch)
```

시스템 프롬프트에 KnowledgeBase 우선 사용 안내 추가:

```kotlin
if (knowledgeTool != null) {
    appendLine("로컬 지식베이스(knowledgeSearch)에 먼저 검색하고, 없으면 다른 도구를 사용하세요.")
}
```

`internal fun toolPriorities()` 추가:

```kotlin
internal fun toolPriorities(): List<String> = listOfNotNull(
    knowledgeTool?.let { "knowledgeSearch" },
    confluenceTool?.let { "confluenceSearch" },
    githubWikiTool?.let { "githubWikiSearch" },
    vectorSearchTool?.let { "vectorSearch" },
)
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest" 2>&1 | tail -10
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: add KnowledgeTool to OrchestratorAgent with highest search priority"
```

---

## Task 8: Main.kt 와이어링

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

테스트 없음 (Main은 통합 진입점 — 컴파일 + 수동 실행 확인으로 검증)

**Step 1: Modify Main.kt**

`IngestAgent`, `LintAgent`, `KnowledgeStore`, `KnowledgeTool` import 추가.

기존 컴포넌트 생성 이후, `orchestrator` 생성 이전에 아래 블록 추가:

```kotlin
val knowledgeStore = KnowledgeStore()
val knowledgeLlmFn: suspend (String) -> String = { userPrompt ->
    executor.execute(prompt("knowledge") { user(userPrompt) }, model).joinToString("") { it.content }
}

// ChromaDB 인덱스 함수 (RAG 활성화 시)
val knowledgeChromaFn: (suspend (String, String, String) -> Unit)? = if (config.rag.enabled) {
    val chromaClient = ChromaClient(config.rag.chromaUrl)
    ;{ id, doc, _ ->
        val collectionId = chromaClient.getOrCreateCollection("knowledge_base")
        chromaClient.addDocuments(collectionId, listOf(id), listOf(doc))
    }
} else null

val ingestAgent = IngestAgent(knowledgeStore, knowledgeLlmFn, knowledgeChromaFn)
val lintAgent = LintAgent(knowledgeStore, knowledgeLlmFn)
val knowledgeTool = KnowledgeTool(knowledgeStore, sourceTracker)
```

`orchestrator` 생성 시 `knowledgeTool` 파라미터 추가:

```kotlin
val orchestrator = OrchestratorAgent(
    knowledgeTool = knowledgeTool,
    confluenceTool = confluenceTool,
    ...
)
```

`SlackConfigHandler` 생성 시 `onIngest`, `onLint` 파라미터 추가:

```kotlin
val configHandler = SlackConfigHandler(
    config = config,
    persistOnChange = true,
    onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
    onIngest = { url -> ingestAgent.ingestUrl(url) },
    onLint = { lintAgent.lint() },
    projectMemory = projectMemory,
)
```

`SlackBotGateway` 생성 시 `ingestAgent` 파라미터 추가:

```kotlin
val gateway = SlackBotGateway(
    ...,
    ingestAgent = ingestAgent,
)
```

**Step 2: Verify compilation**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

**Step 3: Run all tests**

```bash
./gradlew test 2>&1 | tail -20
```
Expected: 전체 테스트 PASS

**Step 4: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: wire KnowledgeStore/IngestAgent/LintAgent/KnowledgeTool in Main"
```

---

## Task 9: .gitignore + 비기능 마무리

**Files:**
- Modify: `.gitignore`

**Step 1: Add .wiki/knowledge/ to .gitignore**

`.gitignore`에 아래 줄 추가 (팀별 지식은 커밋 제외):

```
# Local knowledge base (team-specific, not committed)
.wiki/knowledge/
```

**Step 2: Verify**

```bash
echo "test" > .wiki/knowledge/test.md
git status 2>&1 | grep "wiki"
```
Expected: `.wiki/knowledge/test.md`가 `git status`에 나타나지 않아야 한다.

**Step 3: Clean up test file and commit**

```bash
rm -f .wiki/knowledge/test.md
git add .gitignore
git commit -m "chore: exclude .wiki/knowledge/ from git (team-local knowledge base)"
```

---

## 최종 확인

**전체 테스트 실행:**

```bash
./gradlew test 2>&1 | tail -30
```
Expected: 모든 테스트 PASS

**새로 추가된 테스트 클래스 확인:**
- `KnowledgeStoreTest` — 7개
- `IngestAgentTest` — 3개
- `LintAgentTest` — 3개
- `KnowledgeToolTest` — 3개
- `SlackConfigHandlerTest` — 기존 + 3개 추가
- `SlackBotGatewayTest` — 기존 + 3개 추가
- `OrchestratorAgentTest` — 기존 + 1개 추가
