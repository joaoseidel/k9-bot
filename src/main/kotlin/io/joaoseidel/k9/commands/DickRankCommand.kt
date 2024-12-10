package io.joaoseidel.k9.commands

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Aggregates.match
import com.mongodb.client.model.Aggregates.sort
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Sorts.descending
import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.core.behavior.channel.MessageChannelBehavior
import io.joaoseidel.k9.User
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList

data class DickRankCommandArgs(
  val page: Int
)

class DickRankCommand(
  private val mongoClient: MongoClient
) : AbstractCommand<DickRankCommandArgs>(
  name = "RankPau",
  description = "Mostra o ranking de tamanhos de pau do servidor"
) {
  lateinit var channel: MessageChannelBehavior

  companion object {
    private const val PAGE_SIZE = 10
  }

  override fun matches(input: String) = input.startsWith("!rankpau")

  override fun help() = "**Use**: !rankpau [página]"

  override suspend fun execute(args: DickRankCommandArgs) {
    val database = mongoClient.getDatabase("k9")
    val collection = database.getCollection<User>("users")

    val dickSizes = collection
      .aggregate(
        listOf(
          match(exists("dickSize", true)),
          match(ne("dickSize.size", null)),
          sort(descending("dickSize.size", "dickSize.latestGeneratedAt")),
          Aggregates.skip(args.page * PAGE_SIZE),
          Aggregates.limit(PAGE_SIZE)
        )
      )
      .mapNotNull { it.dickSize?.let { dickSize -> it.discordId to dickSize.size } }
      .toList()

    val totalDickSizes = collection.countDocuments(
      Filters.and(
        Filters.exists("dickSize", true),
        Filters.ne("dickSize.size", null)
      )
    )

    val page = args.page + 1
    val totalPages = (totalDickSizes + PAGE_SIZE - 1) / PAGE_SIZE

    channel.createMessage(
      buildString {
        appendLine("**Top paus do servidor**:")
        dickSizes.forEachIndexed { i, (discordId, size) ->
          val index = i + 1 + (page - 1) * PAGE_SIZE
          when (index) {
            1 -> append(":first_place:")
            2 -> append(":second_place:")
            3 -> append(":third_place:")
            else -> append(index)
          }
          appendLine(". <@$discordId> com ${size}cm")
        }
        append("**Página [$page de $totalPages]**")
      }
    )
  }

  override suspend fun parseArguments(arguments: List<String>): DickRankCommandArgs {
    val page = try {
      arguments.getOrNull(1)?.toInt()
    } catch (_: NumberFormatException) {
      throw IllegalArgumentException("A página deve ser um número")
    }

    if (page != null && page < 1) {
      throw IllegalArgumentException("A página deve ser maior que 0")
    }

    return DickRankCommandArgs(page?.minus(1) ?: 0)
  }
}
