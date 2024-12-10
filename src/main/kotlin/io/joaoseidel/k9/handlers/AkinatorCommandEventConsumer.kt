package io.joaoseidel.k9.handlers

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.reply
import dev.kord.core.event.message.MessageCreateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.commands.AkinatorCommand
import io.ktor.client.HttpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

class AkinatorCommandEventConsumer(
  private val httpClient: HttpClient
) : EventConsumer<MessageCreateEvent> {
  private val akinatorCommand = AkinatorCommand(httpClient)

  companion object {
    const val LOG_TAG = "[AkinatorCommandEventConsumer]"
  }

  override suspend fun consume(event: MessageCreateEvent) {
    val message = event.message

    val author = message.author ?: return
    if (author.isBot != false) return

    val channel = message.channel
    if (channel.id != Snowflake(K9_AKINATOR_CHANNEL_ID)) return

    val messageContent = message.content.trim()
    if (messageContent.isBlank()) return
    if (!akinatorCommand.matches(messageContent)) {
      channel.withTyping {
        message.reply { this.content = akinatorCommand.help() }
      }
      return
    }
    val arguments = messageContent.split(" ")

    if (AKINATOR_SEMAPHORE.availablePermits == 0) {
      channel.withTyping {
        message.reply { this.content = "Estou ocupado agora, tente mais tarde." }
      }
      return
    }

    logger.info { "${CommandEventConsumer.Companion.LOG_TAG} Command received: ${messageContent}" }
    AKINATOR_SEMAPHORE.withPermit {
      try {
        withTimeout(60000) {
          when {
            akinatorCommand.matches(messageContent) -> {
              akinatorCommand.apply {
                this.channel = channel
                this.author = author
              }

              val arguments = akinatorCommand.parseArguments(arguments)
              return@withTimeout akinatorCommand.execute(arguments)
            }
            // Default
            else -> return@withTimeout
          }
        }
      } catch (e: TimeoutCancellationException) {
        logger.error(e) { "$LOG_TAG Timeout processing message: ${e.message}" }
        message.reply {
          this.content = "Demorei tanto para responder que deu erro aqui, tenta de novo."
        }
      } catch (e: Exception) {
        logger.error(e) { "$LOG_TAG Error processing command: ${e.message}" }
        message.reply { this.content = "Acabou de dar um erro aqui, tenta de novo." }
      }
    }
  }
}
