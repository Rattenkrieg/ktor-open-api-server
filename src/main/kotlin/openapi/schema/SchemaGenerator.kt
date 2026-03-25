// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

object SchemaGenerator {

    fun fromTypeToSchema(
        type: KType,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema {
        val slug = type.slug()
        cache[slug]?.let { return it }
        return when (val clazz = type.classifier as KClass<*>) {
            Unit::class -> error("Unit cannot be converted to JsonSchema, use fromTypeOrUnit()")
            Int::class -> checkForNull(type, TypeDefinition.INT)
            Long::class -> checkForNull(type, TypeDefinition.LONG)
            Double::class -> checkForNull(type, TypeDefinition.DOUBLE)
            Float::class -> checkForNull(type, TypeDefinition.FLOAT)
            String::class -> checkForNull(type, TypeDefinition.STRING)
            Boolean::class -> checkForNull(type, TypeDefinition.BOOLEAN)
            UUID::class -> checkForNull(type, TypeDefinition.UUID)
            else -> complexTypeToSchema(clazz, type, cache)
        }
    }

    fun fromTypeOrUnit(
        type: KType,
        cache: MutableMap<String, JsonSchema> = mutableMapOf(),
    ): JsonSchema? = when (type.classifier as KClass<*>) {
        Unit::class -> null
        else -> fromTypeToSchema(type, cache)
    }

    private fun checkForNull(type: KType, schema: JsonSchema): JsonSchema = when (type.isMarkedNullable) {
        true -> OneOfDefinition(NullableDefinition(), schema)
        false -> schema
    }

    private fun complexTypeToSchema(
        clazz: KClass<*>,
        type: KType,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema = try {
        when {
            clazz.isSubclassOf(Enum::class) -> handleEnum(type, clazz, cache)
            clazz.isSubclassOf(Collection::class) -> handleCollection(type, cache)
            clazz.isSubclassOf(Map::class) -> handleMap(type, cache)
            clazz.isSealed -> handleSealed(type, clazz, cache)
            clazz.primaryConstructor == null -> TypeDefinition(type = "object")
            else -> handleObject(type, clazz, cache)
        }
    } catch (_: Exception) {
        TypeDefinition(type = "object")
    }

    private fun handleEnum(type: KType, clazz: KClass<*>, cache: MutableMap<String, JsonSchema>): JsonSchema {
        cache[type.slug()] = ReferenceDefinition(type.referenceSlug())
        val options = clazz.java.enumConstants.map { it.toString() }.toSet()
        return EnumDefinition(enum = options)
    }

    private fun handleCollection(type: KType, cache: MutableMap<String, JsonSchema>): JsonSchema {
        val elementType = type.arguments.firstOrNull()?.type
        if (elementType == null) {
            val definition = ArrayDefinition(TypeDefinition(type = "object"))
            return when (type.isMarkedNullable) {
                true -> OneOfDefinition(NullableDefinition(), definition)
                false -> definition
            }
        }
        val elementSchema = fromTypeToSchema(elementType, cache).let {
            if (it.isObjectOrEnum()) {
                cache[elementType.slug()] = it
                ReferenceDefinition(elementType.referenceSlug())
            } else {
                it
            }
        }
        val definition = ArrayDefinition(elementSchema)
        return when (type.isMarkedNullable) {
            true -> OneOfDefinition(NullableDefinition(), definition)
            false -> definition
        }
    }

    private fun handleMap(type: KType, cache: MutableMap<String, JsonSchema>): JsonSchema {
        val keyType = type.arguments.firstOrNull()?.type
        val keyClass = keyType?.classifier as? KClass<*>
        if (keyClass == null || type.arguments.size < 2) {
            val definition = MapDefinition(TypeDefinition(type = "object"))
            return when (type.isMarkedNullable) {
                true -> OneOfDefinition(NullableDefinition(), definition)
                false -> definition
            }
        }
        // Map keys must serialize as strings in JSON. String, Enum, value classes,
        // and any type with a string-based serializer are all valid.
        val valueType = type.arguments[1].type ?: error("Map value type argument missing")
        val valueSchema = fromTypeToSchema(valueType, cache).let {
            if (it is TypeDefinition && it.type == "object") {
                cache[valueType.slug()] = it
                ReferenceDefinition(valueType.referenceSlug())
            } else {
                it
            }
        }
        val definition = MapDefinition(valueSchema)
        return when (type.isMarkedNullable) {
            true -> OneOfDefinition(NullableDefinition(), definition)
            false -> definition
        }
    }

    private fun handleSealed(type: KType, clazz: KClass<*>, cache: MutableMap<String, JsonSchema>): JsonSchema {
        val subclasses = clazz.sealedSubclasses
            .map { it.createType(type.arguments) }
            .map { t ->
                val schema = fromTypeToSchema(t, cache)
                val enriched = addSealedDiscriminator(t, schema)
                if (enriched is TypeDefinition && enriched.type == "object") {
                    cache[t.slug()] = enriched
                    ReferenceDefinition(t.referenceSlug())
                } else {
                    enriched
                }
            }
            .toSet()
        return AnyOfDefinition(subclasses)
    }

    private fun addSealedDiscriminator(type: KType, schema: JsonSchema): JsonSchema {
        if (schema is TypeDefinition && schema.type == "object") {
            val clazz = type.classifier as KClass<*>
            val qualifier = clazz.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
                ?: clazz.qualifiedName!!
            return schema.copy(
                required = schema.required?.plus("type"),
                properties = schema.properties?.plus("type" to EnumDefinition(enum = setOf(qualifier))),
            )
        }
        return schema
    }

    private fun handleObject(
        type: KType,
        clazz: KClass<*>,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema {
        val slug = type.slug()
        val referenceSlug = type.referenceSlug()
        cache[slug] = ReferenceDefinition(referenceSlug)
        val typeMap = clazz.typeParameters.zip(type.arguments).toMap()
        val props = serializableProperties(clazz)
            .filterNot { it.javaField == null }
            .associate { prop ->
                val schema = when {
                    prop.needsGenericInjection(typeMap) -> handleNestedGenerics(typeMap, prop, cache)
                    typeMap.containsKey(prop.returnType.classifier) -> handleGenericProperty(prop, typeMap, cache)
                    else -> handleProperty(prop, cache)
                }
                val nullChecked = when (prop.returnType.isMarkedNullable && !schema.isNullable()) {
                    true -> OneOfDefinition(NullableDefinition(), schema)
                    false -> schema
                }
                serializableName(prop) to nullChecked
            }
        val required = serializableProperties(clazz)
            .asSequence()
            .filterNot { it.javaField == null }
            .filterNot { it.returnType.isMarkedNullable }
            .filterNot { prop ->
                clazz.primaryConstructor
                    ?.parameters
                    ?.find { it.name == prop.name }
                    ?.isOptional
                    ?: false
            }
            .map { serializableName(it) }
            .toSet()
        val definition = TypeDefinition(type = "object", properties = props, required = required)
        cache[slug] = definition
        return definition
    }

    private fun handleNestedGenerics(
        typeMap: Map<KTypeParameter, KTypeProjection>,
        prop: KProperty<*>,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema {
        val propClass = prop.returnType.classifier as KClass<*>
        val types = prop.returnType.arguments.map {
            val typeSymbol = it.type.toString()
            typeMap.filterKeys { k -> k.name == typeSymbol }.values.first()
        }
        val constructedType = propClass.createType(types)
        return fromTypeToSchema(constructedType, cache).let {
            if (it.isObjectOrEnum()) {
                cache[constructedType.slug()] = it
                ReferenceDefinition(constructedType.referenceSlug())
            } else {
                it
            }
        }
    }

    private fun handleGenericProperty(
        prop: KProperty<*>,
        typeMap: Map<KTypeParameter, KTypeProjection>,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema {
        val type = typeMap[prop.returnType.classifier]?.type
            ?: error("Failed to resolve generic type for ${prop.name}")
        return fromTypeToSchema(type, cache).let {
            if (it.isObjectOrEnum()) {
                cache[type.slug()] = it
                ReferenceDefinition(type.referenceSlug())
            } else {
                it
            }
        }
    }

    private fun handleProperty(
        prop: KProperty<*>,
        cache: MutableMap<String, JsonSchema>,
    ): JsonSchema = fromTypeToSchema(prop.returnType, cache).let {
        if (it.isObjectOrEnum()) {
            cache[prop.returnType.slug()] = it
            ReferenceDefinition(prop.returnType.referenceSlug())
        } else {
            it
        }
    }

    private fun serializableProperties(clazz: KClass<*>): Collection<KProperty1<out Any, *>> {
        return clazz.memberProperties
            .filterNot { it.hasAnnotation<Transient>() }
            .filter { clazz.primaryConstructor?.parameters?.map { p -> p.name }?.contains(it.name) ?: true }
    }

    private fun serializableName(property: KProperty1<out Any, *>): String =
        property.annotations.filterIsInstance<SerialName>().firstOrNull()?.value ?: property.name

    private fun KProperty<*>.needsGenericInjection(typeMap: Map<KTypeParameter, KTypeProjection>): Boolean {
        val typeSymbols = returnType.arguments.map { it.type.toString() }
        return typeMap.any { (k, _) -> typeSymbols.contains(k.name) }
    }

    private fun JsonSchema.isObjectOrEnum(): Boolean {
        val isObj = this is TypeDefinition && type == "object"
        val isObjOneOf = this is OneOfDefinition && oneOf.any { it is TypeDefinition && (it as TypeDefinition).type == "object" }
        val isEnum = this is EnumDefinition
        val isEnumOneOf = this is OneOfDefinition && oneOf.any { it is EnumDefinition }
        return isObj || isObjOneOf || isEnum || isEnumOneOf
    }

    private fun JsonSchema.isNullable(): Boolean = this is OneOfDefinition && oneOf.any { it is NullableDefinition }
}

private const val COMPONENT_SLUG = "#/components/schemas"

fun KType.slug(): String = when {
    arguments.isNotEmpty() -> {
        val clazz = classifier as KClass<*>
        val classNames = arguments.map { (it.type?.classifier as? KClass<*>)?.schemaSlug() ?: "Any" }
        classNames.joinToString(separator = "-", prefix = "${clazz.schemaSlug()}-")
    }
    else -> (classifier as KClass<*>).schemaSlug()
}

fun KType.referenceSlug(): String = "$COMPONENT_SLUG/${slug()}"

private fun KClass<*>.schemaSlug(): String {
    if (java.packageName == "java.lang") return simpleName!!
    if (java.packageName == "java.util") return simpleName!!
    val pkg = java.packageName
    return qualifiedName?.replace(pkg, "")?.replace(".", "") ?: simpleName!!
}
