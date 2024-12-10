package io.joaoseidel.k9.commands

import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.edit
import dev.kord.core.entity.User
import kotlinx.coroutines.delay

data class DiceCommandArgs(
  val sides: Int,
  val modifier: Int?,
)

class DiceCommand : AbstractCommand<DiceCommandArgs>(
  name = "Dados",
  description = "Rola um dado de N lados"
) {
  lateinit var channel: MessageChannelBehavior
  lateinit var author: User

  override fun matches(input: String) = Regex("^(!d\\d+)").containsMatchIn(input)

  override fun help() = "**Use**: !d<numero de lados> [modificador]"

  override suspend fun execute(args: DiceCommandArgs) {
    if (args.sides < 2) {
      throw IllegalArgumentException("O número de lados deve ser maior que 1")
    }

    val sides = args.sides
    val modifier = args.modifier ?: 0

    var randomSize = (1..args.sides).random()
    var randomSizeModifier = randomSize + modifier

    if (randomSizeModifier < 1) {
      randomSize = 1
      randomSizeModifier = 1
    }

    var messageText = "Jogando o dado... "
    val message = channel.createMessage("Jogando o dado...")
    delay(500)

    for (i in 1..2) {
      message.edit {
        messageText += " $i..."
        this.content = messageText
      }
      delay(500)
    }

    message.edit {
      this.content = buildString {
        if (randomSize == sides) append("**CRÍTICO!**")
        else if (randomSize == 1) append("**ERRO CRÍTICO!**")

        append("${author.mention} jogou um dado d$sides e tirou $randomSize")
        if (modifier != 0) append(" *(Valor com modificador ${randomSizeModifier})*")
      }
    }
  }

  override suspend fun parseArguments(arguments: List<String>): DiceCommandArgs {
    if (arguments.isEmpty() || arguments.size > 2) {
      throw IllegalArgumentException(help())
    }

    val sides = try {
      arguments.first().removePrefix("!d").toInt()
    } catch (_: NumberFormatException) {
      throw IllegalArgumentException("O número de lados deve ser um número")
    }

    val modifier = try {
      arguments.getOrNull(1)?.toInt()
    } catch (_: NumberFormatException) {
      throw IllegalArgumentException("O modificador deve ser um número")
    }

    return DiceCommandArgs(sides, modifier)
  }
}
