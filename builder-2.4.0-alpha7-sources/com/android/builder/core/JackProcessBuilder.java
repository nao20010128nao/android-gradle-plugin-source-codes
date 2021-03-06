/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.builder.core;

import static com.google.common.base.Preconditions.checkNotNull;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.ide.common.process.JavaProcessInfo;
import com.android.ide.common.process.ProcessEnvBuilder;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessInfoBuilder;
import com.android.sdklib.BuildToolInfo;
import com.android.sdklib.BuildToolInfo.JackVersion;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A builder to create a Jack-specific ProcessInfoBuilder
 */
public class JackProcessBuilder extends ProcessEnvBuilder<JackProcessBuilder> {

    @NonNull
    private final JackProcessOptions options;
    @NonNull
    private final ILogger logger;

    public JackProcessBuilder(@NonNull JackProcessOptions options, @NonNull ILogger logger) {
        this.options = options;
        this.logger = logger;
    }

    @NonNull
    public JavaProcessInfo build(@NonNull BuildToolInfo buildToolInfo) throws ProcessException {
        ProcessInfoBuilder builder = new ProcessInfoBuilder();
        builder.addEnvironments(mEnvironment);

        String jackLocation = System.getenv("USE_JACK_LOCATION");
        String jackJar = jackLocation != null
                ? jackLocation + File.separator + SdkConstants.FN_JACK
                : buildToolInfo.getPath(BuildToolInfo.PathId.JACK);
        if (jackJar == null || !new File(jackJar).isFile()) {
            throw new IllegalStateException("Unable to find jack.jar at " + jackJar);
        }

        builder.setClasspath(jackJar);
        builder.setMain("com.android.jack.Main");

        if (options.getJavaMaxHeapSize() != null) {
            builder.addJvmArg("-Xmx" + options.getJavaMaxHeapSize());
        } else {
            builder.addJvmArg("-Xmx1024M");
        }

        builder.addJvmArg("-Dfile.encoding=" + options.getEncoding());
        // due to b.android.com/82031
        builder.addArgs("-D", "jack.dex.optimize=true");

        if (options.isDebugJackInternals()) {
            builder.addJvmArg("-Dcom.android.jack.log=DEBUG");
        }

        if (options.isVerboseProcessing()) {
            builder.addArgs("--verbose", "info");
        }

        builder.addArgs("-D", "jack.reporter=sdk");

        builder.addArgs("-D", "jack.dex.debug.vars=" + options.isDebuggable());

        if (!options.getClassPaths().isEmpty()) {
            builder.addArgs("--classpath", FileUtils.joinFilePaths(options.getClassPaths()));
        }

        for (File lib : options.getImportFiles()) {
            builder.addArgs("--import", lib.getAbsolutePath());
        }

        if (options.getDexOutputDirectory() != null) {
            builder.addArgs("--output-dex", options.getDexOutputDirectory().getAbsolutePath());
        }

        if (options.getJackOutputFile() != null) {
            builder.addArgs("--output-jack", options.getJackOutputFile().getAbsolutePath());
        }

        for (File file : options.getProguardFiles()) {
            builder.addArgs("--config-proguard", file.getAbsolutePath());
        }

        if (options.getMappingFile() != null) {
            builder.addArgs("-D", "jack.obfuscation.mapping.dump=true");
            builder.addArgs("-D", "jack.obfuscation.mapping.dump.file=" + options.getMappingFile().getAbsolutePath());
        }

        if (options.isMultiDex()) {
            builder.addArgs("--multi-dex");
            if (DefaultApiVersion.isLegacyMultidex(options.getMinSdkVersion())) {
                builder.addArgs("legacy");
            } else {
                builder.addArgs("native");
            }
        }

        for (File jarjarRuleFile : options.getJarJarRuleFiles()) {
            builder.addArgs("--config-jarjar", jarjarRuleFile.getAbsolutePath());
        }

        if (options.getSourceCompatibility() != null) {
            builder.addArgs("-D", "jack.java.source.version=" + options.getSourceCompatibility());
        }

        if (options.getIncrementalDir() != null) {
            builder.addArgs("--incremental-folder", options.getIncrementalDir().getAbsolutePath());
        }

        if (!DefaultApiVersion.isPreview(options.getMinSdkVersion())) {
            builder.addArgs(
                    "-D", "jack.android.min-api-level=" + options.getMinSdkVersion().getApiLevel());
        }

        if (!options.getAnnotationProcessorNames().isEmpty()) {
            builder.addArgs("-D", "jack.annotation-processor.manual=true");
            builder.addArgs("-D",
                    "jack.annotation-processor.manual.list="
                            + Joiner.on(',').join(options.getAnnotationProcessorNames()));
        }
        if (!options.getAnnotationProcessorClassPath().isEmpty()) {
            builder.addArgs("-D", "jack.annotation-processor.path=true");
            builder.addArgs("-D",
                    "jack.annotation-processor.path.list="
                            + FileUtils.joinFilePaths(options.getAnnotationProcessorClassPath()));
        }
        if (!options.getAnnotationProcessorOptions().isEmpty()) {
            String processorOptions = options.getAnnotationProcessorOptions().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(","));
            builder.addArgs("-D", "jack.annotation-processor.options=" + processorOptions);
        }
        if (options.getAnnotationProcessorOutputDirectory() != null) {
            FileUtils.mkdirs(options.getAnnotationProcessorOutputDirectory());
            builder.addArgs(
                    "-D",
                    "jack.annotation-processor.source.output="
                            + options.getAnnotationProcessorOutputDirectory().getAbsolutePath());
        }

