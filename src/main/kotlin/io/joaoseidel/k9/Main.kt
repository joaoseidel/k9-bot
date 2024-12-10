package io.joaoseidel.k9

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.client.model.Aggregates.*
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Sorts.*
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.*
import com.mongodb.client.result.UpdateResult
import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.createRole
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Member
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.starry.ktscheduler.scheduler.KtScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.joaoseidel.k9.handlers.AkinatorCommandEventConsumer
import io.joaoseidel.k9.handlers.ChatEventConsumer
import io.joaoseidel.k9.handlers.CommandEventConsumer
import io.joaoseidel.k9.handlers.DigimonCommandEventConsumer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
  val kord = Kord(System.getenv("DISCORD_TOKEN"))
  val mongoUri = System.getenv("MONGO_URI")

  val httpClient = getHttpClient()
  val mongoClient = getMongoClient(mongoUri)

  // K9 Chat
  val chatEventConsumer = ChatEventConsumer(httpClient)

  // K9 Commands
  val commandEventConsumer = CommandEventConsumer(mongoClient)

  // K9 Akinator
  val akinatorEventConsumer = AkinatorCommandEventConsumer(httpClient)

  // K9 Digimon
  val digimonEventConsumer = DigimonCommandEventConsumer(mongoClient)

  kord.on<MessageCreateEvent> {
    chatEventConsumer.consume(this)
    commandEventConsumer.consume(this)
    akinatorEventConsumer.consume(this)
    digimonEventConsumer.consume(this)
  }

  val timeZone = ZoneId.of("America/Sao_Paulo")
  val scheduler = KtScheduler(timeZone = timeZone)
  val weeklyDickResetJob = WeeklyDickResetJob(kord, mongoClient)
  scheduler.addJob(weeklyDickResetJob.job)
  scheduler.addEventListener(weeklyDickResetJob)
  scheduler.start()

  // K9 Ready
  kord.login {
    @OptIn(PrivilegedIntent::class)
    intents += Intent.MessageContent
  }
}

private fun getHttpClient() = HttpClient(CIO) {
  engine {
    requestTimeout = 0
  }

  install(ContentNegotiation) {
    json(Json { ignoreUnknownKeys = true })
  }

  defaultRequest {
    header("Content-Type", "application/json")
    header("Authorization", "Bearer ${System.getenv("OPENAI_API_KEY")}")
    header("OpenAI-Beta", "assistants=v2")
  }
}

private fun getMongoClient(uri: String) = MongoClient.create(
  MongoClientSettings.builder()
    .applyConnectionString(ConnectionString(uri))
    .serverApi(
      ServerApi.builder()
        .version(ServerApiVersion.V1)
        .build()
    )
    .build()
)

// data classes
@Serializable
data class AssistantMessages(
  val data: List<MessageContent>
) {
  @Serializable
  data class MessageContent(
    val content: List<TextContent>
  ) {
    @Serializable
    data class TextContent(
      val text: TextValue
    ) {
      @Serializable
      data class TextValue(
        val value: String?
      )
    }
  }
}

@Serializable
data class OpenAiRunStatusResponse(
  val status: String,
  @SerialName("last_error")
  val lastError: LastError? = null,
  @SerialName("required_action")
  val requiredAction: RequiredAction? = null
) {
  @Serializable
  data class LastError(
    val code: String,
    val message: String
  )

  @Serializable
  data class RequiredAction(
    val type: String,
    @SerialName("submit_tool_outputs")
    val submitToolOutputs: SubmitToolOutputs
  ) {
    @Serializable
    data class SubmitToolOutputs(
      @SerialName("tool_calls")
      val toolCalls: Array<ToolCall>
    ) {
      @Serializable
      data class ToolCall(
        val id: String,
        val type: String,
        val function: ToolCallFunction
      ) {
        @Serializable
        data class ToolCallFunction(
          val name: String,
          val arguments: String
        )
      }
    }
  }
}

@Serializable
data class UserOpenAiMessage(
  val role: String,
  val content: String,
  val metadata: UserOpenAiMessageMetadata? = null
) {
  @Serializable
  data class UserOpenAiMessageMetadata(
    val username: String,
    val mention: String
  )
}

@Serializable
data class SubmitToolOutputs(
  @SerialName("tool_outputs")
  val toolOutputs: Array<ToolOutput>
) {
  @Serializable
  data class ToolOutput(
    @SerialName("tool_call_id")
    val toolCallId: String,
    val output: String
  )
}

