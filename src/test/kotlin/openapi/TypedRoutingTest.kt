package openapi

import openapi.oas.OpenApiSpec
import openapi.oas.Info
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
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

@Serializable
data class UserData(val email: String, val name: String)

@Serializable
data class UserResponse(val id: String, val email: String, val name: String) : OkResponsePayload()

@Serializable
data class CreatedUserDirectResponse(val id: String, val email: String, val name: String) : CreatedResponsePayload()

data class CreateUserPayload(
    val companyId: PathParam,
    val body: Body<UserData>,
) : RequestPayload

data class GetUsersPayload(
    val companyId: PathParam,
    val status: QueryParam?,
) : RequestPayload

data class PayloadWithHeader(
    val companyId: PathParam,
    @Suppress("ConstructorParameterNaming")
    val `X-Idempotency-Key`: HeaderParam,
    val body: Body<UserData>,
) : RequestPayload

data class DeleteUserPayload(
    val companyId: PathParam,
    val id: PathParam,
) : RequestPayload

data class CreatedUserResponse(
    val body: ResponseBody<UserResponse>,
) : CreatedResponsePayload()

data class UserResponseWithHeader(
    val body: ResponseBody<UserResponse>,
    @Suppress("ConstructorParameterNaming")
    val `X-Request-Id`: ResponseHeader,
) : OkResponsePayload()

data class StreamPayload(
    val id: PathParam,
) : RequestPayload

data class AcceptPayload(
    val id: PathParam,
    val accept: AcceptHeader,
) : RequestPayload

data class OriginPayload(
    val id: PathParam,
    val origin: RequestOrigin,
) : RequestPayload

data class MultipartPayload(
    val id: PathParam,
    val multipart: MultipartBody,
) : RequestPayload

data class NamedQueryPayload(
    val id: PathParam,
    @Name("flow_type[]") val flowType: QueryParamList?,
    @Name("company[]") val company: QueryParamList?,
    val orderBy: QueryParam?,
) : RequestPayload

data class RenamedHeaderPayload(
    val id: PathParam,
    @Name("X-Custom-Header") val customHeader: HeaderParam,
) : RequestPayload

data class NullableBodyPayload(
    val id: PathParam,
    val body: Body<UserData>?,
) : RequestPayload

data class CookiePayload(
    val id: PathParam,
    @Name("session_id") val session: CookieParam,
    val tracking: CookieParam?,
) : RequestPayload

data class RedirectPayload(
    val id: PathParam,
) : RequestPayload

@Serializable
data class ErrorDetail(val code: String, val message: String)

sealed interface ExternalLinkResult : ResponsePayload {
    class Redirect(url: String) : RedirectResponse(url, permanent = false), ExternalLinkResult
    object Missing : NotFoundResponsePayload(), ExternalLinkResult
}

sealed interface ProcessResult : ResponsePayload {
    data class Success(val body: ResponseBody<UserResponse>) : CreatedResponsePayload(), ProcessResult {
        constructor(value: UserResponse) : this(ResponseBody(value))
    }
    data class Invalid(val body: ResponseBody<ErrorDetail>) : BadRequestResponsePayload(), ProcessResult {
        constructor(value: ErrorDetail) : this(ResponseBody(value))
    }
    data class Missing(val body: ResponseBody<ErrorDetail>) : NotFoundResponsePayload(), ProcessResult {
        constructor(value: ErrorDetail) : this(ResponseBody(value))
    }
    object Failed : InternalServerErrorResponsePayload(), ProcessResult
}

sealed interface SimpleResult : ResponsePayload {
    object Done : NoContentResponsePayload(), SimpleResult
    data class Error(val body: ResponseBody<ErrorDetail>) : BadRequestResponsePayload(), SimpleResult {
        constructor(value: ErrorDetail) : this(ResponseBody(value))
    }
}

