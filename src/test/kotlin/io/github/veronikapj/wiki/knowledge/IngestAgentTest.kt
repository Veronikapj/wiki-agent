package io.github.veronikapj.wiki.knowledge

import io.mockk.coEvery
import io.mockk.mockk
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

    @AfterEach fun cleanup() {
        agent.close()
        File(baseDir).deleteRecursively()
    }

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
        val url = "https://example.com"
        val key = agent.urlToSourceKey(url)
        store.savePage("sources/$key.md", "url: $url\n날짜: 2024-01")
        coEvery { llmFn(any()) } returns "PAGES:\n"

        val result = agent.ingestUrl(url)

        assertTrue(result.contains("이미 등록") || result.contains("duplicate"))
    }

    @Test fun `ingest empty LLM response saves to sources only`() = runBlocking {
        coEvery { llmFn(any()) } returns ""

        val result = agent.ingestText("짧은 텍스트")

        assertTrue(result.contains("일부 저장") || result.contains("sources"))
    }
}
