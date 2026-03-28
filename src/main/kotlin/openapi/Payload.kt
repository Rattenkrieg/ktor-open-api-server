package openapi

import io.ktor.http.*
import io.ktor.http.content.*
import java.io.OutputStream
import java.io.Writer

interface RequestPayload

sealed interface RequestPayloadItem<out T> {
    val value: T
}

class Body<T : Any>(private val receiver: suspend () -> T) {
    private var cached: T? = null
    suspend fun value(): T = cached ?: receiver().also { cached = it }
}
data class PathParam(override val value: String) : RequestPayloadItem<String>
data class QueryParam(override val value: String) : RequestPayloadItem<String>
data class QueryParamList(override val value: List<String>) : RequestPayloadItem<List<String>>

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Name(val value: String)
data class HeaderParam(override val value: String) : RequestPayloadItem<String>
data class Principal<T : Any>(override val value: T) : RequestPayloadItem<T>
data class AcceptHeader(override val value: List<HeaderValue>) : RequestPayloadItem<List<HeaderValue>>
data class QueryParams(override val value: Map<String, List<String>>) : RequestPayloadItem<Map<String, List<String>>>
data class MultipartBody(override val value: MultiPartData) : RequestPayloadItem<MultiPartData>
data class RequestOrigin(override val value: RequestConnectionPoint) : RequestPayloadItem<RequestConnectionPoint>
data class CookieParam(override val value: String) : RequestPayloadItem<String>

interface ResponsePayload {
    val statusCode: HttpStatusCode get() = HttpStatusCode.OK
}

sealed class StreamResponsePayload(
    override val statusCode: HttpStatusCode,
) : ResponsePayload

open class ByteStreamResponse(
    val contentType: ContentType,
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    val fileName: String? = null,
    val writer: suspend OutputStream.() -> Unit,
) : StreamResponsePayload(statusCode)

open class TextStreamResponse(
    val contentType: ContentType,
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    val fileName: String? = null,
    val writer: suspend Writer.() -> Unit,
) : StreamResponsePayload(statusCode)

open class RedirectResponse(
    val url: String,
    permanent: Boolean,
) : StreamResponsePayload(if (permanent) HttpStatusCode.MovedPermanently else HttpStatusCode.Found)

abstract class OkResponsePayload : ResponsePayload
abstract class AcceptedResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.Accepted }
abstract class CreatedResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.Created }
abstract class NoContentResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.NoContent }
abstract class BadRequestResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.BadRequest }
abstract class NotFoundResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.NotFound }
abstract class InternalServerErrorResponsePayload : ResponsePayload { override val statusCode = HttpStatusCode.InternalServerError }

sealed interface ResponsePayloadItem<out T> {
    val value: T
}

@kotlinx.serialization.Serializable
data class ResponseBody<T : @kotlinx.serialization.Serializable Any>(override val value: T) : ResponsePayloadItem<T>
data class ResponseHeader(override val value: String) : ResponsePayloadItem<String>
data class ResponseCookie(
    override val value: String,
    val path: String? = null,
    val maxAge: Int? = null,
    val httpOnly: Boolean = true,
    val secure: Boolean = true,
    val encoding: CookieEncoding = CookieEncoding.RAW,
    val extensions: Map<String, String?> = mapOf(),
) : ResponsePayloadItem<String>

data class Ok<T : Any>(val body: ResponseBody<T>) : OkResponsePayload() {
    constructor(value: T) : this(ResponseBody(value))
}

data class Created<T : Any>(val body: ResponseBody<T>) : CreatedResponsePayload() {
    constructor(value: T) : this(ResponseBody(value))
}

object NoContent : NoContentResponsePayload()
