package openapi

import openapi.oas.OpenApiSpec
import openapi.oas.Info
import openapi.oas.Path
import openapi.oas.PathOperation
import openapi.oas.MediaType
import openapi.oas.Parameter
import openapi.oas.Request
import openapi.oas.Response
import openapi.oas.Header
import openapi.schema.JsonSchema
import openapi.schema.SchemaGenerator
import openapi.schema.TypeDefinition
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import openapi.schema.slug
import openapi.schema.ArrayDefinition
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

val OpenApiSpecKey = AttributeKey<OpenApiSpec>("OpenApiSpec")

class OpenApiConfig {
    lateinit var spec: OpenApiSpec
    var specPath: String? = "/openapi.json"
    var customTypes: Map<KType, JsonSchema> = mapOf()
}

val OpenApi = createApplicationPlugin("OpenApi", ::OpenApiConfig) {
    pluginConfig.customTypes.forEach { (type, schema) ->
        pluginConfig.spec.components.schemas[type.slug()] = schema
    }
    application.attributes.put(OpenApiSpecKey, pluginConfig.spec)
    val specPath = pluginConfig.specPath ?: return@createApplicationPlugin
    val specJson = lazy {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
        json.encodeToString(OpenApiSpec.serializer(), application.attributes[OpenApiSpecKey])
    }
    onCallRespond { call ->
        if (call.request.local.uri == specPath) return@onCallRespond
    }
    application.routing {
        route(specPath) {
            method(HttpMethod.Get) {
                handle {
                    call.respondText(specJson.value, ContentType.Application.Json)
                }
            }
        }
    }
}

fun Application.openApiSpecJson(): String {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
    return json.encodeToString(OpenApiSpec.serializer(), attributes[OpenApiSpecKey])
}

fun addRouteToSpec(
    spec: OpenApiSpec,
    path: String,
    method: HttpMethod,
    payloadType: KType,
    responseType: KType
) {
    val cache = spec.components.schemas
    val payloadClass = payloadType.classifier as KClass<*>
    val constructor = payloadClass.primaryConstructor ?: return
    val parameters = mutableListOf<Parameter>()
    var requestBody: Request? = null
    for (param in constructor.parameters) {
        val paramType = param.type
        val classifier = paramType.classifier as KClass<*>
        val paramName = param.name ?: continue
        when {
            classifier.isSubclassOf(Body::class) -> {
                val bodyType = paramType.arguments[0].type!!
                val schema = SchemaGenerator.fromTypeToSchema(bodyType, cache)
                requestBody = Request(
                    description = null,
                    content = mapOf("application/json" to MediaType(schema = schema)),
                    required = !paramType.isMarkedNullable
                )
            }
            classifier == PathParam::class -> {
                parameters.add(
                    Parameter(
                        name = paramName,
                        `in` = Parameter.Location.path,
                        schema = TypeDefinition.STRING,
                        required = true
                    )
                )
            }
            classifier == QueryParam::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                parameters.add(
                    Parameter(
                        name = httpName,
                        `in` = Parameter.Location.query,
                        schema = TypeDefinition.STRING,
                        required = !paramType.isMarkedNullable
                    )
                )
            }
            classifier == QueryParamList::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                parameters.add(
                    Parameter(
                        name = httpName,
                        `in` = Parameter.Location.query,
                        schema = ArrayDefinition(items = TypeDefinition.STRING),
                        required = !paramType.isMarkedNullable
                    )
                )
            }
            classifier == HeaderParam::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                parameters.add(
                    Parameter(
                        name = httpName,
                        `in` = Parameter.Location.header,
                        schema = TypeDefinition.STRING,
                        required = !paramType.isMarkedNullable
                    )
                )
            }
            classifier == MultipartBody::class -> {
                requestBody = Request(
                    description = null,
                    content = mapOf("multipart/form-data" to MediaType(schema = TypeDefinition(type = "object"))),
                    required = true
                )
            }
            // Not part of the OpenAPI spec — internal concerns
            classifier.isSubclassOf(Principal::class) -> {}
            classifier == AcceptHeader::class -> {}
            classifier == RequestOrigin::class -> {}
            classifier == QueryParams::class -> {}
            classifier == CookieParam::class -> {}
        }
    }
    val responses = buildResponsePayloadSpec(responseType, cache)
    val operation = PathOperation(
        parameters = parameters.ifEmpty { null },
        requestBody = requestBody,
        responses = responses
    )
    val pathItem = spec.paths.getOrPut(path) { Path() }
    when (method) {
        HttpMethod.Get -> pathItem.get = operation
        HttpMethod.Post -> pathItem.post = operation
        HttpMethod.Put -> pathItem.put = operation
        HttpMethod.Delete -> pathItem.delete = operation
        HttpMethod.Patch -> pathItem.patch = operation
    }
}

