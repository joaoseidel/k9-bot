package io.joaoseidel.k9.handlers

import dev.kord.common.annotation.KordPreview
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.reply
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.live.LiveMessage
import dev.kord.core.live.live
import dev.kord.core.live.on
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.AssistantMessages
import io.joaoseidel.k9.AssistantOpenAi
import io.joaoseidel.k9.AssistantOpenAiRun
import io.joaoseidel.k9.OpenAiRunStatusResponse
import io.joaoseidel.k9.OpenAiRunStatusResponse.RequiredAction.SubmitToolOutputs.*
import io.joaoseidel.k9.SubmitToolOutputs
import io.joaoseidel.k9.SubmitToolOutputs.*
import io.joaoseidel.k9.UserOpenAiMessage
import io.joaoseidel.k9.commands.GIVE_UP_EMOJI
import io.joaoseidel.k9.commands.K9_AKINATOR_ASSISTANT_ID
import io.joaoseidel.k9.commands.MAYBE_EMOJI
import io.joaoseidel.k9.commands.THUMBS_DOWN_EMOJI
import io.joaoseidel.k9.commands.THUMBS_UP_EMOJI
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

val AKINATOR_SEMAPHORE = Semaphore(1)
val K9_AKINATOR_CHANNEL_ID = System.getenv("K9_AKINATOR_CHANNEL_ID")