class TypedRoutingTest : ShouldSpec({

    fun openApiSpec() = OpenApiSpec(info = Info(title = "Test API", version = "1.0.0"))

    should("extract body and path param from typedPost") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<CreateUserPayload, UserResponse> {
                        UserResponse(
                            id = "generated-id",
                            email = payload.body.value().email,
                            name = payload.body.value().name
                        )
                    }
                }
            }
            val response = client.post("/api/v1/companies/abc-123/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"test@example.com","name":"Test User"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<UserResponse>(response.bodyAsText())
            body.id shouldBe "generated-id"
            body.email shouldBe "test@example.com"
            body.name shouldBe "Test User"
        }
    }

    should("extract path and query params from typedGet") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedGet<GetUsersPayload, Ok<List<UserResponse>>> {
                        val users = listOf(UserResponse("1", "a@b.com", "User1"))
                        Ok(if (payload.status?.value == "active") users else listOf())
                    }
                }
            }
            val response = client.get("/api/v1/companies/abc-123/users?status=active")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "User1"
        }
    }

    should("extract header param and include it in spec") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<PayloadWithHeader, UserResponse> {
                        UserResponse(
                            id = payload.`X-Idempotency-Key`.value,
                            email = payload.body.value().email,
                            name = payload.body.value().name
                        )
                    }
                }
            }
            val response = client.post("/api/v1/companies/abc-123/users") {
                contentType(ContentType.Application.Json)
                header("X-Idempotency-Key", "idem-123")
                setBody("""{"email":"test@example.com","name":"Test"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            Json.decodeFromString<UserResponse>(response.bodyAsText()).id shouldBe "idem-123"
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val params = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users")?.jsonObject
                ?.get("post")?.jsonObject
                ?.get("parameters")?.jsonArray
            params.shouldNotBeNull()
            val headerNames = params.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            headerNames shouldContain "X-Idempotency-Key"
        }
    }

    @OptIn(RawCallAccess::class)
    should("allow raw call access via opt-in") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedGet<GetUsersPayload, Ok<List<UserResponse>>> {
                        @OptIn(RawCallAccess::class)
                        val userAgent = call.request.headers["User-Agent"] ?: "unknown"
                        Ok(listOf(UserResponse("1", userAgent, "User1")))
                    }
                }
            }
            val response = client.get("/api/v1/companies/abc-123/users") {
                header("User-Agent", "test-agent")
            }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "test-agent"
        }
    }

    should("use custom status code at runtime and in spec") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<CreateUserPayload, CreatedUserDirectResponse> {
                        CreatedUserDirectResponse("1", payload.body.value().email, payload.body.value().name)
                    }
                }
            }
            val response = client.post("/api/v1/companies/abc-123/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.com","name":"Test"}""")
            }
            response.status shouldBe HttpStatusCode.Created
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val responses = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users")?.jsonObject
                ?.get("post")?.jsonObject
                ?.get("responses")?.jsonObject
            responses.shouldNotBeNull()
            responses["201"].shouldNotBeNull()
        }
    }

    should("return status code with no body for NoContent response") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users/{id}") {
                    typedDelete<DeleteUserPayload, NoContent> {
                        NoContent
                    }
                }
            }
            val response = client.delete("/api/v1/companies/abc-123/users/user-456")
            response.status shouldBe HttpStatusCode.NoContent
            response.bodyAsText() shouldBe ""
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val resp204 = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users/{id}")?.jsonObject
                ?.get("delete")?.jsonObject
                ?.get("responses")?.jsonObject
                ?.get("204")?.jsonObject
            resp204.shouldNotBeNull()
            resp204["description"]?.jsonPrimitive?.content shouldBe "No Content"
        }
    }

    should("generate complete openapi spec for typed routes") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<CreateUserPayload, UserResponse> {
                        UserResponse("1", payload.body.value().email, payload.body.value().name)
                    }
                    typedGet<GetUsersPayload, Ok<List<UserResponse>>> {
                        Ok(listOf())
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            specJson["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"
            specJson["info"]?.jsonObject?.get("title")?.jsonPrimitive?.content shouldBe "Test API"
            val usersPath = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users")?.jsonObject
            usersPath.shouldNotBeNull()
            val postOp = usersPath["post"]?.jsonObject
            postOp.shouldNotBeNull()
            postOp["requestBody"].shouldNotBeNull()
            val postParamNames = postOp["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            postParamNames.shouldNotBeNull()
            postParamNames shouldContain "companyId"
            val getOp = usersPath["get"]?.jsonObject
            getOp.shouldNotBeNull()
            val getParamNames = getOp["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            getParamNames.shouldNotBeNull()
            getParamNames shouldContain "companyId"
            getParamNames shouldContain "status"
            val schemas = specJson["components"]?.jsonObject?.get("schemas")?.jsonObject
            schemas.shouldNotBeNull()
            schemas["UserData"].shouldNotBeNull()
            schemas["UserResponse"].shouldNotBeNull()
        }
    }

    should("return ResponsePayload with custom status code and body") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<CreateUserPayload, CreatedUserResponse> {
                        CreatedUserResponse(
                            body = ResponseBody(UserResponse("1", payload.body.value().email, payload.body.value().name))
                        )
                    }
                }
            }
            val response = client.post("/api/v1/companies/abc-123/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.com","name":"Test"}""")
            }
            response.status shouldBe HttpStatusCode.Created
            val body = Json.decodeFromString<UserResponse>(response.bodyAsText())
            body.id shouldBe "1"
            body.email shouldBe "a@b.com"
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val responses = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users")?.jsonObject
                ?.get("post")?.jsonObject
                ?.get("responses")?.jsonObject
            responses.shouldNotBeNull()
            responses["201"].shouldNotBeNull()
        }
    }

    should("set response headers from ResponsePayload") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users") {
                    typedPost<CreateUserPayload, UserResponseWithHeader> {
                        UserResponseWithHeader(
                            body = ResponseBody(UserResponse("1", payload.body.value().email, payload.body.value().name)),
                            `X-Request-Id` = ResponseHeader("req-abc-123"),
                        )
                    }
                }
            }
            val response = client.post("/api/v1/companies/abc-123/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.com","name":"Test"}""")
            }
            response.status shouldBe HttpStatusCode.OK
            response.headers["X-Request-Id"] shouldBe "req-abc-123"
            val body = Json.decodeFromString<UserResponse>(response.bodyAsText())
            body.id shouldBe "1"
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val postOp = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users")?.jsonObject
                ?.get("post")?.jsonObject
            val resp200 = postOp?.get("responses")?.jsonObject?.get("200")?.jsonObject
            resp200.shouldNotBeNull()
            val headers = resp200["headers"]?.jsonObject
            headers.shouldNotBeNull()
            headers["X-Request-Id"].shouldNotBeNull()
        }
    }

    should("return 204 NoContent for PATCH on shared route with GET") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/users/me") {
                    typedGet<GetUsersPayload, Ok<List<UserResponse>>> {
                        Ok(listOf())
                    }
                    typedPatch<CreateUserPayload, NoContent> {
                        NoContent
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val mePath = specJson["paths"]?.jsonObject?.get("/users/me")?.jsonObject
            mePath.shouldNotBeNull()
            // GET should be 200
            val getResp = mePath["get"]?.jsonObject?.get("responses")?.jsonObject
            getResp.shouldNotBeNull()
            getResp["200"].shouldNotBeNull()
            // PATCH should be 204
            val patchResp = mePath["patch"]?.jsonObject?.get("responses")?.jsonObject
            patchResp.shouldNotBeNull()
            patchResp["204"].shouldNotBeNull()
            patchResp["204"]?.jsonObject?.get("description")?.jsonPrimitive?.content shouldBe "No Content"
        }
    }

    should("return 204 NoContent for PATCH route") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users/{id}") {
                    typedPatch<CreateUserPayload, NoContent> {
                        NoContent
                    }
                }
            }
            val response = client.patch("/api/v1/companies/abc-123/users/user-456") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.com","name":"Test"}""")
            }
            response.status shouldBe HttpStatusCode.NoContent
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val patchResponses = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users/{id}")?.jsonObject
                ?.get("patch")?.jsonObject
                ?.get("responses")?.jsonObject
            patchResponses.shouldNotBeNull()
            patchResponses["204"].shouldNotBeNull()
            patchResponses["204"]?.jsonObject?.get("description")?.jsonPrimitive?.content shouldBe "No Content"
        }
    }

    should("return no content from ResponsePayload object") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/api/v1/companies/{companyId}/users/{id}") {
                    typedDelete<DeleteUserPayload, NoContent> {
                        NoContent
                    }
                }
            }
            val response = client.delete("/api/v1/companies/abc-123/users/user-456")
            response.status shouldBe HttpStatusCode.NoContent
            response.bodyAsText() shouldBe ""
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val resp204 = specJson["paths"]?.jsonObject
                ?.get("/api/v1/companies/{companyId}/users/{id}")?.jsonObject
                ?.get("delete")?.jsonObject
                ?.get("responses")?.jsonObject
                ?.get("204")?.jsonObject
            resp204.shouldNotBeNull()
        }
    }

    should("respond with ByteStreamResponse") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/export") {
                    typedGet<StreamPayload, ByteStreamResponse> {
                        ByteStreamResponse(ContentType.Application.OctetStream) {
                            write("binary-${payload.id.value}".toByteArray())
                        }
                    }
                }
            }
            val response = client.get("/items/42/export")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "binary-42"
            response.contentType()?.withoutParameters() shouldBe ContentType.Application.OctetStream
        }
    }

    should("respond with TextStreamResponse") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/csv") {
                    typedGet<StreamPayload, TextStreamResponse> {
                        TextStreamResponse(ContentType.Text.CSV) {
                            write("name,value\n")
                            write("item,${payload.id.value}\n")
                        }
                    }
                }
            }
            val response = client.get("/items/42/csv")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "name,value\nitem,42\n"
            response.contentType()?.withoutParameters() shouldBe ContentType.Text.CSV
        }
    }

    should("generate spec with no content body for stream responses") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/export") {
                    typedGet<StreamPayload, ByteStreamResponse> {
                        ByteStreamResponse(ContentType.Application.OctetStream) {
                            write("data".toByteArray())
                        }
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/export")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val resp200 = getOp["responses"]?.jsonObject?.get("200")?.jsonObject
            resp200.shouldNotBeNull()
            resp200["content"] shouldBe null
        }
    }

    should("extract AcceptHeader from request") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<AcceptPayload, Ok<Map<String, String>>> {
                        val acceptTypes = payload.accept.value.map { it.value }
                        Ok(mapOf("accept" to acceptTypes.joinToString(",")))
                    }
                }
            }
            val response = client.get("/items/42") {
                header("Accept", "text/csv, application/json")
            }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "text/csv"
        }
    }

    should("not include AcceptHeader in spec parameters") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<AcceptPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val paramNames = getOp["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            paramNames.shouldNotBeNull()
            paramNames shouldBe listOf("id")
        }
    }

    should("respondWith returns alternate response type") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<AcceptPayload, Ok<Map<String, String>>> {
                        val acceptsCsv = payload.accept.value.any { it.value == "text/csv" }
                        if (acceptsCsv) {
                            respondWith(TextStreamResponse(ContentType.Text.CSV) {
                                write("csv-data")
                            })
                        }
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val csvResponse = client.get("/items/42") {
                accept(ContentType.Text.CSV)
            }
            csvResponse.status shouldBe HttpStatusCode.OK
            csvResponse.bodyAsText() shouldBe "csv-data"
            csvResponse.contentType()?.withoutParameters() shouldBe ContentType.Text.CSV
            val jsonResponse = client.get("/items/42") {
                accept(ContentType.Application.Json)
            }
            jsonResponse.status shouldBe HttpStatusCode.OK
            jsonResponse.bodyAsText() shouldContain "42"
        }
    }

    should("extract RequestOrigin from request") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedPost<OriginPayload, Ok<Map<String, String>>> {
                        Ok(mapOf(
                            "id" to payload.id.value,
                            "remoteHost" to payload.origin.value.remoteHost,
                        ))
                    }
                }
            }
            val response = client.post("/items/42")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "42"
        }
    }

    should("not include RequestOrigin in spec parameters") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedPost<OriginPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val postOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}")?.jsonObject
                ?.get("post")?.jsonObject
            postOp.shouldNotBeNull()
            val paramNames = postOp["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            paramNames.shouldNotBeNull()
            paramNames shouldBe listOf("id")
        }
    }

    should("generate multipart/form-data request body in spec for MultipartBody") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/upload") {
                    typedPost<MultipartPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val postOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/upload")?.jsonObject
                ?.get("post")?.jsonObject
            postOp.shouldNotBeNull()
            val requestBody = postOp["requestBody"]?.jsonObject
            requestBody.shouldNotBeNull()
            val content = requestBody["content"]?.jsonObject
            content.shouldNotBeNull()
            content["multipart/form-data"].shouldNotBeNull()
        }
    }

    should("extract QueryParamList with @Name annotation") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/search") {
                    typedGet<NamedQueryPayload, Ok<Map<String, String>>> {
                        val flowTypes = payload.flowType?.value?.joinToString(",") ?: "none"
                        val companies = payload.company?.value?.joinToString(",") ?: "none"
                        val order = payload.orderBy?.value ?: "default"
                        Ok(mapOf("flowTypes" to flowTypes, "companies" to companies, "orderBy" to order))
                    }
                }
            }
            val response = client.get("/items/42/search?flow_type[]=salary&flow_type[]=bonus&company[]=acme&orderBy=name")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
            body["flowTypes"] shouldBe "salary,bonus"
            body["companies"] shouldBe "acme"
            body["orderBy"] shouldBe "name"
        }
    }

    should("generate spec with @Name annotation and QueryParamList as array") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/search") {
                    typedGet<NamedQueryPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/search")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val params = getOp["parameters"]?.jsonArray
            params.shouldNotBeNull()
            val paramMap = params.associate {
                it.jsonObject["name"]?.jsonPrimitive?.content to it.jsonObject
            }
            paramMap["flow_type[]"].shouldNotBeNull()
            paramMap["flow_type[]"]!!["schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content shouldBe "array"
            paramMap["company[]"].shouldNotBeNull()
            paramMap["company[]"]!!["schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content shouldBe "array"
            paramMap["orderBy"].shouldNotBeNull()
            paramMap["orderBy"]!!["schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content shouldBe "string"
        }
    }

    should("extract HeaderParam with @Name annotation") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<RenamedHeaderPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("header" to payload.customHeader.value))
                    }
                }
            }
            val response = client.get("/items/42") {
                header("X-Custom-Header", "header-value")
                header("Accept", "application/json")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
            body["header"] shouldBe "header-value"
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}")?.jsonObject
                ?.get("get")?.jsonObject
            val paramNames = getOp?.get("parameters")?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            paramNames.shouldNotBeNull()
            paramNames shouldContain "X-Custom-Header"
        }
    }

    should("extract nullable Body when present") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/approve") {
                    typedPost<NullableBodyPayload, Ok<Map<String, String>>> {
                        val email = payload.body?.value()?.email ?: "none"
                        Ok(mapOf("id" to payload.id.value, "email" to email))
                    }
                }
            }
            val withBody = client.post("/items/42/approve") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.com","name":"Test"}""")
            }
            withBody.status shouldBe HttpStatusCode.OK
            val body1 = Json.decodeFromString<Map<String, String>>(withBody.bodyAsText())
            body1["email"] shouldBe "a@b.com"
            val withoutBody = client.post("/items/42/approve")
            withoutBody.status shouldBe HttpStatusCode.OK
            val body2 = Json.decodeFromString<Map<String, String>>(withoutBody.bodyAsText())
            body2["email"] shouldBe "none"
        }
    }

    should("generate spec with optional requestBody for nullable Body") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/approve") {
                    typedPost<NullableBodyPayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val postOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/approve")?.jsonObject
                ?.get("post")?.jsonObject
            postOp.shouldNotBeNull()
            val requestBody = postOp["requestBody"]?.jsonObject
            requestBody.shouldNotBeNull()
            requestBody["required"]?.jsonPrimitive?.content shouldBe "false"
        }
    }

    should("extract CookieParam with @Name annotation") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<CookiePayload, Ok<Map<String, String>>> {
                        Ok(mapOf(
                            "session" to payload.session.value,
                            "tracking" to (payload.tracking?.value ?: "none"),
                        ))
                    }
                }
            }
            val response = client.get("/items/42") {
                header("Cookie", "session_id=abc123; tracking=xyz789")
                header("Accept", "application/json")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
            body["session"] shouldBe "abc123"
            body["tracking"] shouldBe "xyz789"
        }
    }

    should("extract nullable CookieParam as null when missing") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<CookiePayload, Ok<Map<String, String>>> {
                        Ok(mapOf(
                            "session" to payload.session.value,
                            "tracking" to (payload.tracking?.value ?: "none"),
                        ))
                    }
                }
            }
            val response = client.get("/items/42") {
                header("Cookie", "session_id=abc123")
                header("Accept", "application/json")
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
            body["session"] shouldBe "abc123"
            body["tracking"] shouldBe "none"
        }
    }

    should("not include CookieParam in spec parameters") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedGet<CookiePayload, Ok<Map<String, String>>> {
                        Ok(mapOf("id" to payload.id.value))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val paramNames = getOp["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            paramNames.shouldNotBeNull()
            paramNames shouldBe listOf("id")
        }
    }

    should("respond with RedirectResponse temporary") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/link") {
                    typedGet<RedirectPayload, RedirectResponse> {
                        RedirectResponse(url = "https://example.com/${payload.id.value}", permanent = false)
                    }
                }
            }
            val noFollowClient = createClient { followRedirects = false }
            val response = noFollowClient.get("/items/42/link")
            response.status shouldBe HttpStatusCode.Found
            response.headers["Location"] shouldBe "https://example.com/42"
        }
    }

    should("respond with RedirectResponse permanent") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/link") {
                    typedGet<RedirectPayload, RedirectResponse> {
                        RedirectResponse(url = "https://example.com/${payload.id.value}", permanent = true)
                    }
                }
            }
            val noFollowClient = createClient { followRedirects = false }
            val response = noFollowClient.get("/items/42/link")
            response.status shouldBe HttpStatusCode.MovedPermanently
            response.headers["Location"] shouldBe "https://example.com/42"
        }
    }

    should("generate spec with redirect status code") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/link") {
                    typedGet<RedirectPayload, RedirectResponse> {
                        RedirectResponse(url = "https://example.com", permanent = false)
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/link")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val responses = getOp["responses"]?.jsonObject
            responses.shouldNotBeNull()
            responses["302"].shouldNotBeNull()
            responses["302"]?.jsonObject?.get("content") shouldBe null
        }
    }

    should("return correct status and body for each sealed response variant") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/process") {
                    typedPost<StreamPayload, ProcessResult> {
                        when (payload.id.value) {
                            "ok" -> ProcessResult.Success(UserResponse("1", "a@b.com", "User"))
                            "bad" -> ProcessResult.Invalid(ErrorDetail("INVALID", "bad request"))
                            "missing" -> ProcessResult.Missing(ErrorDetail("NOT_FOUND", "not found"))
                            else -> ProcessResult.Failed
                        }
                    }
                }
            }
            val created = client.post("/items/ok/process")
            created.status shouldBe HttpStatusCode.Created
            Json.decodeFromString<UserResponse>(created.bodyAsText()).id shouldBe "1"
            val badRequest = client.post("/items/bad/process")
            badRequest.status shouldBe HttpStatusCode.BadRequest
            Json.decodeFromString<ErrorDetail>(badRequest.bodyAsText()).code shouldBe "INVALID"
            val notFound = client.post("/items/missing/process")
            notFound.status shouldBe HttpStatusCode.NotFound
            val failed = client.post("/items/other/process")
            failed.status shouldBe HttpStatusCode.InternalServerError
        }
    }

    should("generate spec with multiple responses for sealed response type") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/process") {
                    typedPost<StreamPayload, ProcessResult> {
                        ProcessResult.Success(UserResponse("1", "a@b.com", "User"))
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val postOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/process")?.jsonObject
                ?.get("post")?.jsonObject
            postOp.shouldNotBeNull()
            val responses = postOp["responses"]?.jsonObject
            responses.shouldNotBeNull()
            responses["201"].shouldNotBeNull()
            responses["201"]?.jsonObject?.get("content").shouldNotBeNull()
            responses["400"].shouldNotBeNull()
            responses["400"]?.jsonObject?.get("content").shouldNotBeNull()
            responses["404"].shouldNotBeNull()
            responses["404"]?.jsonObject?.get("content").shouldNotBeNull()
            responses["500"].shouldNotBeNull()
            responses["500"]?.jsonObject?.get("content") shouldBe null
        }
    }

    should("generate spec with multiple responses for simple sealed type") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    typedDelete<DeleteUserPayload, SimpleResult> {
                        SimpleResult.Done
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val deleteOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}")?.jsonObject
                ?.get("delete")?.jsonObject
            deleteOp.shouldNotBeNull()
            val responses = deleteOp["responses"]?.jsonObject
            responses.shouldNotBeNull()
            responses["204"].shouldNotBeNull()
            responses["204"]?.jsonObject?.get("content") shouldBe null
            responses["400"].shouldNotBeNull()
            responses["400"]?.jsonObject?.get("content").shouldNotBeNull()
        }
    }

    should("set Content-Disposition header on ByteStreamResponse with fileName") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/export") {
                    typedGet<StreamPayload, ByteStreamResponse> {
                        ByteStreamResponse(ContentType.Application.OctetStream, fileName = "report.zip") {
                            write("binary-data".toByteArray())
                        }
                    }
                }
            }
            val response = client.get("/items/42/export")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=report.zip"
            response.bodyAsText() shouldBe "binary-data"
        }
    }

    should("set Content-Disposition header on TextStreamResponse with fileName") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/csv") {
                    typedGet<StreamPayload, TextStreamResponse> {
                        TextStreamResponse(ContentType.Text.CSV, fileName = "report.csv") {
                            write("name,value\n")
                        }
                    }
                }
            }
            val response = client.get("/items/42/csv")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=report.csv"
            response.bodyAsText() shouldBe "name,value\n"
        }
    }

    should("not set Content-Disposition header when fileName is null") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/export") {
                    typedGet<StreamPayload, ByteStreamResponse> {
                        ByteStreamResponse(ContentType.Application.OctetStream) {
                            write("data".toByteArray())
                        }
                    }
                }
            }
            val response = client.get("/items/42/export")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentDisposition] shouldBe null
        }
    }

    should("return correct status for sealed redirect-or-404") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/external-link") {
                    typedGet<StreamPayload, ExternalLinkResult> {
                        when (payload.id.value) {
                            "exists" -> ExternalLinkResult.Redirect("https://example.com/doc")
                            else -> ExternalLinkResult.Missing
                        }
                    }
                }
            }
            val noFollowClient = createClient { followRedirects = false }
            val redirect = noFollowClient.get("/items/exists/external-link")
            redirect.status shouldBe HttpStatusCode.Found
            redirect.headers["Location"] shouldBe "https://example.com/doc"
            val missing = noFollowClient.get("/items/other/external-link")
            missing.status shouldBe HttpStatusCode.NotFound
        }
    }

    should("generate spec with 302 and 404 for sealed redirect-or-404") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}/external-link") {
                    typedGet<StreamPayload, ExternalLinkResult> {
                        ExternalLinkResult.Missing
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val getOp = specJson["paths"]?.jsonObject
                ?.get("/items/{id}/external-link")?.jsonObject
                ?.get("get")?.jsonObject
            getOp.shouldNotBeNull()
            val responses = getOp["responses"]?.jsonObject
            responses.shouldNotBeNull()
            responses["302"].shouldNotBeNull()
            responses["302"]?.jsonObject?.get("content") shouldBe null
            responses["404"].shouldNotBeNull()
            responses["404"]?.jsonObject?.get("content") shouldBe null
        }
    }

    should("handle transparent route selectors in fullPath") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = openApiSpec()
            }
            routing {
                route("/items/{id}") {
                    createChild(object : RouteSelector() {
                        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                            RouteSelectorEvaluation.Transparent
                        override fun toString(): String = ""
                    }).apply {
                        typedGet<StreamPayload, Ok<Map<String, String>>> {
                            Ok(mapOf("id" to payload.id.value))
                        }
                    }
                }
            }
            val specJson = Json.decodeFromString<JsonObject>(client.get("/openapi.json").bodyAsText())
            val paths = specJson["paths"]?.jsonObject?.keys
            paths.shouldNotBeNull()
            paths shouldContain "/items/{id}"
        }
    }
})
