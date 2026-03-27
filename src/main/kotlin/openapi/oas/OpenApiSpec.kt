// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.oas

import kotlinx.serialization.Serializable

@Serializable
data class OpenApiSpec(
    val openapi: String = "3.1.0",
    val info: Info,
    val servers: MutableList<Server> = mutableListOf(),
    val paths: MutableMap<String, Path> = mutableMapOf(),
    val components: Components = Components(),
)

@Serializable
data class Info(
    val title: String,
    var version: String,
    var summary: String? = null,
    var description: String? = null,
    var termsOfService: String? = null,
)

@Serializable
data class Server(
    val url: String,
    val description: String? = null,
)

@Serializable
data class Components(
    val schemas: MutableMap<String, openapi.schema.JsonSchema> = mutableMapOf(),
    val securitySchemes: MutableMap<String, SecurityScheme> = mutableMapOf(),
)

@Serializable
data class SecurityScheme(
    val type: String,
    val scheme: String? = null,
    val bearerFormat: String? = null,
    val name: String? = null,
    val `in`: String? = null,
) {
    companion object {
        fun bearer(bearerFormat: String? = "JWT") = SecurityScheme(
            type = "http",
            scheme = "bearer",
            bearerFormat = bearerFormat,
        )
        fun basic() = SecurityScheme(type = "http", scheme = "basic")
        fun apiKey(name: String, location: String = "header") = SecurityScheme(
            type = "apiKey",
            name = name,
            `in` = location,
        )
    }
}
