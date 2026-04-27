package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.WikiConfig
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor

class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onReindex: (suspend () -> Int)? = null,
    private val onIngest: (suspend (String) -> String)? = null,
    private val onLint: (suspend () -> String)? = null,
    private val projectMemory: ProjectMemory? = null,
    private val asyncExecutor: Executor = Executor { r -> Thread(r).also { it.isDaemon = true }.start() },
) {
    private var lastIndexTime: LocalDateTime? = null
    private var lastIndexCount: Int = 0

    fun currentConfig(): WikiConfig = config

    fun handle(command: String): String {
        val parts = command.trim().split(" ")
        return when {
            parts.size >= 2 && parts[1] == "help" -> helpMessage()
            parts.size >= 2 && parts[1] == "memory" -> handleMemory(parts.drop(2))
            parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
                val arg = parts.getOrNull(3)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(parts.drop(3).joinToString(" "))
            }
            parts.size >= 2 && parts[1] == "reindex" && parts.getOrNull(2) == "status" ->
                reindexStatus()
            parts.size >= 2 && parts[1] == "reindex" ->
                triggerReindex()
            parts.size >= 3 && parts[1] == "ingest" -> triggerIngest(parts[2])
            parts.size >= 2 && parts[1] == "ingest" -> "사용법: /wiki ingest <URL>"
            parts.size >= 2 && parts[1] == "lint" -> triggerLint()
            else -> helpMessage()
        }
    }

    private fun triggerReindex(): String {
        val indexer = onReindex ?: return "RAG가 비활성화 상태입니다. config.yml에서 rag.enabled=true로 설정하세요."
        // 비동기 실행 — 슬래시 커맨드 3초 타임아웃 방지
        asyncExecutor.execute {
            runCatching {
                val count = runBlocking { indexer() }
                lastIndexCount = count
                lastIndexTime = LocalDateTime.now()
                log.info("Reindex completed: {} pages", count)
            }.onFailure { e ->
                log.error("Reindex failed", e)
            }
        }
        return ":hourglass_flowing_sand: 인덱싱을 시작했습니다. `/wiki reindex status`로 진행 상황을 확인하세요."
    }

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

    private fun reindexStatus(): String {
        val time = lastIndexTime?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            ?: "아직 인덱싱하지 않았습니다"
        return "마지막 인덱싱: $time / 문서 수: $lastIndexCount"
    }

    private fun setSpaces(spacesArg: String): String {
        val newSpaces = spacesArg.split(",").map { it.trim() }.filter { it.isNotBlank() }
        config = config.copy(confluence = config.confluence.copy(spaces = newSpaces))
        if (persistOnChange) ConfigLoader.save(config, configPath)
        log.info("Confluence spaces updated: {}", newSpaces)
        return "검색 범위 업데이트: ${newSpaces.joinToString(", ")}"
    }

    private fun showSpaces(): String {
        val spaces = config.confluence.spaces
        return if (spaces.isEmpty()) "현재 설정된 스페이스가 없습니다."
        else "현재 검색 스페이스: ${spaces.joinToString(", ")}"
    }

    private fun handleMemory(args: List<String>): String {
        val mem = projectMemory ?: return "프로젝트 메모리가 비활성화 상태입니다."
        return when (args.firstOrNull()) {
            "add" -> {
                val content = args.drop(1).joinToString(" ").trim()
                if (content.isBlank()) return "사용법: /wiki memory add <내용>"
                mem.add(content)
                "메모리 저장 완료: $content"
            }
            "show" -> mem.show()
            "clear" -> {
                mem.clear()
                "프로젝트 메모리 초기화 완료"
            }
            else -> "사용법: /wiki memory add|show|clear"
        }
    }

    fun helpMessage() = """
        *위키 검색 봇 사용법*

        :mag: *검색*
        • `@wiki <질문>` — 채널에서 멘션으로 검색
        • `/wiki <질문>` — 슬래시 커맨드로 검색

        :gear: *설정*
        • `/wiki config space DEV,PM` — 검색 스페이스 설정
        • `/wiki config space show` — 현재 설정 확인
        • `/wiki reindex` — RAG 재인덱싱
        • `/wiki reindex status` — 마지막 인덱싱 정보

        :brain: *프로젝트 메모리*
        • `/wiki memory add <내용>` — 프로젝트 정보 저장 (도메인 용어, 팀 정보 등)
        • `/wiki memory show` — 저장된 프로젝트 정보 확인
        • `/wiki memory clear` — 프로젝트 정보 초기화

        :books: *지식베이스*
        • `/wiki ingest <URL>` — URL을 지식베이스에 ingest
        • `/wiki lint` — 지식베이스 품질 검사

        :bulb: *도움말*
        • `@wiki 도움말` 또는 `/wiki help`
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