        if (!options.getInputFiles().isEmpty()) {
            if (options.getEcjOptionFile() != null) {
                try {
                    createEcjOptionFile();
                } catch (IOException e) {
                    throw new ProcessException(
                            "Unable to create " + options.getEcjOptionFile() + ".");
                }
                builder.addArgs("@" + options.getEcjOptionFile().getAbsolutePath());
            } else {
                for (File file : options.getInputFiles()) {
                    builder.addArgs(file.getAbsolutePath());
                }
            }
        }

        JackVersion apiVersion = buildToolInfo.getSupportedJackApi();

        if (apiVersion.getVersion() >= JackVersion.V4.getVersion()) {
            api04Specific(buildToolInfo, builder);
        }

        // apply all additional params
        for (Map.Entry<String, String> param : options.getAdditionalParameters().entrySet()) {
            builder.addArgs("-D", param.getKey() + "=" + param.getValue());
        }

        return builder.createJavaProcess();
    }

    private void api04Specific(
            @NonNull BuildToolInfo buildToolInfo, @NonNull ProcessInfoBuilder builder) {
        List<File> pluginPaths = Lists.newArrayList(options.getJackPluginClassPath());
        List<String> pluginNames = Lists.newArrayList(options.getJackPluginNames());
        if (options.getCoverageMetadataFile() != null) {
            String coveragePluginPath =
                    buildToolInfo.getPath(BuildToolInfo.PathId.JACK_COVERAGE_PLUGIN);
            if (coveragePluginPath == null || !new File(coveragePluginPath).isFile()) {
                logger.warning(
                        "Unable to find coverage plugin '%s'.  Disabling code coverage.",
                        coveragePluginPath);
            } else {
                pluginPaths.add(new File(coveragePluginPath));
                pluginNames.add(JackProcessOptions.COVERAGE_PLUGIN_NAME);
                builder.addArgs(
                        "-D",
                        "jack.coverage.metadata.file="
                                + options.getCoverageMetadataFile().getAbsolutePath());
                builder.addArgs("-D", "jack.coverage=true");
            }
        }

        if (!pluginPaths.isEmpty()) {
            builder.addArgs("--pluginpath", FileUtils.joinFilePaths(pluginPaths));
        }
        if (!pluginNames.isEmpty()) {
            builder.addArgs("--plugin", Joiner.on(",").join(pluginNames));
        }
    }

    private void createEcjOptionFile() throws IOException {
        checkNotNull(options.getEcjOptionFile());

        StringBuilder sb = new StringBuilder();
        for (File sourceFile : options.getInputFiles()) {
            sb.append('\"')
                    .append(FileUtils.toSystemIndependentPath(sourceFile.getAbsolutePath()))
                    .append('\"')
                    .append("\n");
        }

        FileUtils.mkdirs(options.getEcjOptionFile().getParentFile());

        Files.write(sb.toString(), options.getEcjOptionFile(), Charsets.UTF_8);
    }

}
