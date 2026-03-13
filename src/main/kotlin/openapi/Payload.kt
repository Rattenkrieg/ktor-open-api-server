package openapi

import io.ktor.http.*
import io.ktor.http.content.*
import java.io.OutputStream
import java.io.Writer

interface RequestPayload

sealed interface RequestPayloadItem<out T> {
    val value: T
}

data class Body<T : Any>(override val value: T) : RequestPayloadItem<T>
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

class ByteStreamResponse(
    val contentType: ContentType,
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    val writer: suspend OutputStream.() -> Unit,
) : StreamResponsePayload(statusCode)

class TextStreamResponse(
    val contentType: ContentType,
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    val writer: suspend Writer.() -> Unit,
) : StreamResponsePayload(statusCode)

class RedirectResponse(
    val url: String,
    permanent: Boolean,
) : StreamResponsePayload(if (permanent) HttpStatusCode.MovedPermanently else HttpStatusCode.Found)

abstract class OkResponsePayload : ResponsePayload

abstract class CreatedResponsePayload : ResponsePayload {
    override val statusCode get() = HttpStatusCode.Created
}

abstract class NoContentResponsePayload : ResponsePayload {
    override val statusCode get() = HttpStatusCode.NoContent
}

sealed interface ResponsePayloadItem<out T> {
    val value: T
}

data class ResponseBody<T : Any>(override val value: T) : ResponsePayloadItem<T>
data class ResponseHeader(override val value: String) : ResponsePayloadItem<String>

data class Ok<T : Any>(val body: ResponseBody<T>) : OkResponsePayload() {
    constructor(value: T) : this(ResponseBody(value))
}

data class Created<T : Any>(val body: ResponseBody<T>) : CreatedResponsePayload() {
    constructor(value: T) : this(ResponseBody(value))
}

object NoContent : NoContentResponsePayload()
