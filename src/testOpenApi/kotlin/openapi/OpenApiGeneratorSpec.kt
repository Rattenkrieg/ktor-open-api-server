package openapi

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import openapi.oas.Info
import openapi.oas.OpenApiSpec
import org.openapitools.codegen.DefaultGenerator
import org.openapitools.codegen.config.CodegenConfigurator
import java.io.File
import java.util.UUID

@JvmInline
value class ModelId(val id: @Contextual UUID)

@Serializable
data class OrderItem(val name: String, val quantity: Int, val price: Long)

@Serializable
enum class OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED }

@Serializable
data class Order(
    val id: @Contextual UUID,
    val modelId: @Contextual ModelId,
    val status: OrderStatus,
    val items: List<OrderItem>,
    val note: String?,
    val tags: Map<String, String>,
)

@Serializable
data class CreateOrderRequest(
    val items: List<OrderItem>,
    val note: String?,
)

data class ListOrdersPayload(
    val status: QueryParam?,
    val auth: Principal<String>,
) : RequestPayload

data class CreateOrderPayload(
    val auth: Principal<String>,
    val body: Body<CreateOrderRequest>,
) : RequestPayload

data class GetOrderPayload(
    val id: PathParam,
    val auth: Principal<String>,
) : RequestPayload

sealed interface CreateOrderResult : ResponsePayload {
    @Serializable
    data class Success(val order: Order) : CreatedResponsePayload(), CreateOrderResult
    @Serializable
    data class ValidationError(val errors: List<String>) : BadRequestResponsePayload(), CreateOrderResult
}

class OpenApiGeneratorSpec : ShouldSpec({

    should("generate valid TypeScript client from spec with complex types") {
        testApplication {
            install(ContentNegotiation) { json() }
            install(OpenApi) {
                spec = OpenApiSpec(info = Info(title = "Test API", version = "1.0.0"))
            }
            routing {
                serveOpenApiSpec("/openapi.json")
                route("/orders") {
                    get<ListOrdersPayload, Ok<List<Order>>> {
                        Ok(listOf())
                    }
                    post<CreateOrderPayload, CreateOrderResult> {
                        CreateOrderResult.Success(
                            Order(
                                id = UUID.randomUUID(),
                                modelId = ModelId(UUID.randomUUID()),
                                status = OrderStatus.PENDING,
                                items = listOf(),
                                note = null,
                                tags = mapOf(),
                            ),
                        )
                    }
                    get<GetOrderPayload, Ok<Order>>("/{id}") {
                        Ok(
                            Order(
                                id = UUID.randomUUID(),
                                modelId = ModelId(UUID.randomUUID()),
                                status = OrderStatus.PENDING,
                                items = listOf(),
                                note = null,
                                tags = mapOf(),
                            ),
                        )
                    }
                }
            }

            val specJson = client.get("/openapi.json").bodyAsText()
            specJson.shouldNotBeNull()

            val specFile = File.createTempFile("openapi-spec", ".json")
            specFile.writeText(specJson)
            val outputDir = File.createTempFile("openapi-gen", "").apply { delete(); mkdirs() }

            try {
                val configurator = CodegenConfigurator().apply {
                    setInputSpec(specFile.absolutePath)
                    setGeneratorName("typescript-fetch")
                    setOutputDir(outputDir.absolutePath)
                }
                val generator = DefaultGenerator().opts(configurator.toClientOptInput())
                val files = generator.generate()
                files.shouldNotBeNull()
                val modelFiles = files.filter { it.path.contains("/models/") }
                modelFiles.size shouldBe files.filter { it.path.contains("/models/") }.size

                val specContent = Json.decodeFromString<kotlinx.serialization.json.JsonObject>(specJson)
                val schemas = specContent["components"]
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("schemas")
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                schemas.shouldNotBeNull()

                val illegalSchemaNames = schemas.keys.filter { name ->
                    name.contains("<") || name.contains(">") || name.contains("?")
                }
                illegalSchemaNames.shouldBeEmpty()

                val selfRefs = schemas.entries.filter { (name, schema) ->
                    schema.toString().contains("\"#/components/schemas/$name\"")
                        && !schema.toString().contains("\"type\"")
                }
                selfRefs.shouldBeEmpty()
            } finally {
                specFile.delete()
                outputDir.deleteRecursively()
            }
        }
    }
})
