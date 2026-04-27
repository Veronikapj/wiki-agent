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
        val root = File(baseDir).canonicalFile
        require(file.canonicalFile.startsWith(root)) { "Path traversal rejected: $relativePath" }
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
    fun loadAll(): List<Pair<String, String>> = lock.withLock {
        val root = File(baseDir)
        if (!root.exists()) return@withLock emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { it.name != "index.md" && it.name != "log.md" }
            .map { file ->
                val rel = file.relativeTo(root).path
                rel to file.readText()
            }.toList()
    }

    fun pageExists(relativePath: String): Boolean = lock.withLock {
        val file = File("$baseDir/$relativePath")
        val root = File(baseDir).canonicalFile
        if (!file.canonicalFile.startsWith(root)) return@withLock false
        file.exists()
    }

    fun incrementAndGetIngestCount(): Int = ingestCount.incrementAndGet()

    fun loadIndex(): String? = lock.withLock {
        val f = File("$baseDir/index.md")
        if (f.exists()) f.readText() else null
    }
}
