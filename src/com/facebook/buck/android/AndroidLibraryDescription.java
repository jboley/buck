/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.CalculateAbiFromClasses;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.util.DependencyMode;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidLibraryDescription.AbstractAndroidLibraryDescriptionArg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("android_library");

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

  public enum JvmLanguage {
    JAVA,
    KOTLIN,
    SCALA,
  }

  private final JavaBuckConfig javaBuckConfig;
  private final JavacOptions defaultOptions;
  private final AndroidLibraryCompilerFactory compilerFactory;

  public AndroidLibraryDescription(
      JavaBuckConfig javaBuckConfig,
      JavacOptions defaultOptions,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.defaultOptions = defaultOptions;
    this.compilerFactory = compilerFactory;
  }

  @Override
  public Class<AndroidLibraryDescriptionArg> getConstructorArgType() {
    return AndroidLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    if (params.getBuildTarget().getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, args.getSrcs(), args.getMavenCoords());
    }

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    boolean hasDummyRDotJavaFlavor =
        params.getBuildTarget().getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    if (HasJavaAbi.isClassAbiTarget(params.getBuildTarget())) {
      Preconditions.checkArgument(!hasDummyRDotJavaFlavor);
      BuildTarget libraryTarget = HasJavaAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule libraryRule = resolver.requireRule(libraryTarget);
      return CalculateAbiFromClasses.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()));
    }

    JavacOptions javacOptions = JavacOptionsFactory.create(defaultOptions, params, resolver, args);

    final Supplier<ImmutableList<BuildRule>> queriedDepsSupplier =
        args.getDepsQuery().isPresent()
            ? Suppliers.memoize(
                () ->
                    QueryUtils.resolveDepQuery(
                            params.getBuildTarget(),
                            args.getDepsQuery().get(),
                            resolver,
                            cellRoots,
                            targetGraph,
                            args.getDeps())
                        .collect(MoreCollectors.toImmutableList()))
            : ImmutableList::of;

    final Supplier<ImmutableList<BuildRule>> exportedDepsSupplier =
        Suppliers.memoize(
            () ->
                resolver
                    .getAllRulesStream(args.getExportedDeps())
                    .collect(MoreCollectors.toImmutableList()));

    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            params.getBuildTarget(),
            params.copyReplacingExtraDeps(
                () ->
                    ImmutableSortedSet.copyOf(
                        Iterables.concat(queriedDepsSupplier.get(), exportedDepsSupplier.get()))),
            JavacFactory.create(ruleFinder, javaBuckConfig, args),
            javacOptions,
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            args.getResourceUnionPackage(),
            args.getFinalRName(),
            false);
    Optional<DummyRDotJava> dummyRDotJava =
        graphEnhancer.getBuildableForAndroidResources(
            resolver, /* createBuildableIfEmpty */ hasDummyRDotJavaFlavor);

    if (hasDummyRDotJavaFlavor) {
      return dummyRDotJava.get();
    } else {
      ImmutableSortedSet<BuildRule> declaredDeps =
          RichStream.fromSupplierOfIterable(params.getDeclaredDeps())
              .concat(RichStream.from(dummyRDotJava))
              .concat(RichStream.fromSupplierOfIterable(queriedDepsSupplier))
              .toImmutableSortedSet(Ordering.natural());

      BuildRuleParams androidLibraryParams =
          params.copyReplacingDeclaredAndExtraDeps(
              Suppliers.ofInstance(declaredDeps), params.getExtraDeps());

      ImmutableSortedSet.Builder<BuildTarget> providedDepsTargetsBuilder =
          ImmutableSortedSet.<BuildTarget>naturalOrder().addAll(args.getProvidedDeps());
      if (args.getProvidedDepsQuery().isPresent()) {
        QueryUtils.resolveDepQuery(
                params.getBuildTarget(),
                args.getProvidedDepsQuery().get(),
                resolver,
                cellRoots,
                targetGraph,
                args.getProvidedDeps())
            .map(BuildRule::getBuildTarget)
            .forEach(providedDepsTargetsBuilder::add);
      }

      return AndroidLibrary.builder(
              targetGraph,
              androidLibraryParams,
              resolver,
              cellRoots,
              javaBuckConfig,
              javacOptions,
              args,
              compilerFactory)
          .setArgs(args)
          .setJavacOptions(javacOptions)
          .setProvidedDeps(providedDepsTargetsBuilder.build())
          .setTests(args.getTests())
          .build();
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty()
        || flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR))
        || flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAndroidLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    compilerFactory
        .getCompiler(constructorArg.getLanguage().orElse(JvmLanguage.JAVA))
        .findDepsForTargetFromConstructorArgs(
            buildTarget, cellRoots, constructorArg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    Optional<SourcePath> getManifest();

    Optional<String> getResourceUnionPackage();

    Optional<String> getFinalRName();

    Optional<JvmLanguage> getLanguage();

    Optional<Query> getDepsQuery();

    Optional<Query> getProvidedDepsQuery();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidLibraryDescriptionArg extends CoreArg {}
}
