package io.joaoseidel.k9.handlers

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.reply
import dev.kord.core.event.message.MessageCreateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.commands.DigimonCommand
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

val DIGIMON_SEMAPHORE = Semaphore(1)
val K9_DIGIMON_CHANNEL_ID = System.getenv("K9_DIGIMON_CHANNEL_ID")

class DigimonCommandEventConsumer(
  mongoClient: MongoClient
) : EventConsumer<MessageCreateEvent> {
  private val digimonCommand = DigimonCommand(mongoClient)

  companion object {
    const val LOG_TAG = "[DigimonCommandEventConsumer]"
  }

  override suspend fun consume(event: MessageCreateEvent) {
    val message = event.message

    val author = message.author ?: return
    if (author.isBot != false) return

    val channel = message.channel
    if (channel.id != Snowflake(K9_DIGIMON_CHANNEL_ID)) return

    val messageContent = message.content.trim()
    if (messageContent.isBlank()) return
    if (!digimonCommand.matches(messageContent)) {
      channel.withTyping {
        message.reply { this.content = digimonCommand.help() }
      }
      return
    }
    val arguments = messageContent.split(" ")

    if (DIGIMON_SEMAPHORE.availablePermits == 0) {
      channel.withTyping {
        message.reply { this.content = "Já tem alguém na tua frente, calma aí." }
      }
      return
    }

    logger.info { "${CommandEventConsumer.Companion.LOG_TAG} Command received: ${messageContent}" }
    DIGIMON_SEMAPHORE.withPermit {
      try {
        withTimeout(60000) {
          when {
            digimonCommand.matches(messageContent) -> {
              digimonCommand.apply {
                this.channel = channel
                this.guild = message.getGuild()
                this.user = author
                this.message = message
              }

              val arguments = digimonCommand.parseArguments(arguments)
              return@withTimeout digimonCommand.execute(arguments)
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
