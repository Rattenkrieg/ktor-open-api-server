package openapi

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import openapi.oas.Components
import openapi.oas.Info
import openapi.oas.OpenApiSpec
import openapi.schema.EnumDefinition
import openapi.schema.IntEnumDefinition
import openapi.schema.JsonSchema
import openapi.schema.ReferenceDefinition
import openapi.schema.TypeDefinition

class JsonSchemaSerializationTest : ShouldSpec({

    val json = Json { prettyPrint = false; encodeDefaults = true; explicitNulls = false }

    should("serialize EnumDefinition without type when omitted (back-compat)") {
        val schema: JsonSchema = EnumDefinition(enum = setOf("A", "B"))
        val encoded = json.encodeToString(JsonSchema.serializer(), schema)
        encoded shouldContain "\"enum\":[\"A\",\"B\"]"
        encoded shouldNotContain "\"type\""
    }

    should("serialize EnumDefinition with type when provided") {
        val schema: JsonSchema = EnumDefinition(enum = setOf("OPEN", "CLOSED"), type = "string")
        val encoded = json.encodeToString(JsonSchema.serializer(), schema)
        val parsed = json.parseToJsonElement(encoded).jsonObject
        parsed.getValue("type").jsonPrimitive.content shouldBe "string"
        parsed.getValue("enum").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("OPEN", "CLOSED")
    }

    should("serialize IntEnumDefinition with integer enum and default type/format") {
        val schema: JsonSchema = IntEnumDefinition(enum = setOf(73009, 73010, 73011))
        val encoded = json.encodeToString(JsonSchema.serializer(), schema)
        val parsed = json.parseToJsonElement(encoded).jsonObject
        parsed.getValue("type").jsonPrimitive.content shouldBe "integer"
        parsed.getValue("format").jsonPrimitive.content shouldBe "int32"
        parsed.getValue("enum").jsonArray.map { it.jsonPrimitive.int } shouldBe listOf(73009, 73010, 73011)
    }

    should("serialize IntEnumDefinition with overridden type and format") {
        val schema: JsonSchema = IntEnumDefinition(enum = setOf(1, 2), type = "integer", format = "int64")
        val encoded = json.encodeToString(JsonSchema.serializer(), schema)
        val parsed = json.parseToJsonElement(encoded).jsonObject
        parsed.getValue("format").jsonPrimitive.content shouldBe "int64"
    }

    should("round-trip IntEnumDefinition through the OpenApiSpec document") {
        val spec = OpenApiSpec(
            info = Info(title = "Test", version = "0.0.1"),
            components = Components(
                schemas = mutableMapOf(
                    "ErrorCode" to IntEnumDefinition(enum = setOf(73009, 73010)),
                    "AppErrorJson" to TypeDefinition(
                        type = "object",
                        properties = mapOf(
                            "errorCode" to ReferenceDefinition("#/components/schemas/ErrorCode"),
                        ),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(OpenApiSpec.serializer(), spec)
        val parsed = json.parseToJsonElement(encoded).jsonObject
        val schemas = parsed.getValue("components").jsonObject.getValue("schemas").jsonObject
        val errorCode = schemas.getValue("ErrorCode").jsonObject
        errorCode.getValue("type").jsonPrimitive.content shouldBe "integer"
        errorCode.getValue("format").jsonPrimitive.content shouldBe "int32"
        errorCode.getValue("enum").jsonArray.map { it.jsonPrimitive.int }.toSet() shouldBe setOf(73009, 73010)
        val appError = schemas.getValue("AppErrorJson").jsonObject
        val ref = appError.getValue("properties").jsonObject
            .getValue("errorCode").jsonObject
            .getValue("\$ref").jsonPrimitive.content
        ref shouldBe "#/components/schemas/ErrorCode"
    }

    should("distinguish EnumDefinition and IntEnumDefinition in the sealed serializer") {
        val strSchema: JsonSchema = EnumDefinition(enum = setOf("a"), type = "string")
        val intSchema: JsonSchema = IntEnumDefinition(enum = setOf(1))
        val strEncoded = json.parseToJsonElement(json.encodeToString(JsonSchema.serializer(), strSchema)).jsonObject
        val intEncoded = json.parseToJsonElement(json.encodeToString(JsonSchema.serializer(), intSchema)).jsonObject
        strEncoded.getValue("enum").jsonArray[0].jsonPrimitive.isString shouldBe true
        intEncoded.getValue("enum").jsonArray[0].jsonPrimitive.isString shouldBe false
    }
})
