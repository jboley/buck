/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidLibraryDescription.JvmLanguage;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

public class AndroidLibrary extends DefaultJavaLibrary implements AndroidPackageable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  @AddToRuleKey private final Optional<SourcePath> manifestFile;

  public static Builder builder(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      JavaBuckConfig javaBuckConfig,
      JavacOptions javacOptions,
      AndroidLibraryDescription.CoreArg args,
      AndroidLibraryCompilerFactory compilerFactory) {
    return new Builder(
        targetGraph,
        params,
        buildRuleResolver,
        cellRoots,
        javaBuckConfig,
        javacOptions,
        args,
        compilerFactory);
  }

  @VisibleForTesting
  AndroidLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> fullJarDeclaredDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      ImmutableSortedSet<SourcePath> abiInputs,
      BuildTarget abiJar,
      JavacOptions javacOptions,
      boolean trackClassUsage,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords,
      Optional<SourcePath> manifestFile,
      ImmutableSortedSet<BuildTarget> tests) {
    super(
        params,
        resolver,
        ruleFinder,
        srcs,
        resources,
        javacOptions.getGeneratedSourceFolderName(),
        proguardConfig,
        postprocessClassesCommands,
        fullJarDeclaredDeps,
        fullJarExportedDeps,
        fullJarProvidedDeps,
        compileTimeClasspathSourcePaths,
        abiInputs,
        abiJar,
        trackClassUsage,
        compileStepFactory,
        resourcesRoot,
        Optional.empty(),
        mavenCoords,
        tests,
        javacOptions.getClassesToRemoveFromJar());
    this.manifestFile = manifestFile;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public Optional<SourcePath> getManifestFile() {
    return manifestFile;
  }

  public static class Builder extends DefaultJavaLibraryBuilder {
    private final AndroidLibraryDescription.CoreArg args;
    private final AndroidLibraryCompilerFactory compilerFactory;

    private AndroidLibraryDescription.JvmLanguage language =
        AndroidLibraryDescription.JvmLanguage.JAVA;
    private Optional<SourcePath> androidManifest = Optional.empty();

    protected Builder(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver buildRuleResolver,
        CellPathResolver cellRoots,
        JavaBuckConfig javaBuckConfig,
        JavacOptions javacOptions,
        AndroidLibraryDescription.CoreArg args,
        AndroidLibraryCompilerFactory compilerFactory) {
      super(targetGraph, params, buildRuleResolver, cellRoots, javaBuckConfig);
      this.args = args;
      this.compilerFactory = compilerFactory;
      setJavacOptions(javacOptions);
      setArgs(args);
      // Set only if this is not Scala/Kotlin
      if (!args.getLanguage().filter(l -> l != JvmLanguage.JAVA).isPresent()) {
        setCompileAgainstAbis(javaBuckConfig.shouldCompileAgainstAbis());
      }
    }

    @Override
    public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.CoreArg args) {
      super.setArgs(args);
      AndroidLibraryDescription.CoreArg androidArgs = (AndroidLibraryDescription.CoreArg) args;
      language = androidArgs.getLanguage().orElse(AndroidLibraryDescription.JvmLanguage.JAVA);
      return setManifestFile(androidArgs.getManifest());
    }

    @Override
    public DefaultJavaLibraryBuilder setManifestFile(Optional<SourcePath> manifestFile) {
      androidManifest = manifestFile;
      return this;
    }

    @Override
    protected BuilderHelper newHelper() {
      return new BuilderHelper();
    }

    protected class BuilderHelper extends DefaultJavaLibraryBuilder.BuilderHelper {
      @Nullable private AndroidLibraryCompiler androidCompiler;

      @Override
      protected DefaultJavaLibrary build() throws NoSuchBuildTargetException {
        return new AndroidLibrary(
            getFinalParams(),
            sourcePathResolver,
            ruleFinder,
            srcs,
            resources,
            proguardConfig,
            postprocessClassesCommands,
            getFinalFullJarDeclaredDeps(),
            fullJarExportedDeps,
            fullJarProvidedDeps,
            getFinalCompileTimeClasspathSourcePaths(),
            getAbiInputs(),
            getAbiJar(),
            Preconditions.checkNotNull(javacOptions),
            getAndroidCompiler().trackClassUsage(Preconditions.checkNotNull(javacOptions)),
            getCompileStepFactory(),
            resourcesRoot,
            mavenCoords,
            androidManifest,
            tests);
      }

      @Override
      protected CompileToJarStepFactory buildCompileStepFactory() {
        return getAndroidCompiler()
            .compileToJar(args, Preconditions.checkNotNull(javacOptions), buildRuleResolver);
      }

      protected AndroidLibraryCompiler getAndroidCompiler() {
        if (androidCompiler == null) {
          androidCompiler = compilerFactory.getCompiler(language);
        }

        return androidCompiler;
      }
    }
  }
}
