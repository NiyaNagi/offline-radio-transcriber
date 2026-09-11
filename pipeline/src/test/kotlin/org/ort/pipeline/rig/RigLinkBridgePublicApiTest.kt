package org.ort.pipeline.rig

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * A coordinator finding, live: [PairedRigDevice.sppSupport] was declared as `:rig-bluetooth`'s
 * `org.ort.rig.bluetooth.SppSupport` directly. `:app` may not depend on `:rig-bluetooth`
 * (`ModuleGraph`'s `allowed[":app"]` names only `:pipeline`, `:data`, `:net`, `:core`, `:lexicon`,
 * `:rig`, `:llm-api` — constitution VII), and `pipeline/build.gradle.kts` declares `:rig-bluetooth`/
 * `:rig-usb` `implementation`, not `api` (correctly — making it `api` would leak the forbidden
 * module transitively to every `:pipeline` consumer without a declared edge, `:app` included). The
 * one real consumer, `:app`'s `BridgeRigLinkPort`, could not even `import` the declared type to
 * write a `when` on it, and fell back to reading the value through `java.lang.reflect` instead —
 * confirmed by reading that class's own kdoc before writing this fix.
 *
 * Fixed by introducing [RigLinkSppSupport] — this bridge's own enum, mapped from
 * `org.ort.rig.bluetooth.SppSupport` inside [DefaultRigLinkBridge] alone. This test is the
 * regression guard: it walks every public constructor/method/field this package exposes — the
 * bridge's entire public surface, exactly what a `:pipeline` consumer such as `:app` can see or be
 * handed — and fails if any declared, generic-parameter or generic-return type belongs to
 * `org.ort.rig.bluetooth` or `org.ort.rig.usb`. A consumer allowed to depend on `:pipeline`/`:rig`/
 * `:core` only must never be handed a type it cannot resolve.
 */
public class RigLinkBridgePublicApiTest {

    @Test
    public fun `WPC3 the bridge's public API references no rig-bluetooth or rig-usb type`() {
        val offenders = SURFACE.flatMap(::forbiddenTypesIn)
        assertTrue(
            offenders.isEmpty(),
            "the RigLinkBridge public surface must never reference an org.ort.rig.bluetooth/" +
                "org.ort.rig.usb type (constitution VII, ModuleGraph[\":app\"]); found:\n" +
                offenders.joinToString("\n"),
        )
    }

    private companion object {
        /** Every class WPC3's bridge hands to, or accepts from, a caller — the interface, its two
         * implementations, every value type it returns, and every state [RigLinkProbeState]
         * declares. Deliberately explicit rather than a classpath scan: this is the exact set a
         * consumer module can see or be handed, no more and no less. */
        val SURFACE: List<Class<*>> = listOf(
            RigLinkBridge::class.java,
            DefaultRigLinkBridge::class.java,
            FakeRigLinkBridge::class.java,
            PairedRigDevice::class.java,
            PairedRigDevicesResult::class.java,
            RigLinkSppSupport::class.java,
            RigLinkProbeState::class.java,
            RigLinkProbeState.Opening::class.java,
            RigLinkProbeState.Open::class.java,
            RigLinkProbeState.Identified::class.java,
            RigLinkProbeState.Verified::class.java,
            RigLinkProbeState.Lost::class.java,
            RigLinkProbeState.NoPermission::class.java,
            RigLinkProbeState.Failed::class.java,
        )

        val FORBIDDEN_PACKAGE_PREFIXES: List<String> = listOf("org.ort.rig.bluetooth", "org.ort.rig.usb")

        fun forbiddenTypesIn(clazz: Class<*>): List<String> {
            val found = mutableListOf<String>()
            for (method: Method in clazz.declaredMethods) {
                if (!Modifier.isPublic(method.modifiers)) continue
                found += walk(method.genericReturnType, "${clazz.name}#${method.name}() return type")
                method.genericParameterTypes.forEachIndexed { index, type ->
                    found += walk(type, "${clazz.name}#${method.name}() parameter $index")
                }
            }
            for (ctor: Constructor<*> in clazz.declaredConstructors) {
                if (!Modifier.isPublic(ctor.modifiers)) continue
                ctor.genericParameterTypes.forEachIndexed { index, type ->
                    found += walk(type, "${clazz.name}(...) constructor parameter $index")
                }
            }
            for (field: Field in clazz.declaredFields) {
                if (!Modifier.isPublic(field.modifiers)) continue
                found += walk(field.genericType, "${clazz.name}#${field.name} field")
            }
            return found
        }

        /** Recursively walks a [Type] — a plain [Class], a generic `List<Foo>`
         * ([ParameterizedType]), an array, a wildcard or a type variable's bounds — collecting a
         * label for every [Class] whose package starts with a forbidden prefix. */
        fun walk(type: Type, label: String): List<String> {
            val found = mutableListOf<String>()
            fun visit(t: Type) {
                when (t) {
                    is Class<*> -> {
                        val packageName = t.name.substringBeforeLast('.', missingDelimiterValue = "")
                        if (FORBIDDEN_PACKAGE_PREFIXES.any { packageName.startsWith(it) }) {
                            found += "$label -> ${t.name}"
                        }
                        if (t.isArray) visit(t.componentType)
                    }
                    is ParameterizedType -> {
                        visit(t.rawType)
                        t.actualTypeArguments.forEach(::visit)
                    }
                    is GenericArrayType -> visit(t.genericComponentType)
                    is WildcardType -> {
                        t.upperBounds.forEach(::visit)
                        t.lowerBounds.forEach(::visit)
                    }
                    is TypeVariable<*> -> t.bounds.forEach(::visit)
                }
            }
            visit(type)
            return found
        }
    }
}