@Serializable
data class AssistantOpenAi(
  @SerialName("assistant_id")
  val assistantId: String,
  @SerialName("additional_instructions")
  val additionalInstructions: String? = null
)

@Serializable
data class AssistantOpenAiRun(
  val id: String,
)

// Helpers
suspend fun updateOrAddRole(
  roleId: Snowflake?,
  member: Member,
  roleName: String,
  roleColor: Color?,
  roleHoist: Boolean
): CustomRole =
  if (roleId != null) {
    val updatedRole = member.guild.getRole(roleId).edit {
      name = roleName
      color = roleColor
      mentionable = true
      hoist = roleHoist
    }
    member.addRole(updatedRole.id)

    CustomRole(
      id = updatedRole.id.toString(),
      name = updatedRole.name,
      color = updatedRole.color
    )
  } else {
    val discordPersonalRole = member.guild.createRole {
      name = roleName
      color = roleColor
      mentionable = true
      hoist = roleHoist
    }
    member.addRole(discordPersonalRole.id)

    CustomRole(
      id = discordPersonalRole.id.toString(),
      name = discordPersonalRole.name,
      color = discordPersonalRole.color
    )
  }

suspend fun getUser(
  mongoClient: MongoClient,
  userReference: String,
  name: String? = null
): User {
  val database = mongoClient.getDatabase("k9")
  val collection = database.getCollection<User>("users")

  return try {
    var user = collection.find(eq("discordId", userReference)).firstOrNull()

    if (user == null) {
      val userDocument = User(
        id = ObjectId(),
        discordId = userReference,
        name = name,
      )
      val result = collection.insertOne(userDocument)
      user = collection.find(eq("_id", result.insertedId)).first()
    }

    if (name != null && user.name != name) {
      val updatedUser = updateUser(mongoClient, user.copy(name = name))
      user = collection.find(eq("_id", updatedUser.upsertedId)).first()
    }

    return user
  } catch (e: MongoException) {
    e.printStackTrace()
    throw e
  }
}

suspend fun getDigimonOwnedByUser(
  mongoClient: MongoClient,
  digimonId: Int
): List<User> {
  val database = mongoClient.getDatabase("k9")
  val collection = database.getCollection<User>("users")

  return collection.aggregate(
    listOf(
      match(exists("digimon", true)),
      match(ne("digimon.id", null)),
      match(eq("digimon.id", digimonId))
    )
  ).toList()
}

suspend fun getUsersWithDickSize(mongoClient: MongoClient): List<User> {
  val database = mongoClient.getDatabase("k9")
  val collection = database.getCollection<User>("users")

  return collection.aggregate(
    listOf(
      match(exists("dickSize", true)),
      match(ne("dickSize.size", null)),
      sort(descending("dickSize.size", "dickSize.latestGeneratedAt"))
    )
  ).toList()
}

suspend fun getUsersThatWonDickSize(mongoClient: MongoClient): List<User> {
  val database = mongoClient.getDatabase("k9")
  val collection = database.getCollection<User>("users")

  return collection.aggregate(
    listOf(
      match(exists("winnerDickSizeTimes", true)),
      match(ne("winnerDickSizeTimes", null)),
      match(gt("winnerDickSizeTimes", 0)),
      sort(descending("winnerDickSizeTimes"))
    )
  ).toList()
}

suspend fun updateUser(
  mongoClient: MongoClient,
  user: User
): UpdateResult {
  val database = mongoClient.getDatabase("k9")
  val collection = database.getCollection<User>("users")

  val updates = combine(
    set("name", user.name),
    set("personalRole", user.personalRole),
    set("dickSize", user.dickSize),
    set("winnerDickSizeTimes", user.winnerDickSizeTimes),
    set("digimon", user.digimon),
    set("digimonCooldown", user.digimonCooldown)
  )

  val options = UpdateOptions().upsert(true)
  return collection.updateOne(eq("_id", user.id), updates, options)
}

// Models
data class CustomRole(
  val id: String,
  val name: String,
  val color: Color
)

data class DickSize(
  val role: CustomRole,
  val size: Int,
  val latestGeneratedAt: LocalDateTime
)

data class Digimon(val id: Int)

data class User(
  @BsonId val id: ObjectId,
  val discordId: String,
  val name: String? = null,
  val personalRole: CustomRole? = null,
  val dickSize: DickSize? = null,
  val winnerDickSizeTimes: Int? = 0,
  val digimon: List<Digimon>? = emptyList(),
  val digimonCooldown: LocalDateTime? = null
)