@OptIn(KordPreview::class)
class AkinatorEventConsumer(
  private val httpClient: HttpClient,
  private val threadId: String
) : EventConsumer<ReactionAddEvent> {
  lateinit var question: Message
  lateinit var channel: MessageChannelBehavior
  lateinit var author: User
  var messageLiveListener: LiveMessage? = null

  private var cancelTimeout: Boolean = false

  companion object {
    const val LOG_TAG = "[AkinatorEventConsumer]"
  }

  @OptIn(DelicateCoroutinesApi::class)
  fun startCooldown() {
    GlobalScope.launch {
      logger.info { "$LOG_TAG Starting cooldown" }
      while (!cancelTimeout) {
        delay(30000)

        if (!cancelTimeout && messageLiveListener?.isActive == true) {
          messageLiveListener?.shutDown()
          question.deleteAllReactions()
          question.reply {
            content = "${author.mention} você demorou demais para responder, então eu desisti."
          }
          logger.info { "$LOG_TAG Cooldown reached, shutting down message listener" }
          return@launch
        }
      }

      logger.info { "$LOG_TAG Cooldown was cancelled" }
    }
  }

  override suspend fun consume(event: ReactionAddEvent) {
    requireNotNull(question)
    requireNotNull(author)
    requireNotNull(messageLiveListener)

    if (event.user.id != author.id) return

    logger.info { "$LOG_TAG Reaction received: ${event.emoji}" }

    val answer = if (event.emoji == THUMBS_UP_EMOJI) "Sim"
    else if (event.emoji == MAYBE_EMOJI) "Não sei ou talvez"
    else if (event.emoji == THUMBS_DOWN_EMOJI) "Não"
    else if (event.emoji == GIVE_UP_EMOJI) "Desisto"
    else null

    if (answer == null) {
      logger.info { "$LOG_TAG Invalid emoji: ${event.emoji}" }
      question.reply { content = "${event.user.mention} usa os emojis certo, po." }
      return
    }

    cancelTimeout = true

    event.message.deleteOwnReaction(THUMBS_UP_EMOJI)
    event.message.deleteOwnReaction(MAYBE_EMOJI)
    event.message.deleteOwnReaction(THUMBS_DOWN_EMOJI)
    event.message.deleteOwnReaction(GIVE_UP_EMOJI)

    var outputMessage: Message? = null

    channel.withTyping {
      logger.info { "$LOG_TAG Adding user message to OpenAI thread" }
      val addMessageResponse =
        httpClient.post("https://api.openai.com/v1/threads/$threadId/messages") {
          setBody(
            Json.encodeToString(
              UserOpenAiMessage(
                role = "user",
                content = "${event.user.mention} respondeu: $answer"
              )
            )
          )
        }
      logger.info { "$LOG_TAG User message added to OpenAI thread: $addMessageResponse" }

      logger.info { "$LOG_TAG Running assistant" }
      val runAssistant: AssistantOpenAiRun =
        httpClient.post("https://api.openai.com/v1/threads/$threadId/runs") {
          setBody(
            Json.encodeToString(
              AssistantOpenAi(
                assistantId = K9_AKINATOR_ASSISTANT_ID,
                additionalInstructions = buildString {
                  append("Se redija ao usuário como ${event.user.mention}. Se ele desistir, seja sarcastico. Mas lembre-se que o jogo acabou.")
                }
              )
            )
          )
        }.body()
      logger.info { "$LOG_TAG Assistant run started: $runAssistant" }

      // Wait until the assistant finishes processing
      logger.info { "$LOG_TAG Checking assistant status with run id ${runAssistant.id}" }
      var gameEnd = false
      runBlocking {
        return@runBlocking handleRunStatus(runAssistant) {
          when (it.function.name) {
            "detect_game_end" -> {
              gameEnd = true
              return@handleRunStatus ToolOutput(
                toolCallId = it.id,
                output = "{\"success\": true, \"instructions\": \"Se despida do usuário, o jogo acabou. E seja sarcastico.\"}",
              )
            }

            else -> {
              throw Exception("Unsupported tool call: ${it.function.name}")
            }
          }
        }
      }

      // Get the assistant messages
      logger.info { "$LOG_TAG Getting assistant messages with run id ${runAssistant.id}" }
      val messagesList: AssistantMessages =
        httpClient.get("https://api.openai.com/v1/threads/$threadId/messages") {
          url {
            parameters.append("run_id", runAssistant.id)
          }
        }.body()
      logger.info { "$LOG_TAG Assistant messages: $messagesList" }

      // Sends the assistant messages to Discord
      logger.info { "$LOG_TAG Sending assistant messages to Discord" }
      outputMessage = question.reply {
        content = buildString {
          messagesList.data
            .flatMap { it.content }
            .mapNotNull { it.text.value }
            .forEach {
              logger.info { "$LOG_TAG Assistant message: $it" }
              appendLine(it)
            }
        }
      }

      if (event.emoji == GIVE_UP_EMOJI || gameEnd) {
        outputMessage = null
        return@withTyping
      }

      outputMessage.addReaction(THUMBS_UP_EMOJI)
      outputMessage.addReaction(MAYBE_EMOJI)
      outputMessage.addReaction(THUMBS_DOWN_EMOJI)
      outputMessage.addReaction(GIVE_UP_EMOJI)
    }

    if (outputMessage == null) {
      messageLiveListener?.cancel() ?: "Game ended"
      return
    }

    val liveMessage = outputMessage.live()

    val author = author
    val channel = channel
    val handler = AkinatorEventConsumer(httpClient, threadId).apply {
      this.question = outputMessage
      this.channel = channel
      this.author = author
      this.messageLiveListener = liveMessage
    }
    handler.startCooldown()

    liveMessage.on<ReactionAddEvent> {
      handler.consume(it)
    }

    this.messageLiveListener?.cancel() ?: "New message listener created"
  }

  private suspend fun handleRunStatus(
    runAssistant: AssistantOpenAiRun,
    handleToolCall: suspend (ToolCall) -> ToolOutput,
  ) {
    while (true) {
      val runStatus: OpenAiRunStatusResponse =
        httpClient
          .get("https://api.openai.com/v1/threads/$threadId/runs/${runAssistant.id}")
          .body()

      logger.info { "$LOG_TAG Assistant status: $runStatus" }
      when (runStatus.status) {
        "cancelled", "failed", "expired" -> {
          runStatus.lastError?.takeIf { it.code == "rate_limit_exceeded" }?.let {
            throw IllegalStateException("Cansei de responder vocês por hoje, vermes inuteis.")
          }

          throw Exception("Assistant run failed with status ${runStatus.status}")
        }

        "requires_action" -> {
          logger.info { "$LOG_TAG Assistant requires action" }

          val requiredAction = runStatus.requiredAction

          if (requiredAction == null) {
            throw Exception("Missing required action")
          }

          if (requiredAction.type != "submit_tool_outputs") {
            throw Exception("Unsupported required action: ${requiredAction.type}")
          }

          logger.info { "$LOG_TAG Collecting tool outputs" }
          val toolOutputs = requiredAction.submitToolOutputs.toolCalls
            .map { handleToolCall(it) }
            .toTypedArray()
          logger.info { "$LOG_TAG Tool outputs collected: $toolOutputs" }

          logger.info { "$LOG_TAG Submitting tool outputs" }
          httpClient.post("https://api.openai.com/v1/threads/$threadId/runs/${runAssistant.id}/submit_tool_outputs") {
            setBody(Json.encodeToString(SubmitToolOutputs(toolOutputs)))
          }
          logger.info { "$LOG_TAG Tool outputs submitted" }
        }

        "completed" -> break
      }

      delay(1500)
    }
  }
}
