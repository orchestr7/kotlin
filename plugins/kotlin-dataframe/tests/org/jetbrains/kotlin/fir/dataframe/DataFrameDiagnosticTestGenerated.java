/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dataframe;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link GenerateNewCompilerTests.kt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/kotlin-dataframe/testData/diagnostics")
@TestDataPath("$PROJECT_ROOT")
public class DataFrameDiagnosticTestGenerated extends AbstractDataFrameDiagnosticTest {
    @Test
    @TestMetadata("A.kt")
    public void testA() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/A.kt");
    }

    @Test
    public void testAllFilesPresentInDiagnostics() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/kotlin-dataframe/testData/diagnostics"), Pattern.compile("^(.+)\\.kt$"), null, true);
    }

    @Test
    @TestMetadata("helloWorld.kt")
    public void testHelloWorld() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/helloWorld.kt");
    }

    @Test
    @TestMetadata("injectAccessorMultiplexOperation.kt")
    public void testInjectAccessorMultiplexOperation() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/injectAccessorMultiplexOperation.kt");
    }

    @Test
    @TestMetadata("injectAccessors.kt")
    public void testInjectAccessors() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/injectAccessors.kt");
    }

    @Test
    @TestMetadata("injectAccessorsDsl.kt")
    public void testInjectAccessorsDsl() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/injectAccessorsDsl.kt");
    }

    @Test
    @TestMetadata("OuterClass.kt")
    public void testOuterClass() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/OuterClass.kt");
    }

    @Test
    @TestMetadata("processDslWithExtensionFun.kt")
    public void testProcessDslWithExtensionFun() throws Exception {
        runTest("plugins/kotlin-dataframe/testData/diagnostics/processDslWithExtensionFun.kt");
    }
}
