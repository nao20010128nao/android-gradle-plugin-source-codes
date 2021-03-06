/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.build.gradle.tasks;

import static com.android.SdkConstants.DOT_JAVA;
import static com.android.SdkConstants.UTF_8;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.internal.LibraryTaskManager;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.AbstractAndroidCompile;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.tasks.annotations.ApiDatabase;
import com.android.build.gradle.tasks.annotations.Extractor;
import com.android.build.gradle.tasks.annotations.TypedefRemover;
import com.android.builder.core.AndroidBuilder;
import com.android.tools.lint.LintCoreApplicationEnvironment;
import com.android.tools.lint.LintCoreProjectEnvironment;
import com.google.common.collect.Lists;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiJavaFile;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.gradle.api.Project;
import org.gradle.api.file.EmptyFileVisitor;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.BuildException;

/**
 * Task which extracts annotations from the source files, and writes them to one of
 * two possible destinations:
 * <ul>
 *     <li> A "external annotations" file (pointed to by {@link ExtractAnnotations#output})
 *          which records the annotations in a zipped XML format for use by the IDE and by
 *          lint to associate the (source retention) annotations back with the compiled code</li>
 *     <li> For any {@code Keep} annotated elements, a Proguard keep file (pointed to by
 *          {@link ExtractAnnotations#proguard}, which lists APIs (classes, methods and fields)
 *          that should not be removed even if no references in code are found to those APIs.</li>
 * </ul>
 * We typically only extract external annotations when building libraries; ProGuard annotations
 * are extracted when building libraries (to record in the AAR), <b>or</b> when building an
 * app module where ProGuarding is enabled.
 */
@ParallelizableTask
public class ExtractAnnotations extends AbstractAndroidCompile {
    private BaseVariantData variant;

    private List<String> bootClasspath;

    private File typedefFile;

    private File output;

    private File proguard;

    private File apiFilter;

    private List<File> mergeJars;

    private String encoding;

    private File classDir;

    private boolean allowErrors = false;

    public BaseVariantData getVariant() {
        return variant;
    }

    public void setVariant(BaseVariantData variant) {
        this.variant = variant;
    }

    /** Boot classpath: typically android.jar */
    @Input
    @Optional
    public List<String> getBootClasspath() {
        return bootClasspath;
    }

    public void setBootClasspath(List<String> bootClasspath) {
        this.bootClasspath = bootClasspath;
    }

    /** The output .zip file to write the annotations database to, if any */
    @Optional
    @OutputFile
    public File getOutput() {
        return output;
    }

    public void setOutput(File output) {
        this.output = output;
    }

    /** The output proguard file to write any @Keep rules into, if any */
    @Optional
    @OutputFile
    public File getProguard() {
        return proguard;
    }

    public void setProguard(File proguard) {
        this.proguard = proguard;
    }

    /**
     * The output .txt file to write the typedef recipe file to. A "recipe" file
     * is a file which describes typedef classes, typically ones that should
     * be deleted. It is generated by this {@link ExtractAnnotations} task and
     * consumed by the {@link TypedefRemover}.
     */
    @Optional
    @OutputFile
    public File getTypedefFile() {
        return typedefFile;
    }

    public void setTypedefFile(File typedefFile) {
        this.typedefFile = typedefFile;
    }

    /**
     * An optional pointer to an API file to filter the annotations by (any annotations
     * not found in the API file are considered hidden/not exposed.) This is in the same
     * format as the api-versions.xml file found in the SDK.
     */
    @Optional
    @InputFile
    public File getApiFilter() {
        return apiFilter;
    }

    public void setApiFilter(File apiFilter) {
        this.apiFilter = apiFilter;
    }

    /**
     * A list of existing annotation zip files (or dirs) to merge in. This can be used to merge in
     * a hardcoded set of annotations that are not present in the source code, such as
     * {@code @Contract} annotations we'd like to record without actually having a dependency
     * on the IDEA annotations library.
     */
    @Optional
    @InputFile
    public List<File> getMergeJars() {
        return mergeJars;
    }

    public void setMergeJars(List<File> mergeJars) {
        this.mergeJars = mergeJars;
    }

    /**
     * The encoding to use when reading source files. The output file will ignore this and
     * will always be a UTF-8 encoded .xml file inside the annotations zip file.
     */
    @Optional
    @Input
    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    /**
     * Location of class files. If set, any non-public typedef source retention annotations
     * will be removed prior to .jar packaging.
     */
    @Optional
    @InputDirectory
    public File getClassDir() {
        return classDir;
    }

    public void setClassDir(File classDir) {
        this.classDir = classDir;
    }

    /** Whether we allow extraction even in the presence of symbol resolution errors */
    @Input
    public boolean isAllowErrors() {
        return allowErrors;
    }

    public void setAllowErrors(boolean allowErrors) {
        this.allowErrors = allowErrors;
    }

    @Override
    @TaskAction
    protected void compile() {
        if (!hasAndroidAnnotations()) {
            return;
        }

        if (encoding == null) {
            encoding = UTF_8;
        }

        LintCoreApplicationEnvironment appEnv = LintCoreApplicationEnvironment.get();
        Disposable parentDisposable = Disposer.newDisposable();

        LintCoreProjectEnvironment projectEnvironment = LintCoreProjectEnvironment.create(
                parentDisposable, appEnv);

        List<PsiJavaFile> parsedUnits = parseSources(projectEnvironment);

        // API definition file
        ApiDatabase database = null;
        if (apiFilter != null && apiFilter.exists()) {
            try {
                database = new ApiDatabase(apiFilter);
            } catch (IOException e) {
                throw new BuildException("Could not open API database " + apiFilter, e);
            }
        }

        boolean displayInfo = getProject().getLogger().isEnabled(LogLevel.INFO);

        Extractor extractor = new Extractor(
                database,
                classDir,
                displayInfo,
                false /*includeClassRetentionAnnotations*/,
                false /*sortAnnotations*/);

        extractor.extractFromProjectSource(parsedUnits);
        if (mergeJars != null) {
            for (File jar : mergeJars) {
                extractor.mergeExisting(jar);
            }
        }
        extractor.export(output, proguard);
        if (typedefFile != null) {
            extractor.writeTypedefFile(typedefFile);
        } else {
            extractor.removeTypedefClasses();
        }

        Disposer.dispose(parentDisposable);
        LintCoreApplicationEnvironment.clearAccessorCache();
    }

