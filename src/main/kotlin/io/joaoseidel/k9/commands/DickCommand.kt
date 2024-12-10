package io.joaoseidel.k9.commands

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.entity.Member
import dev.kord.core.entity.User
import dev.kord.core.entity.effectiveName
import io.joaoseidel.k9.DickSize
import io.joaoseidel.k9.getUser
import io.joaoseidel.k9.updateOrAddRole
import io.joaoseidel.k9.updateUser
import java.time.Duration
import java.time.LocalDateTime.now

data class DickCommandArgs(
  val targetId: Snowflake?
)

class DickCommand(
  private val mongoClient: MongoClient
) : AbstractCommand<DickCommandArgs>(
  name = "Pau",
  description = "Mostra o tamanho do seu pinto"
) {
  lateinit var channel: MessageChannelBehavior
  lateinit var author: User
  lateinit var member: Member

  companion object {
    const val RESET_INTERVAL_HOURS = 12L
    val DICK_RANGE = (-10..24)
  }

  override fun matches(input: String) = input.startsWith("!pau")

  override fun help() = "**Use**: !pau [@usuário]"

  override suspend fun execute(args: DickCommandArgs) {
    requireNotNull(channel)
    requireNotNull(author)
    requireNotNull(member)

    if (args.targetId != null) {
      val target = member.guild.getMember(args.targetId)
      val targetUser = getUser(mongoClient, target.id.toString(), target.effectiveName)

      if (targetUser.dickSize == null) {
        channel.createMessage(
          buildString {
            append("${author.mention} o tamanho do pau de ${target.mention} ")
            appendLine("ainda não foi definido, mas não fica triste...")
            append("Fala pro ${target.mention} que ele tem que rodar o `!pau`")
          }
        )
        return
      }

      channel.createMessage(
        buildString {
          append("${author.mention}, seu safadinho, o tamanho do pau de ${target.mention} é ")
          appendLine("de ${targetUser.dickSize.size}cm, gostou? 😏")
        }
      )
      return
    }

    val user = getUser(mongoClient, author.id.toString(), author.effectiveName)
    val randomSize = DICK_RANGE.random()

    val dickSize = if (user.dickSize != null) {
      val latestGeneratedAt = user.dickSize.latestGeneratedAt
      if (latestGeneratedAt.plusHours(RESET_INTERVAL_HOURS).isBefore(now())) {
        val roleId = Snowflake(user.dickSize.role.id)
        updateOrAddRole(roleId, member, "${randomSize}cm de pau", null, false)
        user.dickSize.copy(size = randomSize, latestGeneratedAt = now())
      } else {
        user.dickSize
      }
    } else {
      val role = updateOrAddRole(null, member, "${randomSize}cm de pau", null, false)
      DickSize(
        role = role,
        size = randomSize,
        latestGeneratedAt = now()
      )
    }

    updateUser(mongoClient, user.copy(dickSize = dickSize))

    channel.createMessage(
      buildString {
        appendLine("${author.mention} seu pau mede ${dickSize.size}cm...")

        val latestGeneratedAt = dickSize.latestGeneratedAt
        val cooldownToShowMessage = latestGeneratedAt.plusSeconds(1)
        if (user.dickSize != null && cooldownToShowMessage.isBefore(now())) {
          val remainingTime =
            Duration.between(now(), latestGeneratedAt.plusHours(RESET_INTERVAL_HOURS))

          val daysRemaining = remainingTime.toDays()
          val hoursRemaining = remainingTime.minusDays(daysRemaining).toHours()
          val minutesRemaining = remainingTime.minusHours(hoursRemaining).toMinutes()
          append("* Faltam ${hoursRemaining}h${minutesRemaining} pra você medir de novo ")
          appendLine("vai que aumenta, né.")
        }
      }
    )
  }

  override suspend fun parseArguments(arguments: List<String>): DickCommandArgs {
    if (arguments.size > 2) {
      throw IllegalArgumentException(help())
    }

    if ("@everyone" in arguments || "@here" in arguments) {
      throw IllegalArgumentException("Não da pra fazer isso, seu safado")
    }

    val targetMention = arguments.getOrNull(1)
    val targetId = if (targetMention.isNullOrBlank()) {
      null
    } else {
      if (!Regex("<@\\d+>").matches(targetMention)) {
        throw IllegalArgumentException("Mencione um usuário válido, com @ por favor")
      }

      Snowflake(targetMention.replace("<@", "").replace(">", ""))
    }

    return DickCommandArgs(targetId)
  }
}
