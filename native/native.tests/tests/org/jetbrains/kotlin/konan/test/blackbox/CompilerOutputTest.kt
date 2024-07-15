/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox

import com.intellij.testFramework.TestDataPath
import org.jetbrains.kotlin.cli.AbstractCliTest
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.konan.test.blackbox.support.*
import org.jetbrains.kotlin.konan.test.blackbox.support.ClassLevelProperty
import org.jetbrains.kotlin.konan.test.blackbox.support.EnforcedProperty
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.*
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.LibraryCompilation
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.ObjCFrameworkCompilation
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.test.blackbox.support.group.FirPipeline
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.CacheMode
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.PipelineType
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.Settings
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertIs

abstract class CompilerOutputTestBase : AbstractNativeSimpleTest() {
    @Test
    fun testReleaseCompilerAgainstPreReleaseLibrary() {
        // https://youtrack.jetbrains.com/issue/KT-64822
        Assumptions.assumeFalse(
            testRunSettings.get<PipelineType>() == PipelineType.K1,
            "KT-64822: the test flaks, to be investigated"
        )

        // We intentionally use JS testdata, because the compilers should behave the same way in such a test.
        // To be refactored later, after CompileKotlinAgainstCustomBinariesTest.testReleaseCompilerAgainstPreReleaseLibraryJs is fixed.
        val rootDir = File("compiler/testData/compileKotlinAgainstCustomBinaries/releaseCompilerAgainstPreReleaseLibraryJs")

        // Debug output for KT-64822 investigation
        println("${MessageRenderer.PROPERTY_KEY}=${System.getProperty(MessageRenderer.PROPERTY_KEY)}")

        doTestPreReleaseKotlinLibrary(rootDir, emptyList())
    }

    @Test
    fun testReleaseCompilerAgainstPreReleaseLibrarySkipPrereleaseCheck() {
        // We intentionally use JS testdata, because the compilers should behave the same way in such a test.
        // To be refactored later, after
        // CompileKotlinAgainstCustomBinariesTest.testReleaseCompilerAgainstPreReleaseLibraryJsSkipPrereleaseCheck is fixed.
        val rootDir =
            File("compiler/testData/compileKotlinAgainstCustomBinaries/releaseCompilerAgainstPreReleaseLibraryJsSkipPrereleaseCheck")

        doTestPreReleaseKotlinLibrary(rootDir, listOf("-Xskip-prerelease-check"))
    }

    private fun doTestPreReleaseKotlinLibrary(rootDir: File, additionalOptions: List<String>) {
        val someNonStableVersion = LanguageVersion.values().firstOrNull { it > LanguageVersion.LATEST_STABLE } ?: return

        val libraryOptions = listOf(
            "-language-version", someNonStableVersion.versionString,
            // Suppress the "language version X is experimental..." warning.
            "-Xsuppress-version-warnings"
        )
        val library = compileLibrary(
            settings = object : Settings(testRunSettings, listOf(PipelineType.DEFAULT)) {},
            source = rootDir.resolve("library"),
            freeCompilerArgs = libraryOptions,
            dependencies = emptyList()
        ).assertSuccess().resultingArtifact

        val pipelineType: PipelineType = testRunSettings.get()

        val compilationResult = compileLibrary(
            testRunSettings,
            source = rootDir.resolve("source.kt"),
            freeCompilerArgs = additionalOptions + pipelineType.compilerFlags,
            dependencies = listOf(library)
        )

        val goldenData = when (pipelineType) {
            PipelineType.K2 -> rootDir.resolve("output.fir.txt").takeIf { it.exists() } ?: rootDir.resolve("output.txt")
            PipelineType.K1 -> rootDir.resolve("output.txt")
            PipelineType.DEFAULT -> rootDir.resolve("output.fir.txt").takeIf { it.exists() && LanguageVersion.LATEST_STABLE.usesK2 } ?: rootDir.resolve("output.txt")
        }

        KotlinTestUtils.assertEqualsToFile(goldenData, compilationResult.toOutput())
    }

    @Test
    fun testObjCExportDiagnostics() {
        val rootDir = File("native/native.tests/testData/compilerOutput/ObjCExportDiagnostics")
        val compilationResult = doBuildObjCFrameworkWithNameCollisions(rootDir, listOf("-Xbinary=objcExportReportNameCollisions=true"))
        val goldenData = rootDir.resolve("output.txt")

        KotlinTestUtils.assertEqualsToFile(goldenData, compilationResult.toOutput())
    }

    @Test
    fun testObjCExportDiagnosticsErrors() {
        val rootDir = File("native/native.tests/testData/compilerOutput/ObjCExportDiagnostics")
        val compilationResult = doBuildObjCFrameworkWithNameCollisions(rootDir, listOf("-Xbinary=objcExportErrorOnNameCollisions=true"))
        assertIs<TestCompilationResult.Failure>(compilationResult)
        val goldenData = rootDir.resolve("error.txt")

        KotlinTestUtils.assertEqualsToFile(goldenData, compilationResult.toOutput())
    }

