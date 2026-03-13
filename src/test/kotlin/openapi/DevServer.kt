package openapi.dev

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import openapi.Body
import openapi.CreatedResponsePayload
import openapi.NoContent
import openapi.Ok
import openapi.OkResponsePayload
import openapi.OpenApi
import openapi.PathParam
import openapi.Principal
import openapi.QueryParam
import openapi.RequestPayload
import openapi.ResponseBody
import openapi.ResponseHeader
import openapi.oas.Info
import openapi.oas.OpenApiSpec
import openapi.oas.Server
import openapi.schema.TypeDefinition
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.typeOf
import openapi.get as typedGet
import openapi.post as typedPost
import openapi.patch as typedPatch
import openapi.delete as typedDelete

// --- Domain types ---

@Serializable
data class UserData(val email: String, val name: String, val role: String)

@Serializable
data class CreateUserRequest(val email: String, val name: String)

@Serializable
data class PatchUserRequest(val name: String? = null, val role: String? = null)

@Serializable
data class TopupOptions(val options: List<TopupOption>)

@Serializable
data class TopupOption(val provider: String, val minAmount: Long, val maxAmount: Long)

@Serializable
data class TaskItem(val id: String, val title: String, val completed: Boolean)

@Serializable
data class CompanyInfo(val id: String, val name: String, val createdAt: String) : OkResponsePayload()

// --- Payloads ---

data class AuthOnlyPayload(
    val auth: Principal<String>,
) : RequestPayload

data class CreateUserPayload(
    val auth: Principal<String>,
    val body: Body<CreateUserRequest>,
) : RequestPayload

data class PatchMePayload(
    val auth: Principal<String>,
    val body: Body<PatchUserRequest>,
) : RequestPayload

data class GetTopupOptionsPayload(
    val accountId: QueryParam,
    val amount: QueryParam,
    val currency: QueryParam?,
) : RequestPayload

data class CompanyPathPayload(
    val companyId: PathParam,
    val auth: Principal<String>,
) : RequestPayload

data class DeleteUserPayload(
    val companyId: PathParam,
    val userId: PathParam,
) : RequestPayload

// --- Response types ---

data class GetMeResponse(
    val body: ResponseBody<UserData>,
    @Suppress("ConstructorParameterNaming")
    val `X-Intercom-Token`: ResponseHeader,
) : OkResponsePayload()

data class CreatedUserResponse(
    val body: ResponseBody<UserData>,
) : CreatedResponsePayload()

// --- Swagger UI ---

private val swaggerHtml = """
<!DOCTYPE html>
<html>
<head>
    <title>API Docs</title>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script>
        SwaggerUIBundle({
            url: "/openapi.json",
            dom_id: '#swagger-ui',
            deepLinking: true,
            presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
            layout: "BaseLayout",
        })
    </script>
</body>
</html>
""".trimIndent()

// --- Main ---

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(OpenApi) {
            spec = OpenApiSpec(
                info = Info(
                    title = "Sayhello.cash API",
                    version = "0.0.1",
                    summary = "Demo API showing typed routing OpenAPI generation",
                    description = "Auto-generated from Kotlin typed route definitions",
                ),
                servers = mutableListOf(
                    Server(url = "http://localhost:8080", description = "Local dev"),
                ),
            )
            customTypes = mapOf(
                typeOf<LocalDate>() to TypeDefinition(type = "string", format = "date"),
                typeOf<Instant>() to TypeDefinition(type = "string", format = "date-time"),
                typeOf<UUID>() to TypeDefinition(type = "string", format = "uuid"),
            )
        }
        routing {
            get("/swagger") {
                call.respondText(swaggerHtml, ContentType.Text.Html)
            }
            route("/users/me") {
                typedGet<AuthOnlyPayload, GetMeResponse> {
                    GetMeResponse(
                        body = ResponseBody(UserData("user@example.com", "Jane Doe", "admin")),
                        `X-Intercom-Token` = ResponseHeader("intercom-token-value"),
                    )
                }
                typedPatch<PatchMePayload, NoContent> {
                    NoContent
                }
            }
            route("/users") {
                typedPost<CreateUserPayload, CreatedUserResponse> {
                    CreatedUserResponse(
                        body = ResponseBody(UserData("new@example.com", "New User", "member")),
                    )
                }
            }
            typedGet<GetTopupOptionsPayload, Ok<TopupOptions>>("/topup-options") {
                Ok(
                    TopupOptions(
                        listOf(TopupOption("card", 100, 100000), TopupOption("bank", 500, 500000)),
                    ),
                )
            }
            route("/companies/{companyId}") {
                typedGet<CompanyPathPayload, CompanyInfo> {
                    CompanyInfo(payload.companyId.value, "Acme Corp", "2024-01-15")
                }
                route("/tasks") {
                    typedGet<CompanyPathPayload, Ok<List<TaskItem>>> {
                        Ok(listOf(TaskItem("1", "Onboarding", false), TaskItem("2", "KYC", true)))
                    }
                }
                route("/users/{userId}") {
                    typedDelete<DeleteUserPayload, NoContent> {
                        NoContent
                    }
                }
            }
        }
    }.start(wait = true)
}
