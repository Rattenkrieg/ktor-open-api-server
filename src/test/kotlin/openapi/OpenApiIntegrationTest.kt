@file:OptIn(ExperimentalSerializationApi::class)

package openapi

import openapi.oas.OpenApiSpec
import openapi.oas.Info
import openapi.schema.EnumDefinition
import openapi.schema.TypeDefinition
import openapi.schema.UnsupportedSchemaException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

class NoParamsPayload : RequestPayload

data class ResultBodyPayload(val body: Body<DescAnnotatedResult>) : RequestPayload

data class ResultResponse(val body: ResponseBody<DescAnnotatedResult>) : OkResponsePayload()

data class NestedOuterResponse(val body: ResponseBody<DescNestedOuter>) : OkResponsePayload()

data class NestedInnerResponse(val body: ResponseBody<DescNestedOuter.Inner>) : OkResponsePayload()

data class HierarchySquatterResponse(val body: ResponseBody<DescHierarchySlugSquatter>) : OkResponsePayload()

data class PlainSquatterResponse(val body: ResponseBody<DescPlainSlugSquatter>) : OkResponsePayload()

@Serializable
data class LeafHolder(val leaf: DescNestedOuter.Inner.Leaf)

data class LeafHolderResponse(val body: ResponseBody<LeafHolder>) : OkResponsePayload()

data class StatusOutcomeResponse(val body: ResponseBody<DescStatusOutcome>) : OkResponsePayload()

data class StateOutcomeResponse(val body: ResponseBody<DescStateOutcome>) : OkResponsePayload()

data class ExprResponse(val body: ResponseBody<DescExpr>) : OkResponsePayload()

data class DottedResponse(val body: ResponseBody<DescDottedOuter>) : OkResponsePayload()

@Serializable
data class UnnamedHolder(val variant: DescUnnamedVariants)

data class UnnamedHolderResponse(val body: ResponseBody<UnnamedHolder>) : OkResponsePayload()

// Reading this descriptor fails, so descriptor-based generation stops half way through NoDescriptorHolder.
object NoDescriptorSerializer : KSerializer<NoDescriptor> {
    override val descriptor: SerialDescriptor get() = error("no descriptor")
    override fun serialize(encoder: Encoder, value: NoDescriptor) = TODO("not used in test")
    override fun deserialize(decoder: Decoder): NoDescriptor = TODO("not used in test")
}

@Serializable(with = NoDescriptorSerializer::class)
data class NoDescriptor(val value: Int)

@Serializable
data class NoDescriptorHolder(val inner: NoDescriptor)

data class NoDescriptorResponse(val body: ResponseBody<NoDescriptorHolder>) : OkResponsePayload()

data class ClashingResponse(val body: ResponseBody<DescClashingVariant>) : OkResponsePayload()

@Serializable
sealed interface GenericOutcome<out T> {
    @Serializable
    @SerialName("OK")
    data class Ok<T>(val value: T) : GenericOutcome<T>

    @Serializable
    @SerialName("ERR")
    data object Err : GenericOutcome<Nothing>
}

data class GenericOutcomeResponse(val body: ResponseBody<GenericOutcome<Int>>) : OkResponsePayload()