    @Test
    fun testLoggingWarningWithDistCache() {
        val rootDir = File("native/native.tests/testData/compilerOutput/runtimeLogging")
        val testCase = generateTestCaseWithSingleFile(
            rootDir.resolve("main.kt"),
            freeCompilerArgs = TestCompilerArgs("-Xruntime-logs=gc=info"),
            extras = TestCase.NoTestRunnerExtras("main"),
            testKind = TestKind.STANDALONE_NO_TR,
        )
        val expectedArtifact = TestCompilationArtifact.Executable(buildDir.resolve("logging_warning_with_cache"))
        val compilation = ExecutableCompilation(
            testRunSettings,
            freeCompilerArgs = testCase.freeCompilerArgs,
            sourceModules = testCase.modules,
            extras = testCase.extras,
            dependencies = emptyList(),
            expectedArtifact = expectedArtifact,
        )
        val compilationResult = compilation.result
        val goldenData = rootDir.resolve(
            if (testRunSettings.get<CacheMode>().useStaticCacheForDistributionLibraries) "logging_cache_warning.txt" else "empty.txt"
        )

        KotlinTestUtils.assertEqualsToFile(goldenData, compilationResult.toOutput())
    }

    @Test
    fun testLoggingInvalid() {
        Assumptions.assumeFalse(testRunSettings.get<CacheMode>().useStaticCacheForDistributionLibraries)
        val rootDir = File("native/native.tests/testData/compilerOutput/runtimeLogging")
        val testCase = generateTestCaseWithSingleFile(
            rootDir.resolve("main.kt"),
            freeCompilerArgs = TestCompilerArgs("-Xruntime-logs=invalid=unknown,logging=debug"),
            extras = TestCase.NoTestRunnerExtras("main"),
            testKind = TestKind.STANDALONE_NO_TR,
        )
        val expectedArtifact = TestCompilationArtifact.Executable(buildDir.resolve("logging_invalid"))
        val compilation = ExecutableCompilation(
            testRunSettings,
            freeCompilerArgs = testCase.freeCompilerArgs,
            sourceModules = testCase.modules,
            extras = testCase.extras,
            dependencies = emptyList(),
            expectedArtifact = expectedArtifact,
        )
        val compilationResult = compilation.result
        val goldenData = rootDir.resolve("logging_invalid_error.txt")

        KotlinTestUtils.assertEqualsToFile(goldenData, compilationResult.toOutput())
    }

    private fun doBuildObjCFrameworkWithNameCollisions(rootDir: File, additionalOptions: List<String>): TestCompilationResult<out TestCompilationArtifact.ObjCFramework> {
        Assumptions.assumeTrue(targets.hostTarget.family.isAppleFamily)

        val settings = testRunSettings
        val lib1 = compileLibrary(settings, rootDir.resolve("lib1.kt")).assertSuccess().resultingArtifact
        val lib2 = compileLibrary(settings, rootDir.resolve("lib2.kt")).assertSuccess().resultingArtifact

        val freeCompilerArgs = TestCompilerArgs(
            listOf(
                "-Xinclude=${lib1.path}",
                "-Xinclude=${lib2.path}"
            ) + additionalOptions
        )
        val expectedArtifact = TestCompilationArtifact.ObjCFramework(buildDir, "testObjCExportDiagnostics")

        return ObjCFrameworkCompilation(
            settings,
            freeCompilerArgs,
            sourceModules = emptyList(),
            dependencies = emptyList(),
            expectedArtifact
        ).result
    }
}

@Suppress("JUnitTestCaseWithNoTests")
@TestDataPath("\$PROJECT_ROOT")
@EnforcedProperty(ClassLevelProperty.COMPILER_OUTPUT_INTERCEPTOR, "NONE")
class ClassicCompilerOutputTest : CompilerOutputTestBase()

@Suppress("JUnitTestCaseWithNoTests")
@FirPipeline
@Tag("frontend-fir")
@TestDataPath("\$PROJECT_ROOT")
@EnforcedProperty(ClassLevelProperty.COMPILER_OUTPUT_INTERCEPTOR, "NONE")
class FirCompilerOutputTest : CompilerOutputTestBase()

internal fun TestCompilationResult<*>.toOutput(): String {
    check(this is TestCompilationResult.ImmediateResult<*>) { this }
    val loggedData = this.loggedData

    // Debug output for KT-64822 investigation
    println("Compiler logged data:\n$loggedData")

    check(loggedData is LoggedData.CompilationToolCall) { loggedData::class }
    return normalizeOutput(loggedData.toolOutput, loggedData.exitCode)
}

private fun normalizeOutput(output: String, exitCode: ExitCode): String {
    val dir = "compiler/testData/compileKotlinAgainstCustomBinaries/"
    return AbstractCliTest.getNormalizedCompilerOutput(
        output,
        exitCode,
        dir,
        dir
    )
}

internal fun AbstractNativeSimpleTest.compileLibrary(
    settings: Settings,
    source: File,
    freeCompilerArgs: List<String> = emptyList(),
    dependencies: List<TestCompilationArtifact.KLIB> = emptyList(),
    packed: Boolean = true,
): TestCompilationResult<out TestCompilationArtifact.KLIB> {
    val testCompilerArgs = if (packed) TestCompilerArgs(freeCompilerArgs) else TestCompilerArgs(freeCompilerArgs + "-nopack")
    val testCase = generateTestCaseWithSingleModule(source, testCompilerArgs)
    val compilation = LibraryCompilation(
        settings = settings,
        freeCompilerArgs = testCase.freeCompilerArgs,
        sourceModules = testCase.modules,
        dependencies = dependencies.map { it.asLibraryDependency() },
        expectedArtifact = getLibraryArtifact(testCase, buildDir, packed)
    )
    return compilation.result
}
