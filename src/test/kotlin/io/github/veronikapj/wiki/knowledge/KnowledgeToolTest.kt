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

    @Test fun `knowledgeSearch returns not-found message when no pages match`() {
        store.savePage("concepts/배포-프로세스.md", "# 배포 프로세스\n내용입니다.")
        val result = tool.knowledgeSearch("없는키워드xyz")
        assertTrue(result.contains("찾을 수 없습니다") || result.contains("없습니다"))
    }

    @Test fun `knowledgeSearch is case-insensitive for english terms`() {
        store.savePage("concepts/ci-cd.md", "# CI/CD\nGitHub Actions 사용")
        val result = tool.knowledgeSearch("ci")
        assertTrue(result.contains("CI") || result.contains("GitHub"))
    }
}
