@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.github.veronikapj.wiki.context.ConversationStore
import io.github.veronikapj.wiki.context.ProjectMemory
import io.github.veronikapj.wiki.context.Turn
import org.slf4j.LoggerFactory

class OrchestratorAgent(
    private val knowledgeTool: KnowledgeTool? = null,
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val useManualLoop: Boolean = false,
    private val conversationStore: ConversationStore? = null,
    private val projectMemory: ProjectMemory? = null,
) {
    init {
        require(knowledgeTool != null || confluenceTool != null || githubWikiTool != null || vectorSearchTool != null) {
            "At least one tool must be enabled"
        }
    }

    suspend fun answer(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        log.info("OrchestratorAgent answering: '{}'", question)
        return if (useManualLoop) answerWithManualLoop(question, listener, sessionId)
        else answerWithKoogAgent(question, listener, sessionId)
    }

    // In-memory history fallback when no conversationStore/sessionId
    private val history = ArrayDeque<Pair<String, String>>()

    private suspend fun answerWithManualLoop(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        val contextHistory: List<Turn> = if (sessionId != null && conversationStore != null) {
            conversationStore.load(sessionId)
        } else {
            history.map { (q, a) -> Turn(q, a) }
        }

        val availableTools = listOfNotNull(
            knowledgeTool?.let { "knowledgeSearch" },   // highest priority — always first
            confluenceTool?.let { "confluenceSearch" },
            githubWikiTool?.let { "githubWikiSearch" },
            vectorSearchTool?.let { "vectorSearch" },
        )
        val model = AnthropicModels.Haiku_4_5

        val memory = projectMemory?.load()

        // 1단계: 검색어 결정 (항상 검색 — 예외 없음)
        val decisionPrompt = buildString {
            memory?.let {
                appendLine("프로젝트 정보:")
                appendLine(it)
                appendLine()
            }
            if (contextHistory.isNotEmpty()) {
                appendLine("이전 대화:")
                contextHistory.forEach { t -> appendLine("Q: ${t.question}\nA: ${t.answer.take(150)}...") }
                appendLine()
            }
            appendLine("당신은 검색 라우터입니다. 사용자의 질문을 분석해 검색어를 생성합니다.")
            appendLine("SYNONYMS는 프로젝트 정보의 도메인 맥락에 맞는 동의어를 생성하세요.")
            appendLine("사용 가능한 도구: ${availableTools.joinToString(", ")}")
            appendLine()
            appendLine("출력 형식 (이 세 줄만 출력, 다른 텍스트 금지):")
            appendLine("TOOL: <도구이름>")
            appendLine("QUERY: <핵심 검색어>")
            appendLine("SYNONYMS: <동의어/유사 표현 2-3개, 쉼표 구분>")
            appendLine()
            appendLine("규칙:")
            appendLine("- 어떤 질문이든 반드시 검색해야 합니다.")
            appendLine("- QUERY는 핵심 키워드만 간결하게.")
            appendLine("- SYNONYMS에 같은 의미의 다른 표현을 포함하세요. 예: 신입 온보딩 → 신규 입사자, 입사 가이드, 온보딩 체크리스트")
            appendLine("- TOOL, QUERY, SYNONYMS 세 줄만 출력하세요.")
            appendLine()
            appendLine("질문: $question")
        }

        val decision = executor.execute(
            prompt("decision") { user(decisionPrompt) }, model
        ).joinToString("") { it.content }.trim()

        log.info("Search decision: {}", decision.take(150))

        // 2단계: tool 실행 (파싱 실패 시 기본 도구로 원본 질문 검색)
        val toolName = Regex("TOOL:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
        if (toolName != null) listener?.onSearchStarted(toolName)
        var searchResult = runCatching { executeFromDecision(decision) }.getOrNull()
        if (toolName != null) listener?.onSearchCompleted(toolName)

        // RAG fallback: CQL 결과 없고 vectorSearch 가능하면 시도
        val vst = vectorSearchTool
        if (searchResult == null && vst != null && toolName != "vectorSearch") {
            log.info("CQL result empty, falling back to RAG")
            listener?.onSearchStarted("vectorSearch")
            searchResult = runCatching { vst.vectorSearch(question) }.getOrNull()
                ?.takeIf { !it.contains("찾을 수 없습니다") && !it.contains("타임아웃") }
            listener?.onSearchCompleted("vectorSearch")
        }

        // Final fallback: 모든 도구로 원본 질문 검색
        if (searchResult == null) {
            searchResult = runCatching { executeDefault(question, availableTools) }.getOrNull()
        }

        log.info("Search result: {}", searchResult?.take(100) ?: "none")

        // 3단계: 검색 결과 + 히스토리로 최종 답변
        val summaryPrompt = buildString {
            appendLine("당신은 회사 내부 위키 검색 봇입니다. 검색 결과를 바탕으로 사용자의 질문에 답변합니다.")
            appendLine("당신은 AI 어시스턴트, 코딩 도구, 개발 환경이 아닙니다. 세션, 브랜치, 코드 관련 대화를 하지 마세요.")
            appendLine()
            memory?.let {
                appendLine("프로젝트 정보:")
                appendLine(it)
                appendLine()
            }
            if (contextHistory.isNotEmpty()) {
                appendLine("이전 대화:")
                contextHistory.forEach { t -> appendLine("Q: ${t.question}\nA: ${t.answer.take(200)}...") }
                appendLine()
            }
            appendLine("질문: $question")
            appendLine()
            if (searchResult != null) {
                appendLine("검색 결과:")
                appendLine(searchResult)
                appendLine()
                appendLine("위 검색 결과만을 바탕으로 답변하세요.")
                appendLine()
                appendLine(buildAnswerGuidelines(verbose = true))
            } else {
                appendLine("검색 결과가 없습니다. '관련 문서를 찾지 못했습니다'라고 답변하세요.")
                appendLine("검색 대상: Confluence 스페이스. 질문을 다르게 표현하면 찾을 수도 있다고 안내하세요.")
            }
        }

        val answer = executor.execute(
            prompt("summary") { user(summaryPrompt) }, model
        ).joinToString("") { it.content }

        if (sessionId != null && conversationStore != null) {
            conversationStore.append(sessionId, question, answer)
            val summarizer: suspend (String) -> String = { p ->
                executor.execute(prompt("compress") { user(p) }, model).joinToString("") { it.content }
            }
            conversationStore.compress(sessionId, summarizer)
        } else {
            history.addLast(question to answer)
            if (history.size > 5) history.removeFirst()
        }

        return answer
    }

    private fun executeFromDecision(decision: String): String? {
        val toolMatch = Regex("TOOL:\\s*(\\S+)").find(decision) ?: return null
        val queryMatch = Regex("QUERY:\\s*(.+)").find(decision) ?: return null
        val toolName = toolMatch.groupValues[1].trim()
        val query = queryMatch.groupValues[1].trim()
        val synonyms = Regex("SYNONYMS:\\s*(.+)").find(decision)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        if (query.isBlank()) return null

        log.info("Executing tool: {} query: {} synonyms: {}", toolName, query, synonyms)

        // Single call — synonyms are now handled inside CQL OR clause
        val result = runCatching {
            when (toolName) {
                "knowledgeSearch" -> knowledgeTool?.knowledgeSearch(query)
                "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(query)
                "confluenceSearch" -> confluenceTool?.confluenceSearch(query, synonyms)
                "vectorSearch" -> vectorSearchTool?.vectorSearch(query)
                else -> null
            }
        }.getOrNull()

        return result?.takeIf { !it.contains("찾을 수 없습니다") }
    }

    private fun executeDefault(question: String, availableTools: List<String>): String? {
        log.info("Fallback: searching all available tools with original question: {}", availableTools)
        val results = availableTools.mapNotNull { tool ->
            runCatching {
                when (tool) {
                    "knowledgeSearch" -> knowledgeTool?.knowledgeSearch(question)
                    "confluenceSearch" -> confluenceTool?.confluenceSearch(question)
                    "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(question)
                    "vectorSearch" -> vectorSearchTool?.vectorSearch(question)
                    else -> null
                }
            }.getOrNull()
        }.filter { !it.contains("찾을 수 없습니다") }
        return if (results.isNotEmpty()) results.joinToString("\n\n") else null
    }

    // API 키 사용 시: Koog AIAgent의 네이티브 tool calling 루프
    private suspend fun answerWithKoogAgent(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        val contextHistory: List<Turn> = if (sessionId != null && conversationStore != null) {
            conversationStore.load(sessionId)
        } else emptyList()

        val summary = if (sessionId != null && conversationStore != null) {
            conversationStore.loadSummary(sessionId)
        } else null

        val memory = projectMemory?.load()

        val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
        for ((index, model) in fallbackModels.withIndex()) {
            val result = runCatching { buildAgent(model, contextHistory, listener, summary, memory).run(question) }
            val ex = result.exceptionOrNull()
            if (ex == null) {
                val answer = result.getOrThrow()
                if (sessionId != null && conversationStore != null) {
                    conversationStore.append(sessionId, question, answer)
                    val summarizer: suspend (String) -> String = { p ->
                        executor.execute(prompt("compress") { user(p) }, model).joinToString("") { it.content }
                    }
                    conversationStore.compress(sessionId, summarizer)
                }
                return answer
            }
            if (index < fallbackModels.lastIndex) {
                log.warn("Retrying with {}", fallbackModels[index + 1].id)
                continue
            }
            log.error("All models failed: {}", ex.message)
            return "검색 중 오류가 발생했습니다: ${ex.message}"
        }
        error("unreachable")
    }

    private fun buildAgent(
        model: LLModel,
        history: List<Turn> = emptyList(),
        listener: SearchProgressListener? = null,
        summary: String? = null,
        memory: String? = null,
    ): AIAgent<String, String> {
        val systemPrompt = buildString {
            val sources = listOfNotNull(
                if (knowledgeTool != null) "로컬 지식베이스" else null,
                if (confluenceTool != null) "Confluence 위키" else null,
                if (githubWikiTool != null) "GitHub Wiki" else null,
                if (vectorSearchTool != null) "벡터 검색(RAG)" else null,
            )
            appendLine("당신은 ${sources.joinToString("와 ")} 검색 전문가입니다.")
            appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
            appendLine("검색 없이 직접 답변하지 마세요.")
            if (knowledgeTool != null) {
                appendLine("로컬 지식베이스(knowledgeSearch)에 먼저 검색하고, 결과가 없으면 다른 도구를 사용하세요.")
            }
            if (confluenceTool != null && vectorSearchTool != null) {
                appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
            }
            if (githubWikiTool != null) {
                appendLine("기술 문서나 코드 관련 질문은 githubWikiSearch도 사용하세요.")
            }
            appendLine("검색 결과를 바탕으로 답변하세요.")
            appendLine()
            appendLine(buildAnswerGuidelines(verbose = false))
            memory?.let {
                appendLine()
                appendLine("# 프로젝트 정보")
                appendLine(it)
            }
            summary?.let {
                appendLine()
                appendLine("# 이전 대화 요약")
                appendLine(it)
            }
        }

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                    system(systemPrompt)
                    for (turn in history) {
                        user(turn.question)
                        assistant(turn.answer)
                    }
                },
                model = model,
                maxAgentIterations = 10,
            ),
            toolRegistry = ToolRegistry {
                if (knowledgeTool != null) tool(knowledgeTool::knowledgeSearch)
                if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
                if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
                if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
            },
            installFeatures = {
                if (listener != null) install(SearchProgressFeature(listener))
            },
        )
    }

    private class SearchProgressFeature(
        private val listener: SearchProgressListener,
    ) : AIAgentGraphFeature<SearchProgressFeature.Config, Unit> {

        class Config : FeatureConfig()

        override val key: AIAgentStorageKey<Unit> = AIAgentStorageKey("wiki-search-progress-${System.nanoTime()}")

        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config()

        override fun install(config: Config, pipeline: AIAgentGraphPipeline): Unit {
            pipeline.interceptToolCallStarting(this) { ctx: ToolCallStartingContext ->
                listener.onSearchStarted(ctx.toolName)
            }
            pipeline.interceptToolCallCompleted(this) { ctx: ToolCallCompletedContext ->
                listener.onSearchCompleted(ctx.toolName)
            }
        }
    }

    internal fun toolPriorities(): List<String> = listOfNotNull(
        knowledgeTool?.let { "knowledgeSearch" },
        confluenceTool?.let { "confluenceSearch" },
        githubWikiTool?.let { "githubWikiSearch" },
        vectorSearchTool?.let { "vectorSearch" },
    )

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)

        fun buildAnswerGuidelines(verbose: Boolean = true): String = buildString {
            appendLine("질문 유형에 맞는 구조로 답변하세요. 질문 유형을 출력하지 마세요. 바로 답변을 시작하세요.")
            appendLine("검색 결과가 제공되었으면 '없습니다', '찾을 수 없습니다'로 시작하지 마세요. 관련 정보를 먼저 안내하세요.")
            appendLine()
            if (verbose) {
                appendLine("질문 유형별 답변 구조:")
                appendLine("정의형 (~뭐야?) → 한 줄 정의 + 부연 1-2문장. 총 3-5줄.")
                appendLine("절차형 (~어떻게?) → 단계별 번호 리스트(1. 2. 3.). 각 단계 1-2문장.")
                appendLine("비교형 (A와 B 차이) → 항목별 비교.")
                appendLine("목록형 (~종류, ~목록) → 불릿(•) 리스트. 각 항목 간결하게.")
                appendLine("기타/복합 → 핵심 답변 먼저, 세부사항 아래. 단순 3-5줄, 복합 10줄+.")
            } else {
                appendLine("질문 유형에 맞게: 정의형(3-5줄) / 절차형(번호 리스트) / 비교형(항목별) / 목록형(불릿) / 기타(핵심 먼저)")
            }
            appendLine()
            if (verbose) {
                appendLine("출처 표기:")
                appendLine("각 문서의 정보를 언급할 때 해당 문장 안에 <URL|문서제목> 형태로 인라인 링크를 넣으세요.")
                appendLine("예: 배포 절차는 <https://wiki.example.com/pages/123|배포 가이드>에 정리되어 있습니다.")
                appendLine("별도 출처 섹션을 만들지 마세요. 본문 흐름 안에 자연스럽게 넣으세요.")
                appendLine("검색 결과에 URL이 없으면 링크 없이 답변하세요. URL을 추측하지 마세요.")
            } else {
                appendLine("출처: 문장 안에 <URL|문서제목> 인라인. 별도 출처 섹션 금지. URL 없으면 링크 생략.")
            }
            appendLine()
            appendLine("검색 결과에 명시적으로 포함된 정보만 사용하세요.")
            appendLine("확실하지 않으면 \"해당 문서에서 정확한 내용을 확인해주세요\"로 안내하세요.")
            appendLine("검색 결과에 없는 내용을 추측하거나 지어내지 마세요.")
            appendLine()
            appendLine("중요 — 출력 형식은 반드시 Slack mrkdwn입니다. Markdown이 아닙니다.")
            appendLine("허용: *굵게* _기울임_ ~취소선~ `코드` ```코드블록``` <URL|텍스트> • 불릿 1. 번호")
            appendLine("금지 (절대 사용하지 마세요): # ## ### **굵게** __굵게__ [텍스트](URL) --- - 대시불릿")
            appendLine("Slack에서 굵게는 *한 개*로 감쌉니다. **두 개**가 아닙니다.")
        }
    }
}