fun buildResponsePayloadSpec(
    responseType: KType,
    cache: MutableMap<String, JsonSchema>
): MutableMap<Int, Response> {
    val responseClass = responseType.classifier as KClass<*>
    if (responseClass.isSubclassOf(StreamResponsePayload::class)) {
        val resolvedStatusCode = resolveResponsePayloadStatusCode(responseClass)
        return mutableMapOf(resolvedStatusCode.value to Response(description = resolvedStatusCode.description))
    }
    val resolvedStatusCode = resolveResponsePayloadStatusCode(responseClass)
    val constructor = responseClass.primaryConstructor
        ?: return mutableMapOf(resolvedStatusCode.value to Response(description = resolvedStatusCode.description))
    val typeParameters = responseClass.typeParameters
    val typeArguments = responseType.arguments
    var bodySchema: JsonSchema? = null
    val responseHeaders = mutableMapOf<String, Header>()
    var hasResponseBody = false
    var hasDataProperties = false
    for (param in constructor.parameters) {
        val paramType = param.type
        val classifier = paramType.classifier as KClass<*>
        val paramName = param.name ?: continue
        when {
            classifier.isSubclassOf(ResponseBody::class) -> {
                hasResponseBody = true
                val bodyTypeArg = paramType.arguments[0].type!!
                val resolvedBodyType = if (bodyTypeArg.classifier is KTypeParameter) {
                    val index = typeParameters.indexOf(bodyTypeArg.classifier)
                    if (index >= 0) typeArguments[index].type!! else bodyTypeArg
                } else {
                    bodyTypeArg
                }
                bodySchema = SchemaGenerator.fromTypeToSchema(resolvedBodyType, cache)
            }
            classifier == ResponseHeader::class -> {
                responseHeaders[paramName] = Header(
                    schema = TypeDefinition.STRING,
                    required = !paramType.isMarkedNullable
                )
            }
            else -> hasDataProperties = true
        }
    }
    if (!hasResponseBody && hasDataProperties) {
        bodySchema = SchemaGenerator.fromTypeToSchema(responseType, cache)
    }
    return mutableMapOf(
        resolvedStatusCode.value to Response(
            description = resolvedStatusCode.description,
            headers = responseHeaders.ifEmpty { null },
            content = bodySchema?.let { mapOf("application/json" to MediaType(schema = it)) }
        )
    )
}

fun resolveResponsePayloadStatusCode(responseClass: KClass<*>): HttpStatusCode {
    val objectInstance = responseClass.objectInstance
    if (objectInstance is ResponsePayload) return objectInstance.statusCode
    if (responseClass.isSubclassOf(NoContentResponsePayload::class)) return HttpStatusCode.NoContent
    if (responseClass.isSubclassOf(CreatedResponsePayload::class)) return HttpStatusCode.Created
    if (responseClass.isSubclassOf(OkResponsePayload::class)) return HttpStatusCode.OK
    val constructor = responseClass.primaryConstructor ?: return HttpStatusCode.OK
    if (constructor.parameters.all { it.isOptional }) {
        return (constructor.callBy(mapOf()) as ResponsePayload).statusCode
    }
    return HttpStatusCode.OK
}
