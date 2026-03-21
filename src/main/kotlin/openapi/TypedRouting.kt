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
import kotlin.reflect.full.createType
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

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedGet(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedGet<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedGet(
    noinline handler: suspend TypedContext<P>.() -> R
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    registerRouteSpec(this, HttpMethod.Get, requestType, responseType)
    return httpMethod(HttpMethod.Get, requestType, responseType, handler)
}

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPost(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPost<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPost(
    noinline handler: suspend TypedContext<P>.() -> R
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    registerRouteSpec(this, HttpMethod.Post, requestType, responseType)
    return httpMethod(HttpMethod.Post, requestType, responseType, handler)
}

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPut(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPut<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPut(
    noinline handler: suspend TypedContext<P>.() -> R
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    registerRouteSpec(this, HttpMethod.Put, requestType, responseType)
    return httpMethod(HttpMethod.Put, requestType, responseType, handler)
}

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedDelete(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedDelete<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedDelete(
    noinline handler: suspend TypedContext<P>.() -> R
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    registerRouteSpec(this, HttpMethod.Delete, requestType, responseType)
    return httpMethod(HttpMethod.Delete, requestType, responseType, handler)
}

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPatch(
    path: String,
    noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { typedPatch<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.typedPatch(
    noinline handler: suspend TypedContext<P>.() -> R
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    registerRouteSpec(this, HttpMethod.Patch, requestType, responseType)
    return httpMethod(HttpMethod.Patch, requestType, responseType, handler)
}

fun <P : RequestPayload> Route.httpMethod(
    method: HttpMethod,
    requestType: KType,
    responseType: KType,
    handler: suspend TypedContext<P>.() -> Any
): Route = method(method) { handle { handleTypedRoute(requestType, responseType, handler) } }

suspend fun <P : RequestPayload> RoutingContext.handleTypedRoute(
    requestType: KType,
    responseType: KType,
    handler: suspend TypedContext<P>.() -> Any
) {
    @Suppress("UNCHECKED_CAST")
    val payload = extractPayload(call, requestType) as P
    val ctx = TypedContext(payload, call)
    val response = try {
        ctx.handler()
    } catch (e: AlternateResponseException) {
        e.response
    }
    sendResponsePayload(call, response as ResponsePayload, responseType)
}

suspend fun sendResponsePayload(
    call: ApplicationCall,
    response: ResponsePayload,
) {
    sendResponsePayload(call, response, response::class.createType())
}

suspend fun sendResponsePayload(
    call: ApplicationCall,
    response: ResponsePayload,
    responseType: KType,
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
    val kClass = response::class
    val statusCode = response.statusCode
    val constructor = kClass.primaryConstructor
    var body: Any? = null
    var hasResponseBody = false
    var hasDataProperties = false
    val responseClass = responseType.classifier as? KClass<*>
    val typeParamMap = buildTypeParameterMap(responseClass, responseType)
    if (constructor != null) {
        for (param in constructor.parameters) {
            val paramType = param.type
            val classifier = paramType.classifier as KClass<*>
            val paramName = param.name ?: continue
            val member = kClass.members.first { it.name == paramName }
            val value = member.call(response) ?: continue
            when {
                classifier.isSubclassOf(ResponseBody::class) -> {
                    hasResponseBody = true
                    body = (value as ResponseBody<*>).value
                }
                classifier == ResponseHeader::class -> {
                    call.response.headers.append(paramName, (value as ResponseHeader).value)
                }
                else -> hasDataProperties = true
            }
        }
    }
    when {
        body != null && body != Unit -> {
            val bodyType = resolveBodyType(responseClass, typeParamMap)
                ?: resolveBodyType(kClass, mapOf())
            val typeInfo = bodyType?.let { TypeInfo(body!!::class, it) }
                ?: TypeInfo(body!!::class)
            call.respond(statusCode, body!!, typeInfo)
        }
        !hasResponseBody && hasDataProperties -> {
            val typeInfo = TypeInfo(kClass, responseType)
            call.respond(statusCode, response, typeInfo)
        }
        else -> call.respond(statusCode)
    }
}

private fun buildTypeParameterMap(kClass: KClass<*>?, responseType: KType): Map<KType, KType> {
    if (kClass == null) return mapOf()
    val typeParameters = kClass.typeParameters
    val typeArguments = responseType.arguments
    val map = mutableMapOf<KType, KType>()
    for (i in typeParameters.indices) {
        val resolved = typeArguments.getOrNull(i)?.type ?: continue
        map[typeParameters[i].createType()] = resolved
    }
    return map
}

private fun resolveBodyType(
    responseClass: KClass<*>?,
    typeParamMap: Map<KType, KType>,
): KType? {
    if (responseClass == null) return null
    val constructor = responseClass.primaryConstructor ?: return null
    for (param in constructor.parameters) {
        val paramType = param.type
        val classifier = paramType.classifier as? KClass<*> ?: continue
        if (classifier.isSubclassOf(ResponseBody::class)) {
            val rawType = paramType.arguments.firstOrNull()?.type ?: return null
            return typeParamMap[rawType] ?: rawType
        }
    }
    return null
}

fun registerRouteSpec(
    route: Route,
    method: HttpMethod,
    requestType: KType,
    responseType: KType
) {
    val path = route.fullPath()
    val spec = route.application.attributes.getOrNull(OpenApiSpecKey) ?: return
    try {
        addRouteToSpec(spec, path, method, requestType, responseType)
    } catch (e: Exception) {
        route.application.log.warn("Failed to register OpenAPI spec for $method $path: ${e.message}")
    }
}

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
                    @Suppress("RedundantSuspendModifier")
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
