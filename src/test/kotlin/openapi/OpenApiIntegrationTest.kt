package openapi

import openapi.oas.OpenApiSpec
import openapi.oas.Info
import openapi.schema.TypeDefinition
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.typeOf

// Simulates hc-server patterns: Principal-only payloads, response headers, nested routes

@Serializable
data class ApiUserData(val email: String, val name: String, val role: String)

@Serializable
data class PatchUserBody(val name: String?)

data class AuthOnly(
    val auth: Principal<String>,
) : RequestPayload

data class PatchMePayload(
    val auth: Principal<String>,
    val body: Body<PatchUserBody>,
) : RequestPayload

data class GetMeResp(
    val body: ResponseBody<ApiUserData>,
    @Suppress("ConstructorParameterNaming")
    val `intercom-token`: ResponseHeader,
) : OkResponsePayload()

data class CompanyIdPayload(
    val companyId: PathParam,
    val auth: Principal<String>,
) : RequestPayload

@Serializable
data class TaskListResponse(val tasks: List<String>) : OkResponsePayload()

class OpenApiIntegrationTest : ShouldSpec({

    fun openApiSpec() = OpenApiSpec(
        info = Info(title = "HC API", version = "0.0.1", description = "Integration test"),
    )

    should("generate spec for principal-only payload and response with headers") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
                customTypes = mapOf(
                    typeOf<LocalDate>() to TypeDefinition(type = "string", format = "date-time"),
                    typeOf<Instant>() to TypeDefinition(type = "string", format = "date-time"),
                )
            }
            routing {
                route("/users/me") {
                    get<AuthOnly, GetMeResp> {
                        GetMeResp(
                            body = ResponseBody(ApiUserData("a@b.com", "User", "admin")),
                            `intercom-token` = ResponseHeader("token-123"),
                        )
                    }
                    patch<PatchMePayload, NoContent> {
                        NoContent
                    }
                }
                route("/companies/{companyId}/tasks") {
                    get<CompanyIdPayload, TaskListResponse> {
                        TaskListResponse(listOf("task1"))
                    }
                }
            }
            val specText = client.get("/openapi.json").bodyAsText()
            val specJson = Json.decodeFromString<JsonObject>(specText)
            specJson["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"
            specJson["info"]?.jsonObject?.get("title")?.jsonPrimitive?.content shouldBe "HC API"
            // Custom types should appear in components
            val schemas = specJson["components"]?.jsonObject?.get("schemas")?.jsonObject
            schemas.shouldNotBeNull()
            schemas["LocalDate"].shouldNotBeNull()
            schemas["Instant"].shouldNotBeNull()
            // /users/me GET should have no parameters (Principal is internal)
            val meGet = specJson["paths"]?.jsonObject
                ?.get("/users/me")?.jsonObject
                ?.get("get")?.jsonObject
            meGet.shouldNotBeNull()
            // Principal params are excluded — should have no params
            meGet["parameters"] shouldBe null
            // Response should have headers
            val meGetResp = meGet["responses"]?.jsonObject?.get("200")?.jsonObject
            meGetResp.shouldNotBeNull()
            val headers = meGetResp["headers"]?.jsonObject
            headers.shouldNotBeNull()
            headers["intercom-token"].shouldNotBeNull()
            // Response body should reference ApiUserData
            val content = meGetResp["content"]?.jsonObject?.get("application/json")?.jsonObject
            content.shouldNotBeNull()
            // /users/me PATCH should have a request body
            val mePatch = specJson["paths"]?.jsonObject
                ?.get("/users/me")?.jsonObject
                ?.get("patch")?.jsonObject
            mePatch.shouldNotBeNull()
            mePatch["requestBody"].shouldNotBeNull()
            mePatch["parameters"] shouldBe null
            // /companies/{companyId}/tasks GET should have companyId path param
            val tasksGet = specJson["paths"]?.jsonObject
                ?.get("/companies/{companyId}/tasks")?.jsonObject
                ?.get("get")?.jsonObject
            tasksGet.shouldNotBeNull()
            val taskParams = tasksGet["parameters"]?.jsonArray
            taskParams.shouldNotBeNull()
            val paramNames = taskParams.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            paramNames shouldBe listOf("companyId")
            // Schemas should include ApiUserData and PatchUserBody
            schemas["ApiUserData"].shouldNotBeNull()
            schemas["PatchUserBody"].shouldNotBeNull()
            schemas["TaskListResponse"].shouldNotBeNull()
            // Pretty-print for manual inspection
            specText shouldContain "\"openapi\""
        }
    }

    should("serve spec at custom path") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
                specPath = "/api/docs/spec.json"
            }
            routing {
                route("/health") {
                    get<AuthOnly, NoContent> {
                        NoContent
                    }
                }
            }
            val response = client.get("/api/docs/spec.json")
            response.status shouldBe HttpStatusCode.OK
            val specJson = Json.decodeFromString<JsonObject>(response.bodyAsText())
            specJson["paths"]?.jsonObject?.get("/health")?.jsonObject?.get("get").shouldNotBeNull()
        }
    }

    should("produce no spec route when specPath is null") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
                specPath = null
            }
            routing {
                route("/health") {
                    get<AuthOnly, NoContent> {
                        NoContent
                    }
                }
            }
            val response = client.get("/openapi.json")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})