    @Input
    public boolean hasAndroidAnnotations() {
        return variant.getVariantDependency().isAnnotationsPresent();
    }

    @NonNull
    private List<PsiJavaFile> parseSources(LintCoreProjectEnvironment projectEnvironment) {
        SourceFileVisitor fileVisitor = new SourceFileVisitor();
        getSource().visit(fileVisitor);
        List<File> sourceFiles = fileVisitor.sourceUnits;
        List<File> sourceRoots = fileVisitor.getSourceRoots();

        if (getClasspath() != null) {
            for (File jar : getClasspath()) {
                sourceRoots.add(jar);
            }
        }
        if (bootClasspath != null) {
            for (String path : bootClasspath) {
                sourceRoots.add(new File(path));
            }
        }

        projectEnvironment.registerPaths(sourceRoots);

        return Extractor.createUnitsForFiles(projectEnvironment.getProject(), sourceFiles);
    }

    public static class ConfigAction implements TaskConfigAction<ExtractAnnotations> {

        @NonNull private Project project;
        @NonNull private AndroidConfig extension;
        @NonNull private VariantScope variantScope;

        public ConfigAction(
                @NonNull Project project,
                @NonNull AndroidConfig extension,
                @NonNull VariantScope variantScope) {
            this.project = project;
            this.extension = extension;
            this.variantScope = variantScope;
        }

        @NonNull
        @Override
        public String getName() {
            return variantScope.getTaskName("extract", "Annotations");
        }

        @NonNull
        @Override
        public Class<ExtractAnnotations> getType() {
            return ExtractAnnotations.class;
        }

        @Override
        public void execute(@NonNull ExtractAnnotations task) {
            final GradleVariantConfiguration variantConfig = variantScope.getVariantConfiguration();
            final AndroidBuilder androidBuilder = variantScope.getGlobalScope().getAndroidBuilder();

            task.setDescription(
                    "Extracts Android annotations for the "
                            + variantConfig.getFullName()
                            + " variant into the archive file");
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setVariant(variantScope.getVariantData());
            task.setDestinationDir(
                    new File(
                            variantScope.getGlobalScope().getIntermediatesDir(),
                            LibraryTaskManager.ANNOTATIONS + "/" + variantConfig.getDirName()));
            task.setOutput(new File(task.getDestinationDir(), SdkConstants.FN_ANNOTATIONS_ZIP));
            task.setTypedefFile(variantScope.getTypedefFile());
            task.setClassDir(variantScope.getJavaOutputDir());
            task.setSource(variantScope.getVariantData().getJavaSources());
            task.setEncoding(extension.getCompileOptions().getEncoding());
            task.setSourceCompatibility(
                    extension.getCompileOptions().getSourceCompatibility().toString());

            task.setClasspath(project.files((Callable<Set<File>>) () ->
                    androidBuilder.getCompileClasspath(variantConfig)));

            // Setup the boot classpath just before the task actually runs since this will
            // force the sdk to be parsed. (Same as in compileTask)
            task.doFirst(
                    aTask -> {
                        if (aTask instanceof ExtractAnnotations) {
                            ExtractAnnotations extractAnnotations = (ExtractAnnotations) aTask;
                            extractAnnotations.setBootClasspath(
                                    androidBuilder.getBootClasspathAsStrings(false));
                        }
                    });

            ((LibraryVariantData) variantScope.getVariantData()).generateAnnotationsTask = task;
        }
    }

    /**
     * Visitor which gathers a series of individual source files as well as inferring the
     * set of source roots
     */
    private static class SourceFileVisitor extends EmptyFileVisitor {
        private final List<File> sourceUnits = Lists.newArrayListWithExpectedSize(100);
        private final List<File> sourceRoots = Lists.newArrayList();

        private String mostRecentRoot = "\000";

        public SourceFileVisitor() {
        }

        public List<File> getSourceFiles() {
            return sourceUnits;
        }

        public List<File> getSourceRoots() {
            return sourceRoots;
        }

        private static final String BUILD_GENERATED = File.separator + "build" + File.separator
                + "generated" + File.separator;

        @Override
        public void visitFile(FileVisitDetails details) {
            File file = details.getFile();
            String path = file.getPath();
            if (path.endsWith(DOT_JAVA) && !path.contains(BUILD_GENERATED)) {
                // Infer the source roots. These are available as relative paths
                // on the file visit details.
                if (!path.startsWith(mostRecentRoot)) {
                    RelativePath relativePath = details.getRelativePath();
                    String pathString = relativePath.getPathString();

                    if (path.endsWith(pathString)) {
                        String root = path.substring(0, path.length() - pathString.length());
                        File rootFile = new File(root);
                        if (!sourceRoots.contains(rootFile)) {
                            mostRecentRoot = rootFile.getPath();
                            sourceRoots.add(rootFile);
                        }
                    }
                }

                sourceUnits.add(file);
            }
        }
    }
}
