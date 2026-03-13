// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.oas

import openapi.schema.JsonSchema
import kotlinx.serialization.Serializable

@Serializable
data class Parameter(
    val name: String,
    val `in`: Location,
    val schema: JsonSchema,
    val description: String? = null,
    val required: Boolean = true,
) {
    @Suppress("EnumNaming")
    @Serializable
    enum class Location {
        query,
        header,
        path,
    }
}

@Serializable
data class Request(
    val description: String?,
    val content: Map<String, MediaType>?,
    val required: Boolean = false,
)

@Serializable
data class Response(
    val description: String,
    val headers: Map<String, Header>? = null,
    val content: Map<String, MediaType>? = null,
)

@Serializable
data class Header(
    val schema: JsonSchema,
    val description: String? = null,
    val required: Boolean = true,
)

@Serializable
data class MediaType(
    val schema: JsonSchema,
)
