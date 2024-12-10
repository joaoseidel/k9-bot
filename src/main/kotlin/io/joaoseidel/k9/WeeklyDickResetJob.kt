package io.joaoseidel.k9

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.starry.ktscheduler.event.JobEvent
import dev.starry.ktscheduler.event.JobEventListener
import dev.starry.ktscheduler.job.Job
import dev.starry.ktscheduler.triggers.CronTrigger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime.now
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

class WeeklyDickResetJob(
  private val kord: Kord,
  private val mongoClient: MongoClient,
  val job: Job = Job(
    jobId = "weekly-dick-reset-job",
    trigger = CronTrigger(setOf(DayOfWeek.MONDAY), LocalTime.of(14, 0)),
    runConcurrently = false,
    dispatcher = Dispatchers.Default,
    callback = {
      logger.info { "Resetando o tamanho do pau de usuários" }

      val users = getUsersWithDickSize(mongoClient)
      if (users.isEmpty()) return@Job

      logger.info { "Usuários com tamanho de pau: ${users.size}" }

      val channel = MessageChannelBehavior(Snowflake(System.getenv("K9_COMMANDS_CHANNEL_ID")), kord)
      val guild = GuildBehavior(channel.asChannel().data.guildId.value!!, kord)
      val biggestDickSize = users.first()

      logger.info { "Maior pau: ${biggestDickSize.dickSize?.size}cm" }

      channel.createMessage(buildString {
        appendLine("Resetando o tamanho do pau de ${users.size} usuários:")
        users.forEach {
          guild.getRole(Snowflake(it.dickSize!!.role.id)).delete("Reset Automático")
          updateUser(
            mongoClient,
            it.copy(
              dickSize = null,
              winnerDickSizeTimes = if (it.id == biggestDickSize.id) {
                it.winnerDickSizeTimes?.plus(1) ?: 1
              } else {
                it.winnerDickSizeTimes ?: 0
              }
            )
          )
          appendLine("- <@${it.discordId}> teve o tamanho do pau de ${it.dickSize.size}cm resetado")
        }

        appendLine()
        append(":sparkles: O maior pau era de <@${biggestDickSize.discordId}> ")
        appendLine("com ${biggestDickSize.dickSize?.size}cm")

        appendLine("**Ranking atual:**")
        val usersThatWonDickSize = getUsersThatWonDickSize(mongoClient)
        usersThatWonDickSize
          .forEachIndexed { index, user ->
            val index = index + 1

            when (index) {
              1 -> append(":first_place:")
              2 -> append(":second_place:")
              3 -> append(":third_place:")
              else -> append(index)
            }

            appendLine(" - <@${user.discordId}> vencendo ${user.winnerDickSizeTimes}x")
          }
        appendLine()

        val remainingTime = Duration.between(now(), now().plusDays(7))
        val daysRemaining = remainingTime.toDays()
        appendLine("*Próximo reset em $daysRemaining ($remainingTime.) dias.*")
      })
    }
  )
) : JobEventListener {
  override fun onJobComplete(event: JobEvent) {
    logger.info { "Job ${event.jobId} completed" }
  }

  override fun onJobError(event: JobEvent) {
    logger.error(event.exception) { "Error on job ${event.jobId}" }
  }
}
