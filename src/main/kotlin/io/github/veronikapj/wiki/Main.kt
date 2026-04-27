@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki

import ai.koog.prompt.dsl.prompt
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.agent.GitHubWikiSearchAgent
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.github.GitHubWikiClient
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.agent.tool.SourceTracker
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.knowledge.IngestAgent
import io.github.veronikapj.wiki.knowledge.KnowledgeStore
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.github.veronikapj.wiki.knowledge.LintAgent
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.context.ProjectMemory
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.context.ConversationStore
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.GoogleEmbeddingClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import io.github.veronikapj.wiki.rag.VectorIndexAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.github.veronikapj.wiki.slack.SlackBotGateway
import io.github.veronikapj.wiki.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wiki.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}, RAG: {}", config.model.provider, config.confluence.spaces, config.rag.enabled)

    // 시크릿 로드 (env → .env → config 폴백)
    val confluenceToken = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
    val slackBotToken = SecretLoader.resolve("SLACK_BOT_TOKEN", config.slack.botToken)
    val slackAppToken = SecretLoader.resolve("SLACK_APP_TOKEN", config.slack.appToken)
    val resolvedModelApiKey = when (config.model.provider) {
        io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
            SecretLoader.resolveNullable("ANTHROPIC_API_KEY", config.model.apiKey)
        io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
            SecretLoader.resolveNullable("GOOGLE_API_KEY", config.model.apiKey)
        else -> config.model.apiKey
    }
    val resolvedModelConfig = config.model.copy(apiKey = resolvedModelApiKey)

    val executor = LLMExecutorBuilder.build(resolvedModelConfig)
    val model = LLMExecutorBuilder.defaultModel(resolvedModelConfig)

    val sourceTracker = SourceTracker()
    val conversationStore = ConversationStore()
    val projectMemory = ProjectMemory()

    val knowledgeStore = KnowledgeStore()
    val knowledgeLlmFn: suspend (String) -> String = { userPrompt ->
        executor.execute(
            prompt("knowledge") { user(userPrompt) }, model
        ).joinToString("") { it.content }
    }

    // ChromaDB knowledge collection (separate from wiki-pages collection)
    val knowledgeChromaFn: (suspend (String, String, String) -> Unit)? = if (config.rag.enabled) {
        val kbChromaClient = ChromaClient(config.rag.chromaUrl)
        val fn: suspend (String, String, String) -> Unit = { id, doc, _ ->
            val collectionId = kbChromaClient.getOrCreateCollection("knowledge_base")
            kbChromaClient.addDocuments(collectionId, listOf(id), listOf(doc))
        }
        fn
    } else null

    val ingestAgent = IngestAgent(knowledgeStore, knowledgeLlmFn, knowledgeChromaFn)
    val lintAgent = LintAgent(knowledgeStore, knowledgeLlmFn)
    val knowledgeTool = KnowledgeTool(knowledgeStore, sourceTracker)
    log.info("Knowledge base initialized: dir=.wiki/knowledge/, chromaFn={}", if (knowledgeChromaFn != null) "enabled" else "disabled")

    var confluenceTool: ConfluenceTool? = null
    var confluenceClient: ConfluenceClient? = null
    if (config.confluence.baseUrl.isNotBlank() && confluenceToken.isNotBlank()) {
        confluenceClient = ConfluenceClient(
            baseUrl = config.confluence.baseUrl,
            token = confluenceToken,
        )
        val confluenceSearchAgent = ConfluenceSearchAgent(
            confluenceClient = confluenceClient,
            spaces = config.confluence.spaces,
        )
        confluenceTool = ConfluenceTool(confluenceSearchAgent, sourceTracker)
        log.info("Confluence enabled: baseUrl={}, spaces={}", config.confluence.baseUrl, config.confluence.spaces)
    } else {
        log.info("Confluence disabled (baseUrl or token not set)")
    }

    val githubToken = SecretLoader.resolve("GITHUB_TOKEN", config.github.token)
    var githubWikiTool: GitHubWikiTool? = null
    if (config.github.enabled && config.github.repos.isNotEmpty()) {
        val githubClient = GitHubWikiClient(githubToken)
        val githubWikiSearchAgent = GitHubWikiSearchAgent(githubClient, config.github.repos)
        githubWikiTool = GitHubWikiTool(githubWikiSearchAgent, sourceTracker)
        log.info("GitHub Wiki enabled: repos={}", config.github.repos)
    }

    var vectorSearchTool: VectorSearchTool? = null
    var vectorIndexAgent: VectorIndexAgent? = null

    if (config.rag.enabled) {
        val chromaClient = ChromaClient(config.rag.chromaUrl)
        val googleApiKey = SecretLoader.resolveNullable("GOOGLE_API_KEY", config.rag.googleApiKey)
        val llmFn: suspend (String) -> String = { userPrompt ->
            executor.execute(
                prompt("llm") { user(userPrompt) }, model
            ).joinToString("") { it.content }
        }
        val llmExpandClient = LlmExpandClient(llmFn)
        val googleEmbeddingClient = if (config.rag.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING)
            GoogleEmbeddingClient(requireNotNull(googleApiKey) { "GOOGLE_API_KEY required for GOOGLE_EMBEDDING mode" })
        else null

        val vectorSearchAgent = VectorSearchAgent(chromaClient, llmExpandClient, googleEmbeddingClient, config.rag)
        vectorSearchTool = VectorSearchTool(vectorSearchAgent, sourceTracker)
        if (confluenceClient != null) {
            vectorIndexAgent = VectorIndexAgent(
                confluenceClient, chromaClient, llmExpandClient, googleEmbeddingClient, config.rag, config.confluence.spaces
            )
        } else {
            log.info("RAG indexing disabled (Confluence not configured)")
        }
        log.info("RAG enabled (mode={})", config.rag.embeddingMode)
    }

    val orchestrator = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        githubWikiTool = githubWikiTool,
        vectorSearchTool = vectorSearchTool,
        executor = executor,
        useManualLoop = config.model.provider == io.github.veronikapj.wiki.config.ModelProvider.CLAUDE_CODE,
        conversationStore = conversationStore,
        projectMemory = projectMemory,
    )

    val slackReady = slackBotToken.isNotBlank() && !slackBotToken.startsWith("xoxb-...") &&
            slackAppToken.isNotBlank() && !slackAppToken.startsWith("xapp-...")

    if (slackReady) {
        val configHandler = SlackConfigHandler(
            config = config,
            persistOnChange = true,
            onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
            onIngest = { url -> ingestAgent.ingestUrl(url) },
            onLint = { lintAgent.lint() },
            projectMemory = projectMemory,
        )
        val gateway = SlackBotGateway(
            slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
            orchestrator = orchestrator,
            configHandler = configHandler,
            projectMemory = projectMemory,
            confluenceClient = confluenceClient,
            ingestAgent = ingestAgent,
        )
        gateway.start()
    } else {
        log.info("Slack tokens not set — running in local CLI mode")
        val divider = "─".repeat(60)
        println(divider)
        println("  wiki-agent CLI  |  GitHub Wiki: Veronikapj/wiki-agent")
        println("  로그: logs/wiki-agent.log  |  종료: q")
        println(divider)
        while (true) {
            println()
            print("질문 > ")
            val input = readlnOrNull()?.trim() ?: break
            if (input == "q") break
            if (input.isBlank()) continue
            println("       $input")
            println(divider)
            println("검색 중...")
            sourceTracker.reset()
            val result = kotlinx.coroutines.runBlocking { orchestrator.answer(input) }
            println()
            println(result)
            println()
            val sources = sourceTracker.sources
            if (sources.isNotEmpty()) {
                println("출처: ${sources.joinToString(" + ")}")
            } else {
                println("출처: 직접 답변 (tool 미사용)")
            }
            println(divider)
        }
        println("종료합니다.")
    }
}
