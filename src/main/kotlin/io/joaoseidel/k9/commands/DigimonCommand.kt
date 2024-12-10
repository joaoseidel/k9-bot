package io.joaoseidel.k9.commands

import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.Color
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.withTyping
import dev.kord.core.behavior.reply
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import dev.kord.core.entity.effectiveName
import dev.kord.core.live.LiveMessage
import dev.kord.core.live.live
import dev.kord.core.live.onReactionAdd
import dev.kord.rest.builder.message.embed
import io.joaoseidel.k9.Digimon
import io.joaoseidel.k9.getDigimonOwnedByUser
import io.joaoseidel.k9.getUser
import io.joaoseidel.k9.updateUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDateTime.now
import kotlin.random.Random

class DigimonCommand(
  private val mongoClient: MongoClient?,
  private val httpClient: HttpClient? = HttpClient(CIO) {
    install(ContentNegotiation) {
      json(Json { ignoreUnknownKeys = true })
    }
  }
) : AbstractCommand<String>(
  name = "Digimon",
  description = "Rola 10 digimons"
) {
  lateinit var channel: MessageChannelBehavior
  lateinit var guild: GuildBehavior
  lateinit var user: User
  lateinit var message: Message

  lateinit var cooldownJob: Job

  override fun matches(input: String) = input.startsWith("!digimon")

  override fun help(): String {
    return "**Use:** !digimon"
  }

  @OptIn(KordPreview::class)
  override suspend fun execute(args: String) {
    requireNotNull(mongoClient) { "MongoClient must be set" }
    requireNotNull(httpClient) { "HttpClient must be set" }
    requireNotNull(channel) { "Channel must be set" }
    requireNotNull(guild) { "Guild must be set" }
    requireNotNull(user) { "User must be set" }
    requireNotNull(message) { "Message must be set" }

    val digimonCaptureSemaphore = Semaphore(1)
    var mongoUser = getUser(mongoClient, user.id.toString(), user.effectiveName)

    val cooldown = if (mongoUser.digimonCooldown != null) mongoUser.digimonCooldown
    else now()

    if (now() < cooldown) {
      val remainingTime = Duration.between(now(), cooldown)
      val daysRemaining = remainingTime.toDays()
      val hoursRemaining = remainingTime.minusDays(daysRemaining).toHours()
      val minutesRemaining = remainingTime.minusHours(hoursRemaining).toMinutes()
      message.reply {
        content =
          "${user.mention}, você precisa esperar ${hoursRemaining}h$minutesRemaining para rolar mais digimon."
      }
      return
    }

    updateUser(mongoClient, mongoUser.copy(digimonCooldown = now().plusHours(10)))
    mongoUser = getUser(mongoClient, user.id.toString(), user.effectiveName)

    val catchDigimonEmoji = ReactionEmoji.Unicode("❤")
    val digimonRandomId = (1..5).map { Random.nextInt(1, 1460) }
    val digimonMessages = mutableListOf<Message>()
    val digimonLiveMessages = mutableListOf<LiveMessage>()

    runBlocking {
      cooldownJob = launch {
        delay(35000)

        digimonMessages.forEach { it.deleteOwnReaction(catchDigimonEmoji) }
        digimonLiveMessages.forEach { it.cancel() }
        message.reply { content = "${user.mention}, você não capturou nenhum digimon :cry:" }
      }

      channel.withTyping {
        for (digimonId in digimonRandomId) {
          val digimon = getDigimon(digimonId)
          val owner = getDigimonOwner(digimonId)

          val digimonMessage = channel.createMessage {
            embed {
              val level = try {
                digimon
                  .getValue("levels").jsonArray[0].jsonObject
                  .getValue("level").jsonPrimitive.content
              } catch (_: Exception) {
                "Desconhecido"
              }
              val imageHref = try {
                digimon
                  .getValue("images").jsonArray[0].jsonObject
                  .getValue("href").jsonPrimitive.content
              } catch (_: Exception) {
                ""
              }
              val types = digimon
                .getValue("types").jsonArray
                .joinToString(", ") {
                  it.jsonObject["type"]?.jsonPrimitive?.content ?: ""
                }
              val attributes = digimon
                .getValue("attributes").jsonArray
                .joinToString(", ") {
                  it.jsonObject["attribute"]?.jsonPrimitive?.content ?: ""
                }
              val skills = digimon
                .getValue("skills").jsonArray
                .joinToString(", ") {
                  it.jsonObject["skill"]?.jsonPrimitive?.content ?: ""
                }

              color = if (owner != null) Color(0xFF0000) else Color(0x00FF00)
              title = digimon.getValue("name").jsonPrimitive.content
              description = "**Level**: $level"
              image = imageHref

              field("Tipos", true) { types }
              field("Atributos", true) { attributes }
              field("Habilidades", true) { skills }

              if (owner != null) {
                guild.getMemberOrNull(owner)?.let {
                  footer {
                    icon = it.avatar?.cdnUrl?.toUrl()
                    text = "Pertence à ${it.effectiveName}"
                  }
                }
              }
            }
          }

          digimonMessages.add(digimonMessage)
          if (owner == null) {
            digimonMessage.addReaction(catchDigimonEmoji)

            val liveMessage = digimonMessage.live()
            digimonLiveMessages.add(liveMessage)

            // Avoid capturing digimon multiple times
            liveMessage.onReactionAdd {
              if (it.userId != user.id) return@onReactionAdd

              if (it.emoji == catchDigimonEmoji) {
                digimonCaptureSemaphore.withPermit {
                  val digimonList = mongoUser.digimon?.toMutableList() ?: mutableListOf()
                  digimonList.add(Digimon(digimonId))
                  updateUser(mongoClient, mongoUser.copy(digimon = digimonList))

                  digimonMessage.reply {
                    val digimonName = digimon.getValue("name").jsonPrimitive.content
                    content = "${user.mention}, você capturou o digimon $digimonName :sparkles:"
                  }

                  cooldownJob.cancel()

                  digimonMessages.forEach { it.deleteOwnReaction(catchDigimonEmoji) }
                  digimonLiveMessages.forEach { it.cancel() }
                }
              }
            }
          }
        }
      }
    }
  }

  override suspend fun parseArguments(arguments: List<String>): String {
    return ""
  }

  private suspend fun getDigimon(digimonId: Int): JsonObject {
    requireNotNull(mongoClient)
    requireNotNull(httpClient)

    var jsonObject = httpClient.get("https://digi-api.com/api/v1/digimon/${digimonId}")
      .body<JsonObject>()

    return jsonObject
  }

  private suspend fun getDigimonOwner(digimonId: Int): Snowflake? {
    requireNotNull(mongoClient)

    val digimonEntity = getDigimonOwnedByUser(mongoClient, digimonId)
    return if (digimonEntity.isNotEmpty()) Snowflake(digimonEntity[0].discordId) else null
  }
}
