package io.amichne.checKer.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName

class IntGuardProcessor(
    private val env: SymbolProcessorEnvironment
) : SymbolProcessor {

    // --- local model types used by this processor ---
    private data class Bound(
        val value: Long,
        val inclusive: Boolean
    )

    private data class Bounds(
        val min: Bound? = null,
        val max: Bound? = null
    )

    private enum class TargetKind { Scalar, Collection, Array, MapValues }
    private data class ConstrainedProp(
        val name: String,
        val kind: TargetKind,
        val bounds: Bounds
    )

    //    val packageName = "io.amichne.checKer.ksp"
    val packageName = "io.amichne.app"

    private fun KSType.toRef(resolver: Resolver) = resolver.createKSTypeReferenceFromKSType(this)

    // --- registry stubs to satisfy unresolved references (no-op for now) ---
    private fun generateRegistrarEntry(@Suppress("UNUSED_PARAMETER") clazz: KSClassDeclaration) { /* no-op */
    }

    private fun finalizeRegistrarFile() { /* no-op */
    }

    // --- type helpers ---
    private fun isCollectionOfCustomInt(
        t: KSType,
        customInt: KSType,
        resolver: Resolver
    ): Boolean {
        val notNullT = t.makeNotNullable()
        val iterableOfElem = resolver.builtIns.iterableType.replace(
            listOf(
                resolver.getTypeArgument(
                    resolver.createKSTypeReferenceFromKSType(customInt.makeNotNullable()), Variance.INVARIANT
                )
            )
        )
        // True if t is Iterable<CustomInt> or any subtype (List, Set, Collection)
        return iterableOfElem.isAssignableFrom(notNullT)
    }

    private fun isMapOfCustomInt(
        t: KSType,
        customInt: KSType,
        resolver: Resolver
    ): Boolean {
        val notNullT = t.makeNotNullable()
        val mapDecl = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("kotlin.collections.Map")
        ) ?: return false
        val star = resolver.getTypeArgument(
            resolver.createKSTypeReferenceFromKSType(resolver.builtIns.anyType),
            Variance.STAR
        )
        val valueArg = resolver.getTypeArgument(customInt.makeNotNullable().toRef(resolver), Variance.INVARIANT)
        val mapOfStarToElem = mapDecl.asType(listOf(star, valueArg))
        return mapOfStarToElem.isAssignableFrom(notNullT)
    }

    private fun isArrayOfCustomInt(
        t: KSType,
        customInt: KSType,
        resolver: Resolver
    ): Boolean {
        val notNullT = t.makeNotNullable()
        val elem = customInt.makeNotNullable()
        val arrayOfElem = resolver.builtIns.arrayType.replace(
            listOf(resolver.getTypeArgument(elem.toRef(resolver), Variance.INVARIANT))
        )
        return arrayOfElem.isAssignableFrom(notNullT)
    }

    private val logger = env.logger
    private val codegen = env.codeGenerator

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val min = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.Min")
        ) ?: return emptyList()
        val max = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.Max")
        )!!
        val range = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.Range")
        )!!
        val customInt = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("$packageName.CustomInteger")
        )!!.asStarProjectedType()

        resolver.getSymbolsWithAnnotation("$packageName.Min")
            .plus(resolver.getSymbolsWithAnnotation("$packageName.Max"))
            .plus(resolver.getSymbolsWithAnnotation("$packageName.Range"))
            .mapNotNull { it.closestClassDeclaration() }
            .distinct()
            .forEach { clazz ->
                generateForClass(resolver, clazz, customInt)
                generateRegistrarEntry(clazz) // for Moshi/Jackson/kotlinx registry
            }

        finalizeRegistrarFile() // writes META-INF/services entry + aggregator object
        return emptyList()
    }

    private fun KSAnnotated.closestClassDeclaration(): KSClassDeclaration? =
        when (this) {
            is KSPropertyDeclaration -> this.parentDeclaration as? KSClassDeclaration
            is KSClassDeclaration -> this
            else -> null
        }

    private fun generateForClass(
        resolver: Resolver,
        clazz: KSClassDeclaration,
        customInt: KSType
    ) {
        val pkg = clazz.packageName.asString()
        val simple = clazz.simpleName.asString()
        val fileName = "${simple}_Validation"
        val props = clazz.getAllProperties().toList()

        // Collect constraints per property (merge Min/Max into Range when needed)
        val constraints = props.mapNotNull { p ->
            val t = p.type.resolve()
            val targetKind = classifyTarget(t, customInt, resolver) ?: return@mapNotNull null
            val c = readConstraints(p) ?: return@mapNotNull null
            ConstrainedProp(p.simpleName.asString(), targetKind, c)
        }

        if (constraints.isEmpty()) return

        // Build KotlinPoet file
        val fileSpec = FileSpec.builder(pkg, fileName)
            .addType(buildValidationObject(pkg, simple, constraints))
            .addFunction(buildCompanionInvoke(clazz, constraints))
            .addFunction(buildValidatedCopy(clazz))
            .build()

        // Write
        codegen.createNewFile(
            Dependencies(aggregating = true, clazz.containingFile!!),
            pkg, fileName
        ).writer().use { out -> fileSpec.writeTo(out) }
    }

