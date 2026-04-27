package io.github.veronikapj.wiki.knowledge

import org.slf4j.LoggerFactory

class LintAgent(
    private val store: KnowledgeStore,
    private val llmFn: suspend (String) -> String,
) {
    suspend fun lint(): String {
        val pages = store.loadAll()
        if (pages.isEmpty()) return "지식베이스가 비어있습니다. `/wiki ingest <URL>`로 내용을 추가한 후 다시 시도하세요."

        log.info("Linting {} knowledge pages", pages.size)

        val allContent = pages.joinToString("\n\n---\n\n") { (path, content) ->
            "## $path\n$content"
        }

        val contentStr = allContent.take(8000)
        val truncationNotice = if (allContent.length > 8000)
            "\n[주의: 콘텐츠가 8000자로 잘렸습니다. 일부 페이지가 분석에서 제외됐을 수 있습니다.]"
        else ""

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
            appendLine(contentStr)
            if (truncationNotice.isNotEmpty()) appendLine(truncationNotice)
        }

        val result = runCatching { llmFn(prompt) }.getOrElse { e ->
            log.error("Lint LLM failed", e)
            store.appendLog("lint-error", "LLM 실패 — ${e.message}")
            return "Lint LLM 오류: ${e.message}"
        }

        store.appendLog("lint", "완료 — ${pages.size}개 페이지 검사")
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(LintAgent::class.java)
    }
}
