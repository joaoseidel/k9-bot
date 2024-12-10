package io.joaoseidel.k9.commands

import dev.kord.common.annotation.KordPreview
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.live.live
import dev.kord.core.live.on
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.AssistantMessages
import io.joaoseidel.k9.AssistantOpenAi
import io.joaoseidel.k9.AssistantOpenAiRun
import io.joaoseidel.k9.OpenAiRunStatusResponse
import io.joaoseidel.k9.handlers.AkinatorEventConsumer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

val THUMBS_UP_EMOJI = ReactionEmoji.Unicode("\uD83D\uDC4D\uD83C\uDFFC")
val MAYBE_EMOJI = ReactionEmoji.Unicode("\uD83E\uDD37\uD83C\uDFFC")
val THUMBS_DOWN_EMOJI = ReactionEmoji.Unicode("\uD83D\uDC4E\uD83C\uDFFC")
val GIVE_UP_EMOJI = ReactionEmoji.Unicode("\u274C")
const val K9_AKINATOR_ASSISTANT_ID = "asst_zlbMf74InKmP1XBDGG2NXMDl"

class AkinatorCommand(
  private val httpClient: HttpClient?
) : AbstractCommand<Unit>(
  name = "Ak8tor (Ak 8 tor)",
  description = "Inicia o jogo do Akinator."
) {
  lateinit var channel: MessageChannelBehavior
  lateinit var author: User

  companion object {
    const val LOG_TAG = "[AkinatorCommand]"
  }

  override fun matches(input: String) = input.startsWith("!akinator")

  override fun help() = "**Use**: !akinator"

  @OptIn(KordPreview::class)
  override suspend fun execute(args: Unit) {
    requireNotNull(httpClient)
    requireNotNull(channel)
    requireNotNull(author)

    var threadId: String? = null
    var outputMessage: Message? = null

    channel.withTyping {
      threadId = httpClient.post("https://api.openai.com/v1/threads")
        .body<JsonObject?>()
        ?.get("id")
        ?.jsonPrimitive
        ?.contentOrNull

      if (threadId == null) {
        channel.createMessage { content = "Erro ao criar thread." }
        return@withTyping
      }

      logger.info { "$LOG_TAG Running assistant" }
      val runAssistant: AssistantOpenAiRun =
        httpClient.post("https://api.openai.com/v1/threads/$threadId/runs") {
          setBody(
            Json.encodeToString(
              AssistantOpenAi(
                assistantId = K9_AKINATOR_ASSISTANT_ID,
                additionalInstructions = buildString {
                  appendLine("O nome do usuário falando agora é ${author.username}")
                  append("Para se redigir a ele use ${author.mention}.")
                }
              )
            )
          )
        }.body()
      logger.info { "$LOG_TAG Assistant run started: $runAssistant" }

      // Wait until the assistant finishes processing
      logger.info { "$LOG_TAG Checking assistant status with run id ${runAssistant.id}" }
      runBlocking {
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

            "completed" -> break
          }

          delay(1500)
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
      outputMessage = channel.createMessage(buildString {
        messagesList.data
          .flatMap { it.content }
          .mapNotNull { it.text.value }
          .forEach {
            logger.info { "$LOG_TAG Assistant message: $it" }
            appendLine(it)
          }
      })

      outputMessage.addReaction(THUMBS_UP_EMOJI)
      outputMessage.addReaction(MAYBE_EMOJI)
      outputMessage.addReaction(THUMBS_DOWN_EMOJI)
      outputMessage.addReaction(GIVE_UP_EMOJI)
    }

    if (threadId == null || outputMessage == null) {
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
  }

  override suspend fun parseArguments(arguments: List<String>) {}
}