class OpenApiIntegrationTest : ShouldSpec({

    fun openApiSpec() = OpenApiSpec(
        info = Info(title = "HC API", version = "0.0.1", description = "Integration test"),
    )

    fun JsonObject.at(vararg keys: String): JsonElement =
        keys.fold<String, JsonElement>(this) { element, key -> element.jsonObject.getValue(key) }

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
                serveOpenApiSpec("/openapi.json")
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

    should("serve spec at custom path via serveOpenApiSpec") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                serveOpenApiSpec("/api/docs/spec.json")
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

    should("not serve spec when serveOpenApiSpec is not called") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
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

    should("document a sealed request and response body under the discriminator kotlinx reads and writes") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                serveOpenApiSpec("/openapi.json")
                post<ResultBodyPayload, ResultResponse>("/results") {
                    ResultResponse(ResponseBody(payload.body.value()))
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val schemas = specJson.getValue("components").jsonObject.getValue("schemas").jsonObject
            schemas.getValue("CREATED") shouldBe Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {"id": {"type": "string"}, "status": {"enum": ["CREATED"]}},
                  "required": ["id", "status"]
                }
                """,
            )
            schemas.getValue("PENDING") shouldBe Json.parseToJsonElement(
                """{"type":"object","properties":{"status":{"enum":["PENDING"]}},"required":["status"]}""",
            )
            val resultSchema = Json.parseToJsonElement(
                """
                {"anyOf": [
                  {"${'$'}ref": "#/components/schemas/CREATED"},
                  {"${'$'}ref": "#/components/schemas/FAILED"},
                  {"${'$'}ref": "#/components/schemas/PENDING"}
                ]}
                """,
            )
            val operation = specJson.at("paths", "/results", "post").jsonObject
            operation.at("requestBody", "content", "application/json", "schema") shouldBe resultSchema
            operation.at("responses", "200", "content", "application/json", "schema") shouldBe resultSchema
            val response = client.post("/results") {
                contentType(ContentType.Application.Json)
                setBody("""{"status":"CREATED","id":"1"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()) shouldBe
                Json.parseToJsonElement("""{"status":"CREATED","id":"1"}""")
        }
    }

    should("export the same spec in every order of routes that share or squat a sealed variant's names") {
        val registrations = mapOf<String, Route.() -> Unit>(
            "outer" to {
                get<NoParamsPayload, NestedOuterResponse>("/outer") {
                    NestedOuterResponse(ResponseBody(DescNestedOuter.Other(1)))
                }
            },
            "inner" to {
                get<NoParamsPayload, NestedInnerResponse>("/inner") {
                    NestedInnerResponse(ResponseBody(DescNestedOuter.Inner.Leaf(1)))
                }
            },
            "squatter" to {
                get<NoParamsPayload, HierarchySquatterResponse>("/squatter") {
                    HierarchySquatterResponse(ResponseBody(DescHierarchySlugSquatter("x")))
                }
            },
            "holder" to {
                get<NoParamsPayload, LeafHolderResponse>("/holder") {
                    LeafHolderResponse(ResponseBody(LeafHolder(DescNestedOuter.Inner.Leaf(1))))
                }
            },
            "plainSquatter" to {
                get<NoParamsPayload, PlainSquatterResponse>("/plain-squatter") {
                    PlainSquatterResponse(ResponseBody(DescPlainSlugSquatter("x")))
                }
            },
            "results" to {
                get<NoParamsPayload, ResultResponse>("/results") {
                    ResultResponse(ResponseBody(DescAnnotatedResult.Pending))
                }
            },
        )
        fun permutations(names: List<String>): List<List<String>> = when (names.size) {
            0, 1 -> listOf(names)
            else -> names.flatMap { head -> permutations(names - head).map { listOf(head) + it } }
        }
        fun exportedSpec(order: List<String>): JsonObject {
            var exported = JsonObject(mapOf())
            testApplication {
                install(ContentNegotiation) { json() }
                install(OpenApi) {
                    spec = openApiSpec()
                }
                routing {
                    serveOpenApiSpec("/openapi.json")
                    order.forEach { registrations.getValue(it)(this) }
                }
                exported = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            }
            return exported
        }
        fun sameSpecInEveryOrder(names: List<String>): JsonObject {
            val orders = permutations(names)
            orders shouldHaveSize 24
            val first = exportedSpec(orders.first())
            orders.drop(1).forEach { order -> withClue(order) { exportedSpec(order) shouldBe first } }
            return first.at("components", "schemas").jsonObject
        }
        val leafKind = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {"value": {"type": "number", "format": "int32"}, "kind": {"enum": ["LEAF"]}},
              "required": ["value", "kind"]
            }
            """,
        )
        val leafType = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {"value": {"type": "number", "format": "int32"}, "type": {"enum": ["LEAF"]}},
              "required": ["value", "type"]
            }
            """,
        )
        val squatter = Json.parseToJsonElement(
            """{"type": "object", "properties": {"other": {"type": "string"}}, "required": ["other"]}""",
        )
        val shared = sameSpecInEveryOrder(listOf("outer", "inner", "squatter", "holder"))
        shared.getValue("LEAF") shouldBe leafKind
        shared.getValue("DescNestedOuter.LEAF") shouldBe leafType
        shared.getValue("DescNestedOuterLEAF") shouldBe squatter
        shared.getValue("LeafHolder") shouldBe Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {"leaf": {"${'$'}ref": "#/components/schemas/LEAF"}},
              "required": ["leaf"]
            }
            """,
        )
        val squatted = sameSpecInEveryOrder(listOf("outer", "plainSquatter", "squatter", "results"))
        squatted.getValue("LEAF") shouldBe Json.parseToJsonElement(
            """{"type": "object", "properties": {"another": {"type": "string"}}, "required": ["another"]}""",
        )
        squatted.getValue("DescNestedOuter.LEAF") shouldBe leafType
        squatted.getValue("DescNestedOuterLEAF") shouldBe squatter
    }

    should("give same-named variants of parents with different discriminators their own components") {
        fun exportedSchemas(statusFirst: Boolean): JsonObject {
            var exported = JsonObject(mapOf())
            testApplication {
                install(ContentNegotiation) { json() }
                install(OpenApi) {
                    spec = openApiSpec()
                }
                routing {
                    serveOpenApiSpec("/openapi.json")
                    val registrations = listOf<Route.() -> Unit>(
                        {
                            get<NoParamsPayload, StatusOutcomeResponse>("/status") {
                                StatusOutcomeResponse(ResponseBody(DescStatusOutcome.None))
                            }
                        },
                        {
                            get<NoParamsPayload, StateOutcomeResponse>("/state") {
                                StateOutcomeResponse(ResponseBody(DescStateOutcome.None))
                            }
                        },
                    )
                    (if (statusFirst) registrations else registrations.reversed()).forEach { it() }
                }
                exported = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
                    .at("components", "schemas").jsonObject
            }
            return exported
        }
        val statusTag = Json.parseToJsonElement(
            """{"type": "object", "properties": {"status": {"enum": ["NONE"]}}, "required": ["status"]}""",
        )
        val stateTag = Json.parseToJsonElement(
            """{"type": "object", "properties": {"state": {"enum": ["NONE"]}}, "required": ["state"]}""",
        )
        val statusFirst = exportedSchemas(statusFirst = true)
        statusFirst.getValue("NONE") shouldBe statusTag
        statusFirst.getValue("DescStateOutcomeNONE") shouldBe stateTag
        val stateFirst = exportedSchemas(statusFirst = false)
        stateFirst.getValue("NONE") shouldBe stateTag
        stateFirst.getValue("DescStatusOutcomeNONE") shouldBe statusTag
    }

    should("document a recursive sealed body whose variant is tagged by an outer parent") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                serveOpenApiSpec("/openapi.json")
                get<NoParamsPayload, ExprResponse>("/expr") {
                    ExprResponse(ResponseBody(DescExpr.Lit(1)))
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val expr = Json.parseToJsonElement(
                """
                {"anyOf": [
                  {"${'$'}ref": "#/components/schemas/DescExpr.ADD"},
                  {"${'$'}ref": "#/components/schemas/LIT"},
                  {"${'$'}ref": "#/components/schemas/NEG"}
                ]}
                """,
            )
            val response = specJson.at("paths", "/expr", "get", "responses", "200", "content", "application/json")
            response.jsonObject.getValue("schema") shouldBe expr
            specJson.at("components", "schemas", "DescExpr.ADD") shouldBe Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {"left": $expr, "right": $expr, "type": {"enum": ["ADD"]}},
                  "required": ["left", "right", "type"]
                }
                """,
            )
        }
    }

    should("document foreign-tagged variants whose short names collide in separate components") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                serveOpenApiSpec("/openapi.json")
                get<NoParamsPayload, DottedResponse>("/dotted") {
                    DottedResponse(ResponseBody(DescDottedOuter.Other(1)))
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val schemas = specJson.at("components", "schemas").jsonObject
            schemas.getValue("DescDottedOuter.Leaf") shouldBe Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {"x": {"type": "number", "format": "int32"}, "type": {"enum": ["x.Leaf"]}},
                  "required": ["x", "type"]
                }
                """,
            )
            schemas.getValue("DescDottedOuter.yLeaf") shouldBe Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {"y": {"type": "string"}, "type": {"enum": ["y.Leaf"]}},
                  "required": ["y", "type"]
                }
                """,
            )
        }
    }

    should("fail startup when a customTypes override tags a sealed variant differently") {
        val foreignTag = mapOf("type" to EnumDefinition(enum = setOf("OTHER")))
        val conflict = shouldThrow<UnsupportedSchemaException> {
            testApplication {
                install(ContentNegotiation) { json() }
                install(OpenApi) {
                    spec = openApiSpec()
                    customTypes = mapOf(
                        typeOf<DescUnnamedVariants.First>() to
                            TypeDefinition(type = "object", properties = foreignTag, required = foreignTag.keys),
                    )
                }
                routing {
                    get<NoParamsPayload, UnnamedHolderResponse>("/unnamed") {
                        UnnamedHolderResponse(ResponseBody(UnnamedHolder(DescUnnamedVariants.First(1))))
                    }
                }
                startApplication()
            }
        }
        conflict.message shouldContain "GET /unnamed"
        conflict.message shouldContain "'openapi.DescUnnamedVariants.First'"
    }

    should("emit no half-built component when the reflection path takes over") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                serveOpenApiSpec("/openapi.json")
                get<NoParamsPayload, NoDescriptorResponse>("/no-descriptor") {
                    NoDescriptorResponse(ResponseBody(NoDescriptorHolder(NoDescriptor(1))))
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val schemas = specJson.at("components", "schemas").jsonObject
            schemas.filter { (name, schema) ->
                schema == Json.parseToJsonElement("""{"${'$'}ref": "#/components/schemas/$name"}""")
            }.keys shouldBe setOf()
            schemas.getValue("NoDescriptorHolder").jsonObject.at("properties").jsonObject.keys shouldBe setOf("inner")
        }
    }

    should("fail startup when a sealed body cannot be described") {
        val clash = shouldThrow<UnsupportedSchemaException> {
            testApplication {
                install(ContentNegotiation) { json() }
                install(OpenApi) {
                    spec = openApiSpec()
                }
                routing {
                    get<NoParamsPayload, ClashingResponse>("/clashing") {
                        ClashingResponse(ResponseBody(DescClashingVariant.Clash("x")))
                    }
                }
                startApplication()
            }
        }
        val refusal = "Cannot describe sealed variant 'CLASH': " +
            "kotlinx refuses to encode its property 'status' next to the tag"
        clash.message shouldBe "Cannot document GET /clashing: $refusal"
        generateSequence<Throwable>(clash) { it.cause }.map { it.message }.toList() shouldContain refusal
        shouldThrow<UnsupportedSchemaException> {
            testApplication {
                install(ContentNegotiation) { json() }
                install(OpenApi) {
                    spec = openApiSpec()
                    json = Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE }
                }
                routing {
                    get<NoParamsPayload, GenericOutcomeResponse>("/outcome") {
                        GenericOutcomeResponse(ResponseBody(GenericOutcome.Ok(1)))
                    }
                }
                startApplication()
            }
        }
    }
})
