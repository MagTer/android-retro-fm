# OpenTelemetry SDK + opentelemetry-disk-buffering (com.retrofm.android.telemetry).
# Both are referenced but deliberately absent at runtime, and R8 refuses the build without
# these (first seen 2026-10-04, :automotive:minifyReleaseWithR8, "Missing class"):
#  - AutoValue: compile-time annotations on the SDK's data classes; nothing reads them at runtime.
#  - the incubator API: optional. The SDK uses its Extended* classes only when
#    opentelemetry-api-incubator is on the classpath, and this app does not ship it.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
