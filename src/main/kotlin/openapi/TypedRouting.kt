package openapi

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.typeOf

class AlternateResponseException(val response: ResponsePayload) : Exception()

class TypedContext<P : RequestPayload>(
    val payload: P,
    private val _call: RoutingCall
) {
    @RawCallAccess
    val call: RoutingCall get() = _call

    fun respondWith(response: ResponsePayload): Nothing {
        throw AlternateResponseException(response)
    }
}

// --- Route DSL aliases ---

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.get(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedGet<P, R>(path, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.get(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedGet<P, R>(handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.post(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPost<P, R>(path, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.post(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPost<P, R>(handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.put(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPut<P, R>(path, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.put(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPut<P, R>(handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.delete(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedDelete<P, R>(path, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.delete(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedDelete<P, R>(handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.patch(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPatch<P, R>(path, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.patch(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = typedPatch<P, R>(handler)

// --- Typed route registration ---

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedGet(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedGet<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedGet(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Get, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPost(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPost<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPost(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Post, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPut(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPut<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPut(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Put, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedDelete(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedDelete<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedDelete(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Delete, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPatch(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPatch<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPatch(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Patch, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.registerTypedRoute(
    method: HttpMethod,
    noinline handler: suspend TypedContext<P>.() -> Any,
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    // why cant we do it here?
//    val s = json.serializersModule.serializer<R>()
    // and use resolved serializer to write response
    registerRouteSpec(this, method, requestType, responseType)
    val responseStrategy = ResponseStrategy.build(responseType)
    return method(method) {
        handle { handleTypedRoute(requestType, responseStrategy, handler) }
    }
}

// --- Response strategy (precomputed at route setup) ---

sealed interface ResponseStrategy {
    data class WithBody(
        val bodyKType: KType?,
        val headerNames: List<String>,
        val statusCode: HttpStatusCode,
    ) : ResponseStrategy

    data class DirectPayload(
        val responseType: KType,
    ) : ResponseStrategy

    data class StatusOnly(
        val statusCode: HttpStatusCode,
    ) : ResponseStrategy

    object Stream : ResponseStrategy
    object Sealed : ResponseStrategy

    companion object {
        fun buildForClass(kClass: KClass<*>): ResponseStrategy {
            if (kClass.isSubclassOf(StreamResponsePayload::class)) return Stream
            if (kClass.isSealed) return Sealed
            val constructor = kClass.primaryConstructor ?: return StatusOnly(
                resolveResponsePayloadStatusCode(kClass),
            )
            var hasResponseBody = false
            var hasDataProperties = false
            var bodyKType: KType? = null
            val headerNames = mutableListOf<String>()
            for (param in constructor.parameters) {
                val classifier = param.type.classifier as? KClass<*> ?: continue
                val paramName = param.name ?: continue
                when {
                    classifier.isSubclassOf(ResponseBody::class) -> {
                        hasResponseBody = true
                        val rawType = param.type.arguments.firstOrNull()?.type
                        if (rawType != null && rawType.classifier is KClass<*>) {
                            bodyKType = rawType
                        }
                    }
                    classifier == ResponseHeader::class -> headerNames.add(paramName)
                    else -> hasDataProperties = true
                }
            }
            if (hasResponseBody) {
                return WithBody(bodyKType, headerNames, resolveResponsePayloadStatusCode(kClass))
            }
            if (hasDataProperties) {
                return DirectPayload(kClass.supertypes.first())
            }
            return StatusOnly(resolveResponsePayloadStatusCode(kClass))
        }

        fun build(responseType: KType): ResponseStrategy {
            val responseClass = responseType.classifier as KClass<*>
            if (responseClass.isSubclassOf(StreamResponsePayload::class)) return Stream
            if (responseClass.isSealed) return Sealed
            val constructor = responseClass.primaryConstructor ?: return StatusOnly(HttpStatusCode.OK)
            var bodyKType: KType? = null
            var hasResponseBody = false
            var hasDataProperties = false
            val headerNames = mutableListOf<String>()
            for (param in constructor.parameters) {
                val classifier = param.type.classifier as? KClass<*> ?: continue
                val paramName = param.name ?: continue
                when {
                    classifier.isSubclassOf(ResponseBody::class) -> {
                        hasResponseBody = true
                        bodyKType = resolveBodyKType(param.type, responseType)
                    }
                    classifier == ResponseHeader::class -> headerNames.add(paramName)
                    else -> hasDataProperties = true
                }
            }
            if (hasResponseBody) {
                return WithBody(
                    bodyKType = bodyKType,
                    headerNames = headerNames,
                    statusCode = resolveResponsePayloadStatusCode(responseClass),
                )
            }
            if (hasDataProperties) {
                return DirectPayload(responseType)
            }
            return StatusOnly(resolveResponsePayloadStatusCode(responseClass))
        }

        private fun resolveBodyKType(responseBodyParamType: KType, responseType: KType): KType? {
            val rawBodyType = responseBodyParamType.arguments.firstOrNull()?.type ?: return null
            if (rawBodyType.classifier is KClass<*>) return rawBodyType
            return resolveBodyTypeFromResponseType(responseType)
        }

        private fun resolveBodyTypeFromResponseType(responseType: KType): KType? {
            val responseClass = responseType.classifier as? KClass<*> ?: return null
            val typeParams = responseClass.typeParameters
            val typeArgs = responseType.arguments
            val constructor = responseClass.primaryConstructor ?: return null
            for (param in constructor.parameters) {
                val classifier = param.type.classifier as? KClass<*> ?: continue
                if (classifier.isSubclassOf(ResponseBody::class)) {
                    val bodyTypeArg = param.type.arguments.firstOrNull()?.type ?: return null
                    val paramIndex = typeParams.indexOfFirst { it == bodyTypeArg.classifier }
                    if (paramIndex >= 0) {
                        return typeArgs.getOrNull(paramIndex)?.type
                    }
                    return bodyTypeArg
                }
            }
            return null
        }
    }
}

// --- Per-request handling ---

suspend fun <P : RequestPayload> RoutingContext.handleTypedRoute(
    requestType: KType,
    strategy: ResponseStrategy,
    handler: suspend TypedContext<P>.() -> Any,
) {
    @Suppress("UNCHECKED_CAST")
    val payload = extractPayload(call, requestType) as P
    val ctx = TypedContext(payload, call)
    val response = try {
        ctx.handler()
    } catch (e: AlternateResponseException) {
        e.response
    }
    sendResponse(call, response as ResponsePayload, strategy)
}

suspend fun sendResponsePayload(
    call: ApplicationCall,
    response: ResponsePayload,
) {
    val strategy = ResponseStrategy.buildForClass(response::class)
    sendResponse(call, response, strategy)
}

suspend fun sendResponse(
    call: ApplicationCall,
    response: ResponsePayload,
    strategy: ResponseStrategy,
) {
    when (response) {
        is ByteStreamResponse -> {
            response.fileName?.let {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, it
                    ).toString()
                )
            }
            call.respondOutputStream(response.contentType, response.statusCode) { response.writer(this) }
            return
        }
        is TextStreamResponse -> {
            response.fileName?.let {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, it
                    ).toString()
                )
            }
            call.respondTextWriter(response.contentType, response.statusCode) { response.writer(this) }
            return
        }
        is RedirectResponse -> {
            call.respondRedirect(response.url, response.statusCode == HttpStatusCode.MovedPermanently)
            return
        }
        else -> {}
    }
    when (strategy) {
        is ResponseStrategy.Stream -> call.respond(response.statusCode)
        is ResponseStrategy.StatusOnly -> call.respond(response.statusCode)
        is ResponseStrategy.WithBody -> sendBodyResponse(call, response, strategy)
        is ResponseStrategy.DirectPayload -> {
            call.respond(response.statusCode, response, TypeInfo(response::class, strategy.responseType))
        }
        is ResponseStrategy.Sealed -> {
            val runtimeStrategy = ResponseStrategy.buildForClass(response::class)
            sendResponse(call, response, runtimeStrategy)
        }
    }
}

private suspend fun sendBodyResponse(
    call: ApplicationCall,
    response: ResponsePayload,
    strategy: ResponseStrategy.WithBody,
) {
    val kClass = response::class
    val statusCode = response.statusCode
    val constructor = kClass.primaryConstructor ?: return call.respond(statusCode)
    var body: Any? = null
    var bodyKType: KType? = strategy.bodyKType
    for (param in constructor.parameters) {
        val paramName = param.name ?: continue
        val classifier = param.type.classifier as? KClass<*> ?: continue
        val member = kClass.members.first { it.name == paramName }
        val value = member.call(response) ?: continue
        when {
            classifier.isSubclassOf(ResponseBody::class) -> {
                body = (value as ResponseBody<*>).value
                if (bodyKType == null) {
                    val runtimeStrategy = ResponseStrategy.buildForClass(kClass)
                    bodyKType = (runtimeStrategy as? ResponseStrategy.WithBody)?.bodyKType
                }
            }
            classifier == ResponseHeader::class -> {
                call.response.headers.append(paramName, (value as ResponseHeader).value)
            }
        }
    }
    when {
        body != null && body != Unit -> {
            if (bodyKType != null) {
                call.respond(statusCode, body, TypeInfo(body::class, bodyKType))
            } else {
                call.respond(statusCode, body)
            }
        }
        else -> call.respond(statusCode)
    }
}

// --- OpenAPI spec registration ---

fun registerRouteSpec(
    route: Route,
    method: HttpMethod,
    requestType: KType,
    responseType: KType,
) {
    val path = route.fullPath()
    val spec = route.application.attributes.getOrNull(OpenApiSpecKey) ?: return
    try {
        addRouteToSpec(spec, path, method, requestType, responseType)
    } catch (e: Exception) {
        route.application.log.warn("Failed to register OpenAPI spec for $method $path: ${e.message}")
    }
}

// --- Request payload extraction ---

suspend fun extractPayload(call: RoutingCall, requestType: KType): Any {
    val kClass = requestType.classifier as KClass<*>
    val constructor = kClass.primaryConstructor
        ?: error("Payload ${kClass.simpleName} must have a primary constructor")
    val args = constructor.parameters.associateWith { param ->
        val paramType = param.type
        val classifier = paramType.classifier as KClass<*>
        val paramName = param.name ?: error("Payload parameter must have a name")
        when {
            classifier.isSubclassOf(Body::class) -> {
                val bodyType = paramType.arguments[0].type!!
                val bodyClass = bodyType.classifier as KClass<*>
                val typeInfo = TypeInfo(bodyClass, bodyType)
                if (paramType.isMarkedNullable) {
                    runCatching { call.receive<Any>(typeInfo) }.getOrNull()?.let { received -> Body { received } }
                } else {
                    val eagerResult = call.receive<Any>(typeInfo)
                    Body { eagerResult }
                }
            }
            classifier == PathParam::class -> {
                PathParam(call.pathParameters[paramName]
                    ?: error("Missing path parameter: $paramName"))
            }
            classifier == QueryParam::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                if (paramType.isMarkedNullable) {
                    call.queryParameters[httpName]?.let { QueryParam(it) }
                } else {
                    QueryParam(call.queryParameters[httpName]
                        ?: error("Missing query parameter: $httpName"))
                }
            }
            classifier == QueryParamList::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                val values = call.queryParameters.getAll(httpName)
                if (paramType.isMarkedNullable) {
                    values?.let { QueryParamList(it) }
                } else {
                    QueryParamList(values ?: error("Missing query parameter: $httpName"))
                }
            }
            classifier == HeaderParam::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                if (paramType.isMarkedNullable) {
                    call.request.headers[httpName]?.let { HeaderParam(it) }
                } else {
                    HeaderParam(call.request.headers[httpName]
                        ?: error("Missing header: $httpName"))
                }
            }
            classifier == AcceptHeader::class -> {
                AcceptHeader(call.request.acceptItems())
            }
            classifier == QueryParams::class -> {
                QueryParams(call.queryParameters.entries().associate { it.key to it.value })
            }
            classifier == MultipartBody::class -> {
                MultipartBody(call.receiveMultipart())
            }
            classifier == RequestOrigin::class -> {
                RequestOrigin(call.request.local)
            }
            classifier == CookieParam::class -> {
                val httpName = param.findAnnotation<Name>()?.value ?: paramName
                if (paramType.isMarkedNullable) {
                    call.request.cookies[httpName]?.let { CookieParam(it) }
                } else {
                    CookieParam(call.request.cookies[httpName]
                        ?: error("Missing cookie: $httpName"))
                }
            }
            classifier.isSubclassOf(Principal::class) -> {
                val principalType = paramType.arguments[0].type!!
                val principalClass = principalType.classifier as KClass<*>
                val principal = call.authentication.principal(null, principalClass)
                if (paramType.isMarkedNullable) {
                    principal?.let { Principal(it) }
                } else {
                    Principal(principal
                        ?: error("No authenticated principal of type ${principalClass.simpleName}"))
                }
            }
            else -> error("Payload property '$paramName' must be a RequestPayloadItem type (Body, PathParam, QueryParam, QueryParamList, HeaderParam, CookieParam, Principal, AcceptHeader, MultipartBody, RequestOrigin), got: ${classifier.simpleName}")
        }
    }
    return constructor.callBy(args)
}

fun Route.fullPath(): String {
    val routeStr = toString()
    return routeStr
        .replace(Regex("\\(.*?\\)\\s*"), "")
        .replace(Regex("/+"), "/")
        .trimEnd('/')
        .trim()
        .ifEmpty { "/" }
}
