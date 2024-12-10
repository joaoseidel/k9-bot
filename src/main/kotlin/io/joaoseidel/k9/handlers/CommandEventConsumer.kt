package io.joaoseidel.k9.handlers

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.reply
import dev.kord.core.event.message.MessageCreateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.commands.AkinatorCommand
import io.joaoseidel.k9.commands.DiceCommand
import io.joaoseidel.k9.commands.DickCommand
import io.joaoseidel.k9.commands.DickRankCommand
import io.joaoseidel.k9.commands.DigimonCommand
import io.joaoseidel.k9.commands.HelpCommand
import io.joaoseidel.k9.commands.RoleCommand
import io.joaoseidel.k9.commands.SexCommand
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

val COMMANDS_SEMAPHORE = Semaphore(1)
val K9_COMMANDS_CHANNEL_ID = System.getenv("K9_COMMANDS_CHANNEL_ID")

class CommandEventConsumer(
  mongoClient: MongoClient
) : EventConsumer<MessageCreateEvent> {
  private val roleCommand = RoleCommand(mongoClient)
  private val dickCommand = DickCommand(mongoClient)
  private val dickRankCommand = DickRankCommand(mongoClient)
  private val diceCommand = DiceCommand()
  private val sexCommand = SexCommand()
  private val akinatorCommand = AkinatorCommand(null) // Just to show the command
  private val digimonCommand = DigimonCommand(null, null) // Just to show the command
  private val helpCommand = HelpCommand(
    listOf(
      roleCommand,
      dickCommand,
      dickRankCommand,
      diceCommand,
      sexCommand,
      akinatorCommand,
      digimonCommand
    )
  )

  companion object {
    const val LOG_TAG = "[CommandEventConsumer]"
  }

  override suspend fun consume(event: MessageCreateEvent) {
    val message = event.message

    val author = message.author ?: return
    if (author.isBot != false) return

    val channel = message.channel
    if (channel.id != Snowflake(K9_COMMANDS_CHANNEL_ID)) return

    val messageContent = message.content.trim()
    if (messageContent.isBlank()) return
    if (!messageContent.startsWith("!")) return
    val arguments = messageContent.split(" ")

    if (COMMANDS_SEMAPHORE.availablePermits == 0) {
      channel.withTyping {
        message.reply { this.content = "Estou ocupado agora, tente mais tarde." }
      }
      return
    }

    logger.info { "$LOG_TAG Command received: ${messageContent}" }
    COMMANDS_SEMAPHORE.withPermit {
      try {
        withTimeout(5000) {
          when {
            // !comandos [página]
            helpCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                helpCommand.apply {
                  this.channel = channel
                }

                val arguments = helpCommand.parseArguments(arguments)
                return@withTyping helpCommand.execute(arguments)
              }
            }

            akinatorCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                channel.createMessage("Pra usar o Akinator, use o canal <#$K9_AKINATOR_CHANNEL_ID>.")
                return@withTyping
              }
            }

            digimonCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                channel.createMessage("Pra rolar digimon, use o canal <#$K9_DIGIMON_CHANNEL_ID>.")
                return@withTyping
              }
            }

            sexCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                sexCommand.apply {
                  this.channel = channel
                  this.guild = message.getGuild()
                  this.author = author
                  this.message = message
                }

                val arguments = sexCommand.parseArguments(arguments)
                return@withTyping sexCommand.execute(arguments)
              }
            }
            // !d<numero de lados> [modificador]
            diceCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                diceCommand.apply {
                  this.channel = channel
                  this.author = author
                }

                val arguments = diceCommand.parseArguments(arguments)
                return@withTyping diceCommand.execute(arguments)
              }
            }

            // !pau [usuario]
            dickCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                dickCommand.apply {
                  this.channel = channel
                  this.author = author
                  this.member = message.getAuthorAsMember()
                }

                val arguments = dickCommand.parseArguments(arguments)
                return@withTyping dickCommand.execute(arguments)
              }
            }

            // !rankpau
            dickRankCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                dickRankCommand.apply {
                  this.channel = channel
                }

                val arguments = dickRankCommand.parseArguments(arguments)
                return@withTyping dickRankCommand.execute(arguments)
              }
            }

            // !cargo <nome do cargo> [#cor do cargo]
            roleCommand.matches(messageContent) -> {
              return@withTimeout channel.withTyping {
                roleCommand.apply {
                  this.channel = channel
                  this.author = author
                  this.member = message.getAuthorAsMember()
                }

                val arguments = roleCommand.parseArguments(arguments)
                return@withTyping roleCommand.execute(arguments)
              }
            }

            // Default
            else -> return@withTimeout
          }
        }
      } catch (e: IllegalArgumentException) {
        logger.error(e) { "$LOG_TAG Invalid arguments: ${e.message}" }
        val reply = message.reply { this.content = e.message!! }
        reply.edit { this.suppressEmbeds = true }
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
