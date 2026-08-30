// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.capturedKClass
import kotlin.reflect.KClass
import kotlin.reflect.KType

private const val COMPONENT_SLUG = "#/components/schemas"

/** A candidate schema component name, e.g. "Status", before collision resolution. */
@JvmInline
value class SchemaSlug(val value: String)

/**
 * Identifies "the same class" for slug collision detection: by `KClass.qualifiedName` when
 * available, otherwise by the descriptor's own shape (kind + element names). Shape, not the
 * `SerialDescriptor` itself, because a hand-built descriptor's `equals` can fail against itself
 * once nested element descriptors are involved, even for the same real class.
 */
sealed class ClassIdentity {
    data class ByQualifiedName(val name: String) : ClassIdentity()
    data class ByShape(val kind: String, val elementNames: List<String>) : ClassIdentity()
}

private fun resolveSlug(
    shortSlug: String,
    identity: ClassIdentity,
    slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    longSlug: () -> String,
): String {
    val slug = SchemaSlug(shortSlug)
    val owner = slugOwners[slug]
    if (owner == null) {
        slugOwners[slug] = identity
        return shortSlug
    }
    if (owner == identity) return shortSlug
    // Reuse this identity's previously-assigned disambiguated slug, if any, instead of
    // recomputing a fresh one on every repeated collision.
    slugOwners.entries.firstOrNull { it.value == identity }?.let { return it.key.value }
    val disambiguated = longSlug()
    slugOwners[SchemaSlug(disambiguated)] = identity
    return disambiguated
}

fun KType.slug(slugOwners: MutableMap<SchemaSlug, ClassIdentity>): String = when {
    arguments.isNotEmpty() -> {
        val clazz = classifier as KClass<*>
        val classNames = arguments.map { (it.type?.classifier as? KClass<*>)?.schemaSlug(slugOwners) ?: "Any" }
        classNames.joinToString(separator = "-", prefix = "${clazz.schemaSlug(slugOwners)}-")
    }
    else -> (classifier as KClass<*>).schemaSlug(slugOwners)
}

fun KType.referenceSlug(slugOwners: MutableMap<SchemaSlug, ClassIdentity>): String =
    "$COMPONENT_SLUG/${slug(slugOwners)}"

private fun shortSlugParts(name: String): List<String> {
    val parts = name.split('.')
    val classStart = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    return if (classStart >= 0) parts.subList(classStart, parts.size) else listOf(parts.last())
}

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.plainSlug(): String = shortSlugParts(serialName.removeSuffix("?")).joinToString("")

// Disambiguated by the enclosing type, not `slugOwners.size` — never depends on registration order.
private fun disambiguateByShape(shortSlug: String, outerSlug: String?, shape: ClassIdentity.ByShape): String =
    outerSlug?.let { "$it$shortSlug" }
        ?: "${shortSlug}_${shapeHash(shape)}"

private fun shapeHash(shape: ClassIdentity.ByShape): String =
    Integer.toHexString((shape.kind + shape.elementNames.joinToString(",")).hashCode())

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.slug(
    slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    outerSlug: String? = null,
): String {
    val name = serialName.removeSuffix("?")
    val shortSlug = shortSlugParts(name).joinToString("")
    // capturedKClass is usually null; the un-shortened serialName is a fully qualified name
    // unless @SerialName overrode it to exactly the colliding short form.
    val qualifiedName = capturedKClass?.qualifiedName ?: name.takeIf { it != shortSlug }
    val shape = ClassIdentity.ByShape(kind.toString(), (0 until elementsCount).map { getElementName(it) })
    val identity = qualifiedName?.let { ClassIdentity.ByQualifiedName(it) } ?: shape
    return resolveSlug(shortSlug, identity, slugOwners) {
        qualifiedName?.replace(".", "") ?: disambiguateByShape(shortSlug, outerSlug, shape)
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.referenceSlug(
    slugOwners: MutableMap<SchemaSlug, ClassIdentity>,
    outerSlug: String? = null,
): String = "$COMPONENT_SLUG/${slug(slugOwners, outerSlug)}"

private fun KClass<*>.schemaSlug(slugOwners: MutableMap<SchemaSlug, ClassIdentity>): String {
    val pkg = java.packageName
    if (pkg == "java.lang" || pkg == "java.util") return simpleName!!
    val qualifiedName = qualifiedName ?: return simpleName!!
    val shortSlug = qualifiedName.replace(pkg, "").replace(".", "")
    return resolveSlug(shortSlug, ClassIdentity.ByQualifiedName(qualifiedName), slugOwners) { qualifiedName.replace(".", "") }
}