//    override fun process(resolver: Resolver): List<KSAnnotated> {
//        resolver.getClassDeclarationByName(
//            resolver.getKSNameFromString("$packageName.Min")
//        ) ?: return emptyList()
//        resolver.getClassDeclarationByName(
//            resolver.getKSNameFromString("$packageName.Max")
//        )!!
//        resolver.getClassDeclarationByName(
//            resolver.getKSNameFromString("$packageName.Range")
//        )!!
//        val customInt = resolver.getClassDeclarationByName(
//            resolver.getKSNameFromString("$packageName.CustomInteger")
//        )!!.asStarProjectedType()
//
//        resolver.getSymbolsWithAnnotation("$packageName.Min").plus(resolver.getSymbolsWithAnnotation("$packageName.Max"))
//            .plus(resolver.getSymbolsWithAnnotation("$packageName.Range")).mapNotNull { it.closestClassDeclaration() }
//            .distinct().forEach { clazz ->
//                generateForClass(resolver, clazz, customInt)
//                generateRegistrarEntry(clazz) // for Moshi/Jackson/kotlinx registry
//            }
//
//        finalizeRegistrarFile() // writes META-INF/services entry + aggregator object
//        return emptyList()
//    }
//
//    private fun KSAnnotated.closestClassDeclaration(): KSClassDeclaration? = when (this) {
//        is KSPropertyDeclaration -> this.parentDeclaration as? KSClassDeclaration
//        is KSClassDeclaration -> this
//        else -> null
//    }
//
//    private fun generateForClass(
//        resolver: Resolver,
//        clazz: KSClassDeclaration,
//        customInt: KSType
//    ) {
//        val pkg = clazz.packageName.asString()
//        val simple = clazz.simpleName.asString()
//        val fileName = "${simple}_Validation"
//        val props = clazz.getAllProperties().toList()
//
//        // Collect constraints per property (merge Min/Max into Range when needed)
//        val constraints = props.mapNotNull { p ->
//            val t = p.type.resolve()
//            val targetKind = classifyTarget(t, customInt, resolver) ?: return@mapNotNull null
//            val c = readConstraints(p) ?: return@mapNotNull null
//            ConstrainedProp(p.simpleName.asString(), targetKind, c)
//        }
//
//        if (constraints.isEmpty()) return
//
//        // Build KotlinPoet file
//        val fileSpec = FileSpec.builder(pkg, fileName)
//            .addType(buildValidationObject(pkg, simple, constraints))
//            .addFunction(buildCompanionInvoke(clazz, constraints))
//            .addFunction(buildValidatedCopy(clazz))
//            .build()
//
//        // Write
//        codegen.createNewFile(
//            Dependencies(aggregating = true, clazz.containingFile!!), pkg, fileName
//        ).writer().use { out -> fileSpec.writeTo(out) }
//    }

    // --- helpers (sketches) ---

    private fun classifyTarget(
        t: KSType,
        customInt: KSType,
        resolver: Resolver
    ): TargetKind? {
        return when {
            customInt.isAssignableFrom(t) -> TargetKind.Scalar
            isArrayOfCustomInt(t, customInt, resolver) -> TargetKind.Array
            isCollectionOfCustomInt(t, customInt, resolver) -> TargetKind.Collection
            isMapOfCustomInt(t, customInt, resolver) -> TargetKind.MapValues
            else -> null
        }
    }

    private fun readConstraints(p: KSPropertyDeclaration): Bounds? {
        val min = p.annotations.firstOrNull { it.shortName.asString() == "Min" }?.let {
            Bound(it.arguments[0].value as Long, inclusive = it.arguments.getOrNull(1)?.value as? Boolean ?: true)
        }
        val max = p.annotations.firstOrNull { it.shortName.asString() == "Max" }?.let {
            Bound(it.arguments[0].value as Long, inclusive = it.arguments.getOrNull(1)?.value as? Boolean ?: true)
        }
        val range = p.annotations.firstOrNull { it.shortName.asString() == "Range" }?.let {
            Bounds(
                min = Bound(
                    it.arguments[0].value as Long, inclusive = it.arguments.getOrNull(2)?.value as? Boolean ?: true
                ), max = Bound(
                it.arguments[1].value as Long, inclusive = it.arguments.getOrNull(3)?.value as? Boolean ?: true
            )
            )
        }
        return range ?: if (min != null || max != null) Bounds(min, max) else null
    }

    private fun buildValidationObject(
        pkg: String,
        simple: String,
        props: List<ConstrainedProp>
    ): TypeSpec {
        val validationDefaults = ClassName(packageName, "ValidationDefaults")
        val validationConfig = ClassName(packageName, "ValidationConfig")
        val owner = ClassName(pkg, simple)

        val cfgParam =
            ParameterSpec.builder("cfg", validationConfig).defaultValue("%T.config", validationDefaults).build()

        return TypeSpec.objectBuilder("${simple}_Validation").addFunction(
            FunSpec.builder("validate").addParameter("instance", owner).addParameter(cfgParam)
                .addCode(buildValidateBody("instance", props)).build()
        ).build()
    }

    private fun buildValidateBody(
        receiver: String,
        props: List<ConstrainedProp>
    ): CodeBlock {
        val Violation = ClassName(packageName, "Violation")
        val Mode = ClassName(packageName, "Mode")
        val ConstraintViolationException = ClassName(packageName, "ConstraintViolationException")

        fun CodeBlock.Builder.emitChecks(
            valueExpr: String,
            pathExpr: String,
            pathIsVar: Boolean,
            bounds: Bounds
        ): CodeBlock.Builder {
            bounds.min?.let { mn ->
                // FAIL_FAST branch
                beginControlFlow("if (cfg.mode === %T.FAIL_FAST)", Mode).beginControlFlow(
                    "if (%L %L %LL)", valueExpr, if (mn.inclusive) "<" else "<=", mn.value
                ).addStatement(
                    "throw %T(listOf(%T(%L, %S, %L, %S)))", ConstraintViolationException, Violation,
                    if (pathIsVar) CodeBlock.of(pathExpr) else CodeBlock.of("%S", pathExpr),
                    "Min(${mn.value} ${if (mn.inclusive) "inclusive" else "exclusive"})", CodeBlock.of(valueExpr),
                    ""
                ).endControlFlow().nextControlFlow("else")
                    .beginControlFlow("if (%L %L %LL)", valueExpr, if (mn.inclusive) "<" else "<=", mn.value)
                    .addStatement(
                        "violations!!.add(%T(%L, %S, %L, %S))", Violation,
                        if (pathIsVar) CodeBlock.of(pathExpr) else CodeBlock.of("%S", pathExpr),
                        "Min(${mn.value} ${if (mn.inclusive) "inclusive" else "exclusive"})", CodeBlock.of(valueExpr),
                        ""
                    ).endControlFlow().endControlFlow()
            }
            bounds.max?.let { mx ->
                beginControlFlow("if (cfg.mode === %T.FAIL_FAST)", Mode).beginControlFlow(
                    "if (%L %L %LL)", valueExpr, if (mx.inclusive) ">" else ">=", mx.value
                ).addStatement(
                    "throw %T(listOf(%T(%L, %S, %L, %S)))", ConstraintViolationException, Violation,
                    if (pathIsVar) CodeBlock.of(pathExpr) else CodeBlock.of("%S", pathExpr),
                    "Max(${mx.value} ${if (mx.inclusive) "inclusive" else "exclusive"})", CodeBlock.of(valueExpr),
                    ""
                ).endControlFlow().nextControlFlow("else")
                    .beginControlFlow("if (%L %L %LL)", valueExpr, if (mx.inclusive) ">" else ">=", mx.value)
                    .addStatement(
                        "violations!!.add(%T(%L, %S, %L, %S))", Violation,
                        if (pathIsVar) CodeBlock.of(pathExpr) else CodeBlock.of("%S", pathExpr),
                        "Max(${mx.value} ${if (mx.inclusive) "inclusive" else "exclusive"})", CodeBlock.of(valueExpr),
                        ""
                    ).endControlFlow().endControlFlow()
            }
            return this
        }

        val b = CodeBlock.builder()
        // violations holder: null for FAIL_FAST, list for COLLECT_ALL
        b.addStatement("val violations = if (cfg.mode === %T.FAIL_FAST) null else mutableListOf<%T>()", Mode, Violation)

        props.forEach { p ->
            when (p.kind) {
                TargetKind.Scalar -> {
                    // instance.<prop>.value
                    b.addStatement("run {")
                    b.addStatement("  val x = %L.%L.value", receiver, p.name)
                    b.emitChecks("x", p.name, false, p.bounds)
                    b.addStatement("}")
                }

                TargetKind.Collection -> {
                    // for ((i, e) in instance.<prop>.withIndex()) { val x = e.value; val __path = "name["+i+"]"; checks }
                    b.beginControlFlow("for ((i, e) in %L.%L.withIndex())", receiver, p.name)
                        .addStatement("val x = e.value").addStatement("val __path = %S + i + %S", "${p.name}[", "]")
                        .emitChecks("x", "__path", true, p.bounds).endControlFlow()
                }

                TargetKind.Array -> {
                    // for (i in instance.<prop>.indices) { val x = instance.<prop>[i].value; val __path = ...; checks }
                    b.beginControlFlow("for (i in %L.%L.indices)", receiver, p.name)
                        .addStatement("val x = %L.%L[i].value", receiver, p.name)
                        .addStatement("val __path = %S + i + %S", "${p.name}[", "]")
                        .emitChecks("x", "__path", true, p.bounds).endControlFlow()
                }

                TargetKind.MapValues -> {
                    // for ((k, v) in instance.<prop>) { val x = v.value; val __path = "name['"+k+"']"; checks }
                    b.beginControlFlow("for ((k, v) in %L.%L)", receiver, p.name).addStatement("val x = v.value")
                        .addStatement("val __path = %S + k.toString() + %S", "${p.name}['", "']")
                        .emitChecks("x", "__path", true, p.bounds).endControlFlow()
                }
            }
        }

        // At the end, if collecting, throw if any violations
        b.beginControlFlow("if (violations != null && violations.isNotEmpty())")
            .addStatement("throw %T(violations)", ConstraintViolationException).endControlFlow()

        return b.build()
    }

    private fun buildCompanionInvoke(
        clazz: KSClassDeclaration,
        props: List<ConstrainedProp>
    ): FunSpec {
        val owner = ClassName(clazz.packageName.asString(), clazz.simpleName.asString())

        val f = FunSpec.builder("invoke")
            .addModifiers(KModifier.OPERATOR, KModifier.PUBLIC)
            .addAnnotation(ClassName("kotlin.jvm", "JvmStatic"))
            .addAnnotation(ClassName("kotlin.jvm", "JvmOverloads"))
            .returns(owner)

        val ctorParams = clazz.primaryConstructor!!.parameters

        ctorParams.forEach { p ->
            val name = p.name!!.asString()

            // TypeName from KSType via kotlinpoet-ksp extension
            val rawType = p.type.resolve().toTypeName()

            // Handle vararg: KotlinPoet expects the *element* type + VARARG modifier,
            // not Array<...>
            val (typeName, modifiers) =
                if (p.isVararg) {
                    val elem = p.type.element!!.typeArguments.single().type!!.resolve().toTypeName()
                    elem to arrayOf(KModifier.VARARG)
                } else {
                    rawType to emptyArray()
                }

            val builder = ParameterSpec.builder(name, typeName)
                .addModifiers(*modifiers)

            // We would mirror constructor defaults, if it were available. Simply ignore for time being
//            if (p.hasDefault) {
//                builder.defaultValue("%L", CodeBlock.of(p.!!.toString()))
//            }

            val param = builder.build()
            f.addParameter(param)
        }

        val argList = ctorParams.joinToString(", ") { it.name!!.asString() }
        f.addStatement("return %T(%L)", owner, argList)
        return f.build()
    }

    private fun buildValidatedCopy(
        clazz: KSClassDeclaration
    ): FunSpec {
        val pkg = clazz.packageName.asString()
        val simple = clazz.simpleName.asString()
        val owner = ClassName(pkg, simple)
        val validator = ClassName(pkg, "${simple}_Validation")

        val f = FunSpec.builder("validatedCopy")
            .receiver(owner)
            .returns(owner)

        val params = clazz.primaryConstructor!!.parameters
        params.forEach { p ->
            val name = p.name!!.asString()
            val typeName = p.type.resolve().toTypeName()
            val param = ParameterSpec.builder(name, typeName)
                .defaultValue("this.%L", name)
                .build()
            f.addParameter(param)
        }

        val argList = params.joinToString(", ") { it.name!!.asString() }
        f.addStatement("val candidate = %T(%L)", owner, argList)
        f.addStatement("%T.validate(candidate)", validator)
        f.addStatement("return candidate")
        return f.build()
    }
}
