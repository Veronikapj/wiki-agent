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

    @Test fun `loadIndex returns null when no index exists`() {
        assertNull(store.loadIndex())
    }

    @Test fun `loadIndex returns content after updateIndex`() {
        store.updateIndex("concepts/a.md", "A")
        assertNotNull(store.loadIndex())
        assertTrue(store.loadIndex()!!.contains("concepts/a.md"))
    }
}
