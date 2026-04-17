package openapi

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.typeOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class TypedContext<P : RequestPayload>(
    val payload: P,
    private val _call: RoutingCall
) {
    @RawCallAccess
    val call: RoutingCall get() = _call
}

// --- Route DSL ---

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.get(
    path: String, noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { get<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.get(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Get, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.post(
    path: String, noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { post<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.post(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Post, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.put(
    path: String, noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { put<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.put(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Put, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.delete(
    path: String, noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { delete<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.delete(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Delete, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.patch(
    path: String, noinline handler: suspend TypedContext<P>.() -> R
): Route = route(path) { patch<P, R>(handler) }

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.patch(
    noinline handler: suspend TypedContext<P>.() -> R
): Route = registerTypedRoute<P, R>(HttpMethod.Patch, handler)

inline fun <reified P : RequestPayload, reified R : ResponsePayload> Route.registerTypedRoute(
    method: HttpMethod,
    noinline handler: suspend TypedContext<P>.() -> Any,
): Route {
    val requestType = typeOf<P>()
    val responseType = typeOf<R>()
    val json = application.attributes.getOrNull(OpenApiJsonKey) ?: Json.Default
    registerRouteSpec(this, method, requestType, responseType)
    val requestPlan = RequestPlan.build(requestType)
    val responsePlan = ResponsePlan.build(responseType, json)
    return method(method) {
        handle { handleTypedRoute(requestPlan, responsePlan, handler) }
    }
}

// --- Request plan (precomputed at route setup) ---

sealed interface ParamExtractor {
    suspend fun extract(call: RoutingCall): Any?
}

private class BodyExtractor(val typeInfo: TypeInfo, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall): Any? {
        return if (nullable) {
            runCatching { call.receive<Any>(typeInfo) }.getOrNull()?.let { Body { it } }
        } else {
            val result = call.receive<Any>(typeInfo)
            Body { result }
        }
    }
}

private class PathParamExtractor(val name: String) : ParamExtractor {
    override suspend fun extract(call: RoutingCall) =
        PathParam(call.pathParameters[name] ?: error("Missing path parameter: $name"))
}

private class QueryParamExtractor(val httpName: String, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall) =
        if (nullable) call.queryParameters[httpName]?.let { QueryParam(it) }
        else QueryParam(call.queryParameters[httpName] ?: error("Missing query parameter: $httpName"))
}

private class QueryParamListExtractor(val httpName: String, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall): Any? {
        val values = call.queryParameters.getAll(httpName)
        return if (nullable) values?.let { QueryParamList(it) }
        else QueryParamList(values ?: error("Missing query parameter: $httpName"))
    }
}

private class HeaderParamExtractor(val httpName: String, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall) =
        if (nullable) call.request.headers[httpName]?.let { HeaderParam(it) }
        else HeaderParam(call.request.headers[httpName] ?: error("Missing header: $httpName"))
}

private class CookieParamExtractor(val httpName: String, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall) =
        if (nullable) call.request.cookies[httpName]?.let { CookieParam(it) }
        else CookieParam(call.request.cookies[httpName] ?: error("Missing cookie: $httpName"))
}

private class PrincipalExtractor(val principalClass: KClass<*>, val nullable: Boolean) : ParamExtractor {
    override suspend fun extract(call: RoutingCall): Any? {
        val principal = call.authentication.principal(null, principalClass)
        return if (nullable) principal?.let { Principal(it) }
        else Principal(principal ?: error("No authenticated principal of type ${principalClass.simpleName}"))
    }
}

private object AcceptHeaderExtractor : ParamExtractor {
    override suspend fun extract(call: RoutingCall) = AcceptHeader(call.request.acceptItems())
}

private object QueryParamsExtractor : ParamExtractor {
    override suspend fun extract(call: RoutingCall) =
        QueryParams(call.queryParameters.entries().associate { it.key to it.value })
}

private object MultipartBodyExtractor : ParamExtractor {
    override suspend fun extract(call: RoutingCall) = MultipartBody(call.receiveMultipart())
}

private object RequestOriginExtractor : ParamExtractor {
    override suspend fun extract(call: RoutingCall) = RequestOrigin(call.request.origin)
}

class RequestPlan private constructor(
    private val objectInstance: Any?,
    private val constructor: KFunction<Any>?,
    private val extractors: List<Pair<KParameter, ParamExtractor>>,
) {
    suspend fun extract(call: RoutingCall): Any {
        if (objectInstance != null) return objectInstance
        val args = mutableMapOf<KParameter, Any?>()
        for ((param, extractor) in extractors) {
            val value = extractor.extract(call)
            if (value != null || !param.isOptional) {
                args[param] = value
            }
        }
        return constructor!!.callBy(args)
    }

    companion object {
        fun build(requestType: KType): RequestPlan {
            val kClass = requestType.classifier as KClass<*>
            kClass.objectInstance?.let {
                return RequestPlan(it, null, listOf())
            }
            @Suppress("UNCHECKED_CAST")
            val constructor = kClass.primaryConstructor as? KFunction<Any>
                ?: error("Payload ${kClass.simpleName} must have a primary constructor")
            val extractors = constructor.parameters.map { param ->
                val paramType = param.type
                val classifier = paramType.classifier as KClass<*>
                val paramName = param.name ?: error("Payload parameter must have a name")
                val nullable = paramType.isMarkedNullable
                val extractor: ParamExtractor = when {
                    classifier.isSubclassOf(Body::class) -> {
                        val bodyType = paramType.arguments[0].type!!
                        val bodyClass = bodyType.classifier as KClass<*>
                        BodyExtractor(TypeInfo(bodyClass, bodyType), nullable)
                    }
                    classifier == PathParam::class -> PathParamExtractor(paramName)
                    classifier == QueryParam::class -> {
                        val httpName = param.findAnnotation<Name>()?.value ?: paramName
                        QueryParamExtractor(httpName, nullable)
                    }
                    classifier == QueryParamList::class -> {
                        val httpName = param.findAnnotation<Name>()?.value ?: paramName
                        QueryParamListExtractor(httpName, nullable)
                    }
                    classifier == HeaderParam::class -> {
                        val httpName = param.findAnnotation<Name>()?.value ?: paramName
                        HeaderParamExtractor(httpName, nullable)
                    }
                    classifier == CookieParam::class -> {
                        val httpName = param.findAnnotation<Name>()?.value ?: paramName
                        CookieParamExtractor(httpName, nullable)
                    }
                    classifier == AcceptHeader::class -> AcceptHeaderExtractor
                    classifier == QueryParams::class -> QueryParamsExtractor
                    classifier == MultipartBody::class -> MultipartBodyExtractor
                    classifier == RequestOrigin::class -> RequestOriginExtractor
                    classifier.isSubclassOf(Principal::class) -> {
                        val principalClass = paramType.arguments[0].type!!.classifier as KClass<*>
                        PrincipalExtractor(principalClass, nullable)
                    }
                    else -> error(
                        "Payload property '$paramName' must be a RequestPayloadItem type " +
                            "(Body, PathParam, QueryParam, QueryParamList, HeaderParam, CookieParam, " +
                            "Principal, AcceptHeader, MultipartBody, RequestOrigin), " +
                            "got: ${classifier.simpleName}"
                    )
                }
                param to extractor
            }
            val bodyCount = extractors.count { it.second is BodyExtractor }
            check(bodyCount <= 1) {
                "Payload ${kClass.simpleName} has $bodyCount Body fields, at most 1 is allowed"
            }
            val principalCount = extractors.count { it.second is PrincipalExtractor }
            check(principalCount <= 1) {
                "Payload ${kClass.simpleName} has $principalCount Principal fields, at most 1 is allowed"
            }
            val pathParamNames = extractors
                .filter { it.second is PathParamExtractor }
                .map { it.first.name }
            val duplicatePathParams = pathParamNames.groupBy { it }.filter { it.value.size > 1 }.keys
            check(duplicatePathParams.isEmpty()) {
                "Payload ${kClass.simpleName} has duplicate PathParam names: $duplicatePathParams"
            }
            return RequestPlan(null, constructor, extractors)
        }
    }
}

// --- Response plan (precomputed at route setup) ---

sealed interface ResponsePlan {
    object Stream : ResponsePlan
    object StatusOnly : ResponsePlan

    data class Sealed(
        val variantPlans: Map<KClass<*>, ResponsePlan>,
    ) : ResponsePlan

    data class WithBody(
        val json: Json,
        val bodySerializer: KSerializer<Any>?,
        val bodyProperty: KProperty1<Any, *>?,
        val headerProperties: List<Pair<String, KProperty1<Any, *>>>,
        val cookieProperties: List<Pair<String, KProperty1<Any, *>>> = listOf(),
    ) : ResponsePlan

    data class DirectPayload(
        val json: Json,
        val serializer: KSerializer<Any>,
    ) : ResponsePlan

    companion object {
        fun build(responseType: KType, json: Json): ResponsePlan {
            val responseClass = responseType.classifier as KClass<*>
            return buildForClassOrType(responseClass, responseType, json, depth = 0)
        }

        fun buildForClass(kClass: KClass<*>, json: Json): ResponsePlan {
            return buildForClassOrType(kClass, null, json, depth = 0)
        }

        private fun buildForClassOrType(
            kClass: KClass<*>,
            responseType: KType?,
            json: Json,
            depth: Int,
        ): ResponsePlan {
            check(depth < 4) { "Sealed response nesting too deep for ${kClass.simpleName}" }
            if (kClass.isSubclassOf(StreamResponsePayload::class)) return Stream
            if (kClass.isSealed) {
                val variantPlans = kClass.sealedSubclasses.associateWith { subclass ->
                    buildForClassOrType(subclass, null, json, depth + 1)
                }
                return Sealed(variantPlans)
            }
            return buildFromConstructor(kClass, responseType, json)
        }

        @Suppress("UNCHECKED_CAST")
        private fun buildFromConstructor(
            kClass: KClass<*>,
            responseType: KType?,
            json: Json,
        ): ResponsePlan {
            val constructor = kClass.primaryConstructor
                ?: return StatusOnly
            val properties = kClass.memberProperties.associateBy { it.name }
            var hasResponseBody = false
            var hasDataProperties = false
            var bodyKType: KType? = null
            var bodyProperty: KProperty1<Any, *>? = null
            val headerProperties = mutableListOf<Pair<String, KProperty1<Any, *>>>()
            val cookieProperties = mutableListOf<Pair<String, KProperty1<Any, *>>>()
            for (param in constructor.parameters) {
                val classifier = param.type.classifier as? KClass<*> ?: continue
                val paramName = param.name ?: continue
                when {
                    classifier.isSubclassOf(ResponseBody::class) -> {
                        hasResponseBody = true
                        bodyProperty = properties[paramName] as? KProperty1<Any, *>
                        bodyKType = if (responseType != null) {
                            resolveBodyKType(param.type, responseType)
                        } else {
                            val rawType = param.type.arguments.firstOrNull()?.type
                            rawType?.takeIf { it.classifier is KClass<*> }
                        }
                    }
                    classifier == ResponseHeader::class -> {
                        (properties[paramName] as? KProperty1<Any, *>)?.let {
                            headerProperties.add(paramName to it)
                        }
                    }
                    classifier == ResponseCookie::class -> {
                        (properties[paramName] as? KProperty1<Any, *>)?.let {
                            val cookieName = param.findAnnotation<Name>()?.value ?: paramName
                            cookieProperties.add(cookieName to it)
                        }
                    }
                    else -> hasDataProperties = true
                }
            }
            if (hasResponseBody) {
                val bodySerializer = bodyKType?.let {
                    json.serializersModule.serializer(it) as KSerializer<Any>
                }
                return WithBody(json, bodySerializer, bodyProperty, headerProperties, cookieProperties)
            }
            if (hasDataProperties) {
                val type = responseType ?: kClass.createType()
                val serializer = json.serializersModule.serializer(type) as KSerializer<Any>
                return DirectPayload(json, serializer)
            }
            return StatusOnly
        }

        private fun resolveBodyKType(responseBodyParamType: KType, responseType: KType): KType? {
            val rawBodyType = responseBodyParamType.arguments.firstOrNull()?.type ?: return null
            if (rawBodyType.classifier is KClass<*>) return rawBodyType
            val responseClass = responseType.classifier as? KClass<*> ?: return null
            val typeParams = responseClass.typeParameters
            val typeArgs = responseType.arguments
            val constructor = responseClass.primaryConstructor ?: return null
            for (param in constructor.parameters) {
                val classifier = param.type.classifier as? KClass<*> ?: continue
                if (classifier.isSubclassOf(ResponseBody::class)) {
                    val bodyTypeArg = param.type.arguments.firstOrNull()?.type ?: return null
                    val paramIndex = typeParams.indexOfFirst { it == bodyTypeArg.classifier }
                    if (paramIndex >= 0) return typeArgs.getOrNull(paramIndex)?.type
                    return bodyTypeArg
                }
            }
            return null
        }
    }
}

// --- Per-request handling ---

suspend fun <P : RequestPayload> RoutingContext.handleTypedRoute(
    requestPlan: RequestPlan,
    responsePlan: ResponsePlan,
    handler: suspend TypedContext<P>.() -> Any,
) {
    @Suppress("UNCHECKED_CAST")
    val payload = requestPlan.extract(call) as P
    val ctx = TypedContext(payload, call)
    val response = ctx.handler() as ResponsePayload
    sendResponse(call, response, responsePlan)
}

suspend fun sendResponsePayload(
    call: ApplicationCall,
    response: ResponsePayload,
) {
    val json = call.application.attributes.getOrNull(OpenApiJsonKey) ?: Json.Default
    val plan = ResponsePlan.buildForClass(response::class, json)
    sendResponse(call, response, plan)
}

suspend fun sendResponse(
    call: ApplicationCall,
    response: ResponsePayload,
    plan: ResponsePlan,
) {
    when (response) {
        is ByteStreamResponse -> {
            sendContentDisposition(call, response.fileName)
            call.respondOutputStream(response.contentType, response.statusCode) { response.writer(this) }
            return
        }
        is TextStreamResponse -> {
            sendContentDisposition(call, response.fileName)
            call.respondTextWriter(response.contentType, response.statusCode) { response.writer(this) }
            return
        }
        is RedirectResponse -> {
            call.respondRedirect(response.url, response.statusCode == HttpStatusCode.MovedPermanently)
            return
        }
    }
    when (plan) {
        is ResponsePlan.Stream -> call.respond(response.statusCode)
        is ResponsePlan.StatusOnly -> call.respond(response.statusCode)
        is ResponsePlan.Sealed -> {
            val variantPlan = plan.variantPlans[response::class]
                ?: ResponsePlan.buildForClass(response::class,
                    (plan.variantPlans.values.firstOrNull() as? ResponsePlan.WithBody)?.json ?: Json.Default)
            sendResponse(call, response, variantPlan)
        }
        is ResponsePlan.WithBody -> sendBodyResponse(call, response, plan)
        is ResponsePlan.DirectPayload -> {
            call.response.status(response.statusCode)
            call.respondText(plan.json.encodeToString(plan.serializer, response), ContentType.Application.Json)
        }
    }
}

private suspend fun sendBodyResponse(
    call: ApplicationCall,
    response: ResponsePayload,
    plan: ResponsePlan.WithBody,
) {
    for ((name, prop) in plan.headerProperties) {
        (prop.get(response) as? ResponseHeader)?.let {
            call.response.headers.append(name, it.value)
        }
    }
    for ((name, prop) in plan.cookieProperties) {
        (prop.get(response) as? ResponseCookie)?.let { cookie ->
            call.response.cookies.append(
                name = name,
                value = cookie.value,
                encoding = cookie.encoding,
                path = cookie.path,
                maxAge = cookie.maxAge?.toLong(),
                expires = cookie.expires,
                httpOnly = cookie.httpOnly,
                secure = cookie.secure,
                extensions = cookie.extensions,
            )
        }
    }
    val bodyWrapper = plan.bodyProperty?.get(response) as? ResponseBody<*>
    val body = bodyWrapper?.value
    when {
        body != null && body != Unit -> {
            var serializer = plan.bodySerializer
            if (serializer == null) {
                val runtimePlan = ResponsePlan.buildForClass(response::class, plan.json)
                serializer = (runtimePlan as? ResponsePlan.WithBody)?.bodySerializer
            }
            if (serializer != null) {
                call.response.status(response.statusCode)
                call.respondText(plan.json.encodeToString(serializer, body), ContentType.Application.Json)
            } else {
                error(
                    "No serializer resolved for response body of type ${body::class.simpleName} " +
                        "in ${response::class.simpleName}. Ensure ResponseBody<T> uses a @Serializable type."
                )
            }
        }
        else -> call.respond(response.statusCode)
    }
}

private fun sendContentDisposition(call: ApplicationCall, fileName: String?) {
    fileName?.let {
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, it
            ).toString()
        )
    }
}

// --- OpenAPI tags ---

val OpenApiTagsKey = AttributeKey<List<String>>("OpenApiTags")

fun Route.tagged(vararg tags: String, build: Route.() -> Unit): Route {
    attributes.put(OpenApiTagsKey, tags.toList())
    build()
    return this
}

private fun Route.collectTags(): List<String>? {
    val tags = mutableListOf<String>()
    application.attributes.getOrNull(OpenApiGlobalTagsKey)?.let { tags.addAll(it) }
    var current: Route? = this
    while (current != null) {
        current.attributes.getOrNull(OpenApiTagsKey)?.let { tags.addAll(it) }
        current = current.parent
    }
    return tags.ifEmpty { null }
}

private fun Route.collectSecurity(): List<Map<String, List<String>>>? {
    val schemes = mutableListOf<String>()
    var current: Route? = this
    while (current != null) {
        if (current is RoutingNode && current.selector is AuthenticationRouteSelector) {
            (current.selector as AuthenticationRouteSelector).names
                .forEach { schemes.add(it ?: "default") }
        }
        current = current.parent
    }
    return if (schemes.isEmpty()) null
    else schemes.map { mapOf(it to listOf()) }
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
    val tags = route.collectTags()
    val security = route.collectSecurity()
    try {
        val json = route.application.attributes.getOrNull(OpenApiJsonKey) ?: Json.Default
        addRouteToSpec(spec, path, method, requestType, responseType, tags, security, json)
    } catch (e: Exception) {
        route.application.log.warn("Failed to register OpenAPI spec for $method $path: ${e.message}")
    }
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
