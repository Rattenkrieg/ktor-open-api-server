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
)
