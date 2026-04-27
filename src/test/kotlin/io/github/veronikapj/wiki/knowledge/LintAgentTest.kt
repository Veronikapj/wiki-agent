package io.github.veronikapj.wiki.knowledge

import io.mockk.coEvery
import io.mockk.coVerify
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
        val result = agent.lint()
        assertTrue(result.contains("비어있습니다") || result.contains("없음"))
        coVerify(exactly = 0) { llmFn(any()) }
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

    @Test fun `lint returns error string when llm throws`() = runBlocking {
        store.savePage("concepts/a.md", "# A\n내용")
        coEvery { llmFn(any()) } throws RuntimeException("timeout")

        val result = agent.lint()

        assertTrue(result.contains("오류") || result.contains("timeout"))
    }
}
