package io.github.veronikapj.wiki.slack

import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.config.SlackConfig
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class DmInputType { URL, LONG_TEXT, NORMAL }

internal fun classifyDmInput(text: String): DmInputType = when {
    text.startsWith("http://") || text.startsWith("https://") -> DmInputType.URL
    text.length >= 500 -> DmInputType.LONG_TEXT
    else -> DmInputType.NORMAL
}

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val projectMemory: ProjectMemory? = null,
    private val confluenceClient: ConfluenceClient? = null,
    private val ingestAgent: io.github.veronikapj.wiki.knowledge.IngestAgent? = null,
) {
    private val app = App()
    private val slackClient: MethodsClient = Slack.getInstance().methods(slackConfig.botToken)
    private val messageExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(20),
    )

    private val toolDisplayNames = mapOf(
        "confluenceSearch" to "Confluence",
        "githubWikiSearch" to "GitHub Wiki",
        "vectorSearch" to "RAG",
    )

    // 봇이 보낸 답변 메시지의 ts를 추적 (리액션 피드백 필터링용) — 최대 500개 유지
    private val botMessageTimestamps: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= MAX_BOT_MSG_CACHE) iterator().let { it.next(); it.remove() }
                return super.add(element)
            }
        }
    )

    // 온보딩 상태: 채널별 진행 단계
    private val onboardingState = ConcurrentHashMap<String, Int>()

    @Volatile
    private var onboardingDone: Boolean? = null
    private val needsOnboarding: Boolean get() {
        val cached = onboardingDone
        if (cached == true) return false
        val hasMem = projectMemory?.load() != null
        if (hasMem) onboardingDone = true
        return !hasMem
    }

    // 스페이스 목록 캐시 (온보딩 시 1회 조회)
    private var cachedSpaces: List<ConfluenceClient.SpaceInfo>? = null
    private fun getSpaces(): List<ConfluenceClient.SpaceInfo> {
        cachedSpaces?.let { return it }
        val spaces = confluenceClient?.let { runBlocking { it.listSpaces() } } ?: emptyList()
        cachedSpaces = spaces
        return spaces
    }

    private fun isOnboarding(channel: String): Boolean =
        needsOnboarding || onboardingState.containsKey(channel)

    private fun postToThread(channel: String, threadTs: String?, msg: String) {
        slackClient.chatPostMessage { req ->
            req.channel(channel).text(msg).let { b -> if (threadTs != null) b.threadTs(threadTs) else b }
        }
    }

    private fun handleOnboarding(channel: String, threadTs: String?, text: String) {
        val step = onboardingState.getOrPut(channel) { 0 }
        when (step) {
            0 -> {
                // Step 0: 팀/조직 질문
                val msg = buildString {
                    appendLine("안녕하세요! :wave: 위키 검색 봇입니다.")
                    appendLine("더 정확한 검색을 위해 몇 가지 알려주세요.")
                    appendLine()
                    appendLine("*1/4* 이 팀/조직의 이름과 역할은 무엇인가요?")
                    appendLine("예: _모바일 앱(iOS/Android) 개발팀, 프로덕트앱개발 트라이브_")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 1
            }
            1 -> {
                // Step 1: 팀 저장 + 도메인 용어 질문
                projectMemory?.add("팀/조직: $text")
                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*2/4* 자주 사용하는 도메인 용어 중 일반적 의미와 다른 것이 있나요?")
                    appendLine("예: _클라이언트 = 모바일 앱(고객이 아님), PR = Pull Request_")
                    appendLine("없으면 '없음'이라고 답해주세요.")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 2
            }
            2 -> {
                // Step 2: 도메인 용어 저장 + 검색 대상 질문
                if (text != "없음") projectMemory?.add("도메인 용어: $text")
                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*3/4* 주로 어떤 정보를 검색하실 건가요?")
                    appendLine("예: _온보딩 가이드, 배포 프로세스, 기술 문서, 회의록_")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 3
            }
            3 -> {
                // Step 3: 검색 대상 저장 + 스페이스 선택 (팀 정보 기반 추천)
                projectMemory?.add("주요 검색 대상: $text")
                val allSpaces = getSpaces()
                val memoryText = projectMemory?.load()?.lowercase() ?: ""
                val keywords = memoryText.split(" ", ",", "/", "(", ")", "\n", "-", "=")
                    .map { it.trim() }.filter { it.length >= 2 }.toSet()

                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*4/4* 검색할 Confluence 스페이스를 선택해주세요.")
                    if (allSpaces.isNotEmpty()) {
                        // collaboration 타입 중 팀 정보와 매칭되는 것 = 추천
                        val collabSpaces = allSpaces.filter { it.type == "collaboration" }
                        val recommended = collabSpaces.filter { space ->
                            val lower = (space.key + " " + space.name).lowercase()
                            keywords.any { lower.contains(it) }
                        }
                        val otherCollab = collabSpaces - recommended.toSet()

                        if (recommended.isNotEmpty()) {
                            appendLine()
                            appendLine(":star: 추천 (팀 정보 기반):")
                            recommended.forEach { appendLine("• `${it.key}` — ${it.name}") }
                        }
                        if (otherCollab.isNotEmpty()) {
                            appendLine()
                            appendLine("기타 팀 스페이스:")
                            otherCollab.forEach { appendLine("• `${it.key}` — ${it.name}") }
                        }
                        appendLine()
                        appendLine("추가할 스페이스 키를 쉼표로 구분해 입력하세요.")
                        appendLine("_위 목록 외 글로벌 스페이스도 키를 직접 입력하면 추가됩니다._")
                    } else {
                        appendLine("스페이스 목록을 불러올 수 없습니다. 직접 입력해주세요.")
                        appendLine("예: _ProductApp, ClientDivision_")
                    }
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 4
            }
            4 -> {
                // Step 4: 스페이스 저장 + 완료
                val selectedSpaces = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val allKeys = getSpaces().map { it.key }.toSet()
                val valid = selectedSpaces.filter { it in allKeys }
                val invalid = selectedSpaces - valid.toSet()
                if (invalid.isNotEmpty()) {
                    val msg = ":warning: 다음 스페이스 키가 유효하지 않습니다: ${invalid.joinToString(", ")}\n다시 입력해주세요."
                    postToThread(channel, threadTs, msg)
                    return // step 4 유지, 재입력 대기
                }
                if (valid.isNotEmpty()) {
                    projectMemory?.add("검색 스페이스: ${valid.joinToString(", ")}")
                }
                val msg = buildString {
                    appendLine("설정 완료! :tada: 스페이스: ${selectedSpaces.joinToString(", ")}")
                    appendLine()
                    appendLine("이제 질문하시면 Confluence에서 검색해 답변드립니다.")
                    appendLine("이 스레드에서 바로 질문하거나, 채널에서 멘션해주세요.")
                    appendLine()
                    appendLine("설정은 `/wiki memory show`로 확인, `/wiki memory add <내용>`으로 추가할 수 있습니다.")
                }
                postToThread(channel, threadTs, msg)
                onboardingState.remove(channel)
                onboardingDone = true
                log.info("Onboarding completed for channel={}", channel)
            }
        }
    }

    fun start() {
        registerMentionHandler()
        registerDmHandler()
        registerReactionHandler()
        registerSlashCommand()
        log.info("Starting Slack bot (Socket Mode)...")
        SocketModeApp(slackConfig.appToken, app).start()
    }

    private fun registerMentionHandler() {
        app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
            val query = extractQuery(payload.event.text)
            val channel = payload.event.channel
            val threadTs = payload.event.ts
            log.info("Mention received: '{}'", query)
            if (isHelpQuery(query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(configHandler.helpMessage()) }
            } else if (isOnboarding(channel)) {
                log.info("Onboarding step for channel={}", channel)
                runCatching { handleOnboarding(channel, threadTs, query) }
                    .onFailure { log.error("Onboarding error: {}", it.message, it) }
            } else if (!handleQueryAsync(channel = channel, threadTs = threadTs, sessionId = threadTs, query = query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
            }
            ctx.ack()
        }
    }

    private fun registerDmHandler() {
        app.event(com.slack.api.model.event.MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (event.channelType != "im" || event.botId != null || event.subtype != null) {
                return@event ctx.ack()
            }
            val query = extractQuery(event.text?.trim() ?: return@event ctx.ack())
            if (query.isBlank()) return@event ctx.ack()
            val channel = event.channel
            log.info("DM received: '{}'", query)
            if (isOnboarding(channel)) {
                runCatching { handleOnboarding(channel, null, query) }
                    .onFailure { log.error("Onboarding error: {}", it.message, it) }
            } else {
                when (classifyDmInput(query)) {
                    DmInputType.URL -> {
                        val ingest = ingestAgent
                        if (ingest != null) {
                            messageExecutor.submit {
                                runCatching {
                                    val result = runBlocking { ingest.ingestUrl(query) }
                                    slackClient.chatPostMessage { it.channel(channel).text(result) }
                                }.onFailure { e ->
                                    log.error("Ingest URL failed: {}", e.message, e)
                                    slackClient.chatPostMessage { it.channel(channel).text("URL ingest 실패: ${e.message}") }
                                }
                            }
                        } else {
                            if (!handleQueryAsync(channel = channel, threadTs = null, sessionId = "dm-$channel", query = query)) {
                                slackClient.chatPostMessage { it.channel(channel).text("요청이 많아 잠시 후 다시 시도해주세요.") }
                            }
                        }
                    }
                    DmInputType.LONG_TEXT -> {
                        slackClient.chatPostMessage {
                            it.channel(channel).text("긴 텍스트를 감지했습니다. `/wiki ingest <URL>` 명령어로 URL을 지식베이스에 저장할 수 있습니다.")
                        }
                    }
                    DmInputType.NORMAL -> {
                        if (!handleQueryAsync(channel = channel, threadTs = null, sessionId = "dm-$channel", query = query)) {
                            slackClient.chatPostMessage { it.channel(channel).text("요청이 많아 잠시 후 다시 시도해주세요.") }
                        }
                    }
                }
            }
            ctx.ack()
        }
    }

    private fun handleQueryAsync(channel: String, threadTs: String?, sessionId: String, query: String): Boolean {
        return try {
        messageExecutor.submit {
            var progressMessageTs: String? = null
            val searchedTools = mutableListOf<String>()

            val listener = object : SearchProgressListener {
                override suspend fun onSearchStarted(toolName: String) {
                    searchedTools.add(toolName)
                    val displayName = toolDisplayNames[toolName] ?: toolName
                    val msg = ":mag: $displayName 검색 중..."
                    try {
                        if (progressMessageTs == null) {
                            val response = slackClient.chatPostMessage { req ->
                                req.channel(channel).text(msg).let { b ->
                                    if (threadTs != null) b.threadTs(threadTs) else b
                                }
                            }
                            progressMessageTs = response.ts
                        } else {
                            slackClient.chatUpdate { req ->
                                req.channel(channel).ts(progressMessageTs).text(msg)
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to send progress message: {}", e.message)
                    }
                }

                override suspend fun onSearchCompleted(toolName: String) {}
            }

            try {
                val result = runBlocking { orchestrator.answer(query, listener, sessionId = sessionId) }

                progressMessageTs?.let { ts ->
                    runCatching { slackClient.chatDelete { it.channel(channel).ts(ts) } }
                }

                val footer = buildString {
                    if (searchedTools.isNotEmpty()) {
                        append("\uD83D\uDCCB ")
                        append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
                    }
                    append("\n$FEEDBACK_GUIDE")
                }
                val finalText = "$result\n\n$footer"

                val sendResult = slackClient.chatPostMessage { req ->
                    req.channel(channel).text(finalText).let { b ->
                        if (threadTs != null) b.threadTs(threadTs) else b
                    }
                }
                if (sendResult.isOk) {
                    log.info("Reply sent to channel={} thread={}", channel, threadTs)
                    sendResult.ts?.let { botMessageTimestamps.add(it) }
                } else {
                    log.error("Slack send failed: error={}, needed={}", sendResult.error, sendResult.needed)
                }
            } catch (e: Exception) {
                log.error("Failed to process query: {}", e.message, e)
                slackClient.chatPostMessage { req ->
                    req.channel(channel).text("오류가 발생했습니다: ${e.message}").let { b ->
                        if (threadTs != null) b.threadTs(threadTs) else b
                    }
                }
            }
        }
        true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            log.warn("Request queue full — rejected: channel={} thread={}", channel, threadTs)
            false
        }
    }

    private fun registerReactionHandler() {
        app.event(com.slack.api.model.event.ReactionAddedEvent::class.java) { payload, ctx ->
            val event = payload.event
            val reaction = event.reaction
            val messageTs = event.item.ts
            if (reaction in FEEDBACK_REACTIONS && messageTs in botMessageTimestamps) {
                log.info(
                    "Feedback received: reaction={}, user={}, channel={}, ts={}",
                    reaction, event.user, event.item.channel, messageTs,
                )
            }
            ctx.ack()
        }
    }

    private fun registerSlashCommand() {
        app.command("/wiki") { req, ctx ->
            val fullCommand = "/wiki ${req.payload.text}"
            val result = configHandler.handle(fullCommand)
            ctx.ack(result)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackBotGateway::class.java)
        private const val MAX_BOT_MSG_CACHE = 500

        const val FEEDBACK_GUIDE = ":thumbsup: 도움이 됐다면 | :thumbsdown: 아쉬웠다면 리액션을 남겨주세요"
        val FEEDBACK_REACTIONS = listOf("+1", "-1", "thumbsup", "thumbsdown")
        private val HELP_KEYWORDS = setOf("도움말", "사용법", "help", "도움", "사용방법")

        fun isHelpQuery(text: String): Boolean =
            text.trim().lowercase() in HELP_KEYWORDS

        fun extractQuery(text: String): String =
            text.replace(Regex("<@[A-Z0-9]+[^>]*>"), "")
                .replace(Regex("\\*Sent using\\*.*$"), "")
                .trim()
    }
}
