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
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    fun close() = httpClient.close()

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
        val pageRegex = Regex("""((?:concepts|entities|sources)/[^/\n:]+\.md):\s*\n([\s\S]*?)(?=(?:concepts|entities|sources)/[^\n:]+\.md:|$)""")
        pageRegex.findAll(output).forEach { match ->
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2].replace(Regex("^---\\s*$", RegexOption.MULTILINE), "").trim()
            if (path.isNotBlank() && content.isNotBlank() && !path.contains("..") && !path.startsWith("/")) {
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

    internal fun urlToSourceKey(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .replace(Regex("[^a-zA-Z0-9가-힣]"), "-")
            .take(80)

    companion object {
        private val log = LoggerFactory.getLogger(IngestAgent::class.java)
    }
}
