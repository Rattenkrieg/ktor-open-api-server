package openapi

@RequiresOptIn(
    message = "Raw call access bypasses typed payload safety. " +
        "Prefer using PayloadItem types (Body, PathParam, QueryParam, HeaderParam, Principal) instead.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class RawCallAccess
