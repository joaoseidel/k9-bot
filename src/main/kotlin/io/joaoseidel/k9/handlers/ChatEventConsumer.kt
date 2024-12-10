package io.joaoseidel.k9.handlers

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.reply
import dev.kord.core.event.message.MessageCreateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.AssistantMessages
import io.joaoseidel.k9.AssistantOpenAi
import io.joaoseidel.k9.AssistantOpenAiRun
import io.joaoseidel.k9.OpenAiRunStatusResponse
import io.joaoseidel.k9.UserOpenAiMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

val AI_CHAT_SEMAPHORE = Semaphore(1)
val K9_CHAT_CHANNEL_ID = System.getenv("K9_CHAT_CHANNEL_ID")

class ChatEventConsumer(
  private val httpClient: HttpClient
) : EventConsumer<MessageCreateEvent> {

  companion object {
    const val LOG_TAG = "[ChatEventConsumer]"
    const val K9_CHAT_ASSISTANT_ID = "asst_QlmvIEGE2lfVVQG4fjfhFT6t"
    const val K9_CHAT_THREAD_ID = "thread_q6WQvdp9EObUXLifjMr3ACVd"
  }

  override suspend fun consume(event: MessageCreateEvent) {
    val message = event.message

    val author = message.author ?: return
    if (author.isBot == true) return

    val channel = message.channel
    if (channel.id != Snowflake(K9_CHAT_CHANNEL_ID)) return

    val messageContent = message.content.trim()
    if (messageContent.isBlank()) return

    val content = messageContent.lowercase()
    when {
      content.contains("http") -> {
        channel.withTyping {
          message.reply {
            this.content = "Não envie links, por favor. Por conta disso não irei responder."
          }
        }
        return
      }

      content.contains("@everyone") || content.contains("@here") -> {
        channel.withTyping {
          message.reply {
            this.content = "Não marque todos, por favor. Por conta disso não irei responder."
          }
        }
        return
      }

      Regex("^(!|\\$|m!).*").containsMatchIn(content) -> {
        channel.withTyping {
          message.reply {
            this.content =
              "Não envie comandos aqui, por favor. Use o <#$K9_COMMANDS_CHANNEL_ID> para meus comandos."
          }
        }
        return
      }
    }

    if (AI_CHAT_SEMAPHORE.availablePermits == 0) {
      channel.withTyping {
        message.reply {
          this.content =
            "Eu já to respondendo alguém, seu corno mal amado. Aprenda a esperar tua vez."
        }
      }
      return
    }

    logger.info { "$LOG_TAG Message received: ${message.content}" }
    logger.info { "$LOG_TAG Semaphore available permits: ${AI_CHAT_SEMAPHORE.availablePermits}" }

    AI_CHAT_SEMAPHORE.withPermit {
      try {
        withTimeout(30000) {
          channel.withTyping {
            // Adds the user message to the OpenAI thread
            logger.info { "$LOG_TAG Adding user message to OpenAI thread" }
            val addMessageResponse =
              httpClient.post("https://api.openai.com/v1/threads/$K9_CHAT_THREAD_ID/messages") {
                setBody(
                  Json.encodeToString(
                    UserOpenAiMessage(
                      role = "user",
                      content = "${author.username} ou ${author.mention} disse: $messageContent",
                      metadata = UserOpenAiMessage.UserOpenAiMessageMetadata(
                        username = author.username,
                        mention = author.mention
                      )
                    )
                  )
                )
              }
            logger.info { "$LOG_TAG User message added to OpenAI thread: $addMessageResponse" }

            // Runs the assistant
            logger.info { "$LOG_TAG Running assistant" }
            val runAssistant: AssistantOpenAiRun =
              httpClient.post("https://api.openai.com/v1/threads/$K9_CHAT_THREAD_ID/runs") {
                setBody(
                  Json.encodeToString(
                    AssistantOpenAi(
                      assistantId = K9_CHAT_ASSISTANT_ID,
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
                    .get("https://api.openai.com/v1/threads/$K9_CHAT_THREAD_ID/runs/${runAssistant.id}")
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
              httpClient.get("https://api.openai.com/v1/threads/$K9_CHAT_THREAD_ID/messages") {
                url {
                  parameters.append("run_id", runAssistant.id)
                }
              }.body()
            logger.info { "$LOG_TAG Assistant messages: $messagesList" }

            // Sends the assistant messages to Discord
            logger.info { "$LOG_TAG Sending assistant messages to Discord" }
            channel.createMessage(buildString {
              messagesList.data
                .flatMap { it.content }
                .mapNotNull { it.text.value }
                .forEach {
                  logger.info { "$LOG_TAG Assistant message: $it" }
                  appendLine(it)
                }
            })
          }
        }
      } catch (e: IllegalStateException) {
        logger.error(e) { "$LOG_TAG Rate limit exceeded: ${e.message}" }
        channel.createMessage(e.message!!)
      } catch (e: TimeoutCancellationException) {
        logger.error(e) { "$LOG_TAG Timeout processing message: ${e.message}" }
        message.reply {
          this.content = "Demorei tanto para responder que deu erro aqui, tenta de novo."
        }
      } catch (e: Exception) {
        logger.error(e) { "$LOG_TAG Error processing message: ${e.message}" }
        message.reply {
          this.content = "Acabou de dar um erro aqui, tenta de novo."
        }
      }
    }
  }
}
