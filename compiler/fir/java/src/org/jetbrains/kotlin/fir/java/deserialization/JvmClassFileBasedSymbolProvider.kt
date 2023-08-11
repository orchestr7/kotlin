/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.deserialization

import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.ThreadSafeMutableState
import org.jetbrains.kotlin.fir.builder.toMutableOrEmpty
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.deserialization.AbstractFirDeserializedSymbolProvider
import org.jetbrains.kotlin.fir.deserialization.FirDeserializationContext
import org.jetbrains.kotlin.fir.deserialization.ModuleDataProvider
import org.jetbrains.kotlin.fir.deserialization.PackagePartsCacheData
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.java.FirJavaFacade
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.transformers.setLazyPublishedVisibility
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.load.kotlin.*
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.jvm.JvmProtoBuf
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmFlags
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.protobuf.InvalidProtocolBufferException
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.serialization.deserialization.IncompatibleVersionErrorData
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.utils.toMetadataVersion
import java.nio.file.Path
import java.nio.file.Paths

// This symbol provider loads JVM classes, reading extra info from Kotlin `@Metadata` annotations
// if present. Use it for library and incremental compilation sessions. For source sessions use
// `JavaSymbolProvider`, as Kotlin classes should be parsed first.
@ThreadSafeMutableState
class JvmClassFileBasedSymbolProvider(
    session: FirSession,
    moduleDataProvider: ModuleDataProvider,
    kotlinScopeProvider: FirKotlinScopeProvider,
    private val packagePartProvider: PackagePartProvider,
    private val kotlinClassFinder: KotlinClassFinder,
    private val javaFacade: FirJavaFacade,
    defaultDeserializationOrigin: FirDeclarationOrigin = FirDeclarationOrigin.Library
) : AbstractFirDeserializedSymbolProvider(
    session, moduleDataProvider, kotlinScopeProvider, defaultDeserializationOrigin, BuiltInSerializerProtocol
) {
    private val annotationsLoader = AnnotationsLoader(session, kotlinClassFinder)
    private val ownMetadataVersion: JvmMetadataVersion = session.languageVersionSettings.languageVersion.toMetadataVersion()

    override fun computePackagePartsInfos(packageFqName: FqName): List<PackagePartsCacheData> =
        packagePartProvider.findPackageParts(packageFqName.asString()).mapNotNull { partName ->
            computePackagePartInfo(packageFqName, partName)
        }

    private fun computePackagePartInfo(packageFqName: FqName, partName: String): PackagePartsCacheData? {
        if (partName in KotlinBuiltins) return null

        val classId = ClassId.topLevel(JvmClassName.byInternalName(partName).fqNameForTopLevelClassMaybeWithDollars)
        if (!javaFacade.hasTopLevelClassOf(classId)) return null
        val (kotlinClass, byteContent) =
            kotlinClassFinder.findKotlinClassOrContent(classId, ownMetadataVersion) as? KotlinClassFinder.Result.KotlinClass ?: return null

        val facadeName = kotlinClass.classHeader.multifileClassName?.takeIf { it.isNotEmpty() }
        val facadeFqName = facadeName?.let { JvmClassName.byInternalName(it).fqNameForTopLevelClassMaybeWithDollars }
        val facadeBinaryClass = facadeFqName?.let {
            kotlinClassFinder.findKotlinClass(ClassId.topLevel(it), ownMetadataVersion)
        }

        val moduleData = moduleDataProvider.getModuleData(kotlinClass.containingLibrary.toPath()) ?: return null

        val header = kotlinClass.classHeader
        val data = header.data ?: header.incompatibleData ?: return null
        val strings = header.strings ?: return null
        val (nameResolver, packageProto) = parseProto(kotlinClass) {
            JvmProtoBufUtil.readPackageDataFrom(data, strings)
        } ?: return null

        val source = JvmPackagePartSource(
            kotlinClass, packageProto, nameResolver, kotlinClass.incompatibility, kotlinClass.isPreReleaseInvisible,
        )

        return PackagePartsCacheData(
            packageProto,
            FirDeserializationContext.createForPackage(
                packageFqName, packageProto, nameResolver, moduleData,
                JvmBinaryAnnotationDeserializer(session, kotlinClass, kotlinClassFinder, byteContent),
                FirJvmConstDeserializer(session, facadeBinaryClass ?: kotlinClass, BuiltInSerializerProtocol),
                source
            ),
        )
    }

    override fun computePackageSetWithNonClassDeclarations(): Set<String> = packagePartProvider.computePackageSetWithNonClassDeclarations()

    override fun knownTopLevelClassesInPackage(packageFqName: FqName): Set<String>? = javaFacade.knownClassNamesInPackage(packageFqName)

    private val KotlinJvmBinaryClass.incompatibility: IncompatibleVersionErrorData<JvmMetadataVersion>?
        get() {
            if (session.languageVersionSettings.getFlag(AnalysisFlags.skipMetadataVersionCheck)) return null

            if (classHeader.metadataVersion.isCompatible(ownMetadataVersion)) return null
            return IncompatibleVersionErrorData(
                actualVersion = classHeader.metadataVersion,
                compilerVersion = JvmMetadataVersion.INSTANCE,
                languageVersion = ownMetadataVersion,
                expectedVersion = ownMetadataVersion.lastSupportedVersionWithThisLanguageVersion(classHeader.metadataVersion.isStrictSemantics),
                filePath = location,
                classId = classId
            )
        }

    private val KotlinJvmBinaryClass.isPreReleaseInvisible: Boolean
        get() = classHeader.isPreRelease

    override fun extractClassMetadata(classId: ClassId, parentContext: FirDeserializationContext?): ClassMetadataFindResult? {
        // Kotlin classes are annotated Java classes, so this check also looks for them.
        if (!javaFacade.hasTopLevelClassOf(classId)) return null

        val result = kotlinClassFinder.findKotlinClassOrContent(classId, ownMetadataVersion)
        if (result !is KotlinClassFinder.Result.KotlinClass) {
            if (parentContext != null || (classId.isNestedClass && getClass(classId.outermostClassId)?.fir !is FirJavaClass)) {
                // Nested class of Kotlin class should have been a Kotlin class.
                return null
            }
            val knownContent = (result as? KotlinClassFinder.Result.ClassFileContent)?.content
            val javaClass = javaFacade.findClass(classId, knownContent) ?: return null
            return ClassMetadataFindResult.NoMetadata { symbol ->
                javaFacade.convertJavaClassToFir(symbol, classId.outerClassId?.let(::getClass), javaClass)
            }
        }

        val kotlinClass = result.kotlinJvmBinaryClass
        if (kotlinClass.classHeader.kind != KotlinClassHeader.Kind.CLASS || kotlinClass.classId != classId) return null
        val data = kotlinClass.classHeader.data ?: kotlinClass.classHeader.incompatibleData ?: return null
        val strings = kotlinClass.classHeader.strings ?: return null
        val (nameResolver, classProto) = parseProto(kotlinClass) {
            JvmProtoBufUtil.readClassDataFrom(data, strings)
        } ?: return null

        return ClassMetadataFindResult.Metadata(
            nameResolver,
            classProto,
            JvmBinaryAnnotationDeserializer(session, kotlinClass, kotlinClassFinder, result.byteContent),
            moduleDataProvider.getModuleData(kotlinClass.containingLibrary?.toPath()),
            KotlinJvmBinarySourceElement(kotlinClass, kotlinClass.incompatibility),
            classPostProcessor = { loadAnnotationsFromClassFile(result, it) }
        )
    }

    override fun isNewPlaceForBodyGeneration(classProto: ProtoBuf.Class): Boolean =
        JvmFlags.IS_COMPILED_IN_JVM_DEFAULT_MODE.get(classProto.getExtension(JvmProtoBuf.jvmClassFlags))

    override fun getPackage(fqName: FqName): FqName? =
        javaFacade.getPackage(fqName)

    private fun loadAnnotationsFromClassFile(
        kotlinClass: KotlinClassFinder.Result.KotlinClass,
        symbol: FirRegularClassSymbol
    ) {
        val annotations = mutableListOf<FirAnnotation>()
        var hasPublishedApi = false
        kotlinClass.kotlinJvmBinaryClass.loadClassAnnotations(
            object : KotlinJvmBinaryClass.AnnotationVisitor {
                override fun visitAnnotation(classId: ClassId, source: SourceElement): KotlinJvmBinaryClass.AnnotationArgumentVisitor? {
                    if (classId == StandardClassIds.Annotations.PublishedApi) {
                        hasPublishedApi = true
                    }
                    return annotationsLoader.loadAnnotationIfNotSpecial(classId, annotations)
                }

                override fun visitEnd() {
                }
            },
            kotlinClass.byteContent,
        )
        symbol.fir.run {
            replaceAnnotations(annotations.toMutableOrEmpty())
            replaceDeprecationsProvider(symbol.fir.getDeprecationsProvider(session))
            setLazyPublishedVisibility(hasPublishedApi, null, session)
        }
    }

    private fun String?.toPath(): Path? {
        return this?.let { Paths.get(it).normalize() }
    }

    private inline fun <T : Any> parseProto(klass: KotlinJvmBinaryClass, block: () -> T): T? =
        try {
            block()
        } catch (e: Throwable) {
            if (session.languageVersionSettings.getFlag(AnalysisFlags.skipMetadataVersionCheck) ||
                klass.classHeader.metadataVersion.isCompatible(ownMetadataVersion)
            ) {
                throw if (e is InvalidProtocolBufferException)
                    IllegalStateException("Could not read data from ${klass.location}", e)
                else e
            }

            null
        }
}
