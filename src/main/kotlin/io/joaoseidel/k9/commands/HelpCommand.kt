package io.joaoseidel.k9.commands

import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.edit

data class HelpCommandArgs(
  val page: Int
)

class HelpCommand(
  private val commands: List<AbstractCommand<*>>
) : AbstractCommand<HelpCommandArgs>(
  name = "Comandos",
  description = "Mostra todos os comandos disponíveis"
) {
  lateinit var channel: MessageChannelBehavior

  companion object {
    private const val PAGE_SIZE = 10
  }

  override fun matches(input: String) = input.startsWith("!help") ||
    input.startsWith("!ajuda") ||
    input.startsWith("!comandos")

  override fun help() = "**Use**: !comandos [página]"

  override suspend fun execute(args: HelpCommandArgs) {
    requireNotNull(channel)

    val page = args.page + 1
    val totalPages = (commands.size + PAGE_SIZE - 1) / PAGE_SIZE

    val message = channel.createMessage(
      buildString {
        appendLine("**Comandos disponíveis do K9**:")
        commands
          .plus(this@HelpCommand)
          .drop(args.page * PAGE_SIZE)
          .take(10)
          .forEachIndexed { index, it ->
            appendLine("    ${it.name}: ${it.description.lowercase()}")
            appendLine("         ${it.help().split("\n").joinToString("\n         ")}")
          }
        append("**Página [$page de $totalPages]**")
      }
    )

    message.edit { suppressEmbeds = true }
  }

  override suspend fun parseArguments(arguments: List<String>): HelpCommandArgs {
    val page = try {
      arguments.getOrNull(1)?.toInt()
    } catch (_: NumberFormatException) {
      throw IllegalArgumentException("A página deve ser um número")
    }

    if (page != null && page < 1) {
      throw IllegalArgumentException("A página deve ser maior que 0")
    }

    return HelpCommandArgs(page?.minus(1) ?: 0)
  }
}
