package io.joaoseidel.k9.commands

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.swapRolePositions
import dev.kord.core.entity.Member
import dev.kord.core.entity.User
import dev.kord.core.entity.effectiveName
import io.joaoseidel.k9.getUser
import io.joaoseidel.k9.updateOrAddRole
import io.joaoseidel.k9.updateUser
import kotlinx.coroutines.flow.toList

data class RoleCommandArgs(
  val roleName: String,
  val roleColor: Color?
)

class RoleCommand(
  private val mongoClient: MongoClient
) : AbstractCommand<RoleCommandArgs>(
  name = "Cargo",
  description = "Gerencia o próprio cargo no servidor"
) {
  lateinit var channel: MessageChannelBehavior
  lateinit var author: User
  lateinit var member: Member

  override fun matches(input: String) = input.startsWith("!cargo")

  override fun help() =
    buildString {
      appendLine("**Use**: !cargo <nome> [#cor em hexadecimal]")
      appendLine(buildString {
        append("**Dica**: Use o site [ColorHex](https://www.color-hex.com) ")
        append("para pegar a cor em hexadecimal")
      })
      append("*A cor é opcional, caso não escolha, sera mantida a mesma ou criada com a padrão.*")
    }

  override suspend fun execute(args: RoleCommandArgs) {
    requireNotNull(channel)
    requireNotNull(author)
    requireNotNull(member)

    val user = getUser(mongoClient, author.id.toString(), author.effectiveName)
    val userPersonalRole = user.personalRole
    val roleId = if (userPersonalRole != null) Snowflake(userPersonalRole.id) else null

    val roleColor = args.roleColor ?: user.personalRole?.color ?: Color(0xEEEEEE)
    val role = updateOrAddRole(roleId, member, args.roleName, roleColor, true)
    updateUser(mongoClient, user.copy(personalRole = role))

    // Ordena os roles do servidor para sempre colocar o cargo recem personalizado no topo
    val rolesSorted = member.guild.roles.toList().sortedByDescending { it.rawPosition }
    val topRolePosition = rolesSorted.firstOrNull()?.rawPosition?.minus(2) ?: 0
    val roles = rolesSorted.map { roleItem ->
      when {
        roleItem.id == Snowflake(role.id) -> roleItem.id to topRolePosition
        else -> roleItem.id to (roleItem.rawPosition - 1)
      }
    }

    member.guild.swapRolePositions {
      move(*roles.toTypedArray())
    }

    channel.createMessage(
      buildString {
        append("Oi, ${author.mention}, seu cargo `${role.name}` foi ")
        append(if (user.personalRole == null) "criado" else "atualizado")
        append(" com sucesso!")
      }
    )
  }

  override suspend fun parseArguments(arguments: List<String>): RoleCommandArgs {
    if (arguments.size < 2) {
      throw IllegalArgumentException(help())
    }

    val roleName = if (arguments.last().startsWith("#")) {
      arguments.drop(1).dropLast(1).joinToString(" ")
    } else {
      arguments.drop(1).joinToString(" ")
    }

    val roleColor = if (arguments.last().startsWith("#")) {
      try {
        Color(arguments.last().removePrefix("#").toInt(16))
      } catch (_: Exception) {
        null
      }
    } else {
      null
    }

    return RoleCommandArgs(roleName, roleColor)
  }
}
