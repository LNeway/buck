/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.MergeAndroidResourceSources;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParamsUtils;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.query.Query;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Description for a {@link BuildRule} that generates an {@code .aar} file.
 *
 * <p>This represents an Android Library Project packaged as an {@code .aar} bundle as specified by:
 * <a> https://developer.android.com/studio/projects/android-library#aar-contents</a>.
 *
 * <p>Note that the {@code aar} may be specified as a {@link SourcePath}, so it could be either a
 * binary {@code .aar} file checked into version control, or a zip file that conforms to the {@code
 * .aar} specification that is generated by another build rule.
 */
public class AndroidAarDescription
    implements DescriptionWithTargetGraph<AndroidAarDescriptionArg>,
        ImplicitDepsInferringDescription<AndroidAarDescriptionArg> {

  private static final Flavor AAR_ANDROID_MANIFEST_FLAVOR =
      InternalFlavor.of("aar_android_manifest");
  private static final Flavor AAR_ASSEMBLE_RESOURCE_FLAVOR =
      InternalFlavor.of("aar_assemble_resource");
  private static final Flavor AAR_ASSEMBLE_ASSETS_FLAVOR = InternalFlavor.of("aar_assemble_assets");
  private static final Flavor AAR_ANDROID_RESOURCE_FLAVOR =
      InternalFlavor.of("aar_android_resource");

  private final AndroidManifestFactory androidManifestFactory;
  private final CxxBuckConfig cxxBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final BuildBuckConfig buildBuckConfig;
  private final ToolchainProvider toolchainProvider;
  private final JavacFactory javacFactory;

  public AndroidAarDescription(
      AndroidManifestFactory androidManifestFactory,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      BuildBuckConfig buildBuckConfig,
      ToolchainProvider toolchainProvider) {
    this.androidManifestFactory = androidManifestFactory;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.buildBuckConfig = buildBuckConfig;
    this.toolchainProvider = toolchainProvider;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public Class<AndroidAarDescriptionArg> getConstructorArgType() {
    return AndroidAarDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams originalBuildRuleParams,
      AndroidAarDescriptionArg args) {

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    buildTarget.assertUnflavored();
    ImmutableSortedSet.Builder<BuildRule> aarExtraDepsBuilder =
        new ImmutableSortedSet.Builder<BuildRule>(Ordering.natural())
            .addAll(originalBuildRuleParams.getExtraDeps().get());

    /* android_manifest */
    BuildTarget androidManifestTarget =
        buildTarget.withAppendedFlavors(AAR_ANDROID_MANIFEST_FLAVOR);

    AndroidManifest manifest =
        androidManifestFactory.createBuildRule(
            androidManifestTarget,
            projectFilesystem,
            graphBuilder,
            args.getDeps(),
            args.getManifestSkeleton());
    aarExtraDepsBuilder.add(graphBuilder.addToIndex(manifest));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(context.getTargetGraph(), buildTarget);

    /* assemble dirs */
    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            buildTarget, /* buildTargetsToExcludeFromDex */
            ImmutableSet.of(),
            apkModuleGraph,
            Suppliers.memoize(
                () -> {
                  NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
                      toolchainProvider.getByName(
                          NdkCxxPlatformsProvider.DEFAULT_NAME,
                          buildTarget.getTargetConfiguration(),
                          NdkCxxPlatformsProvider.class);
                  return ndkCxxPlatformsProvider.getResolvedNdkCxxPlatforms(graphBuilder).values();
                }));
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(originalBuildRuleParams.getBuildDeps()),
        graphBuilder);
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableCollection<SourcePath> assetsDirectories =
        packageableCollection.getAssetsDirectories().values();
    AssembleDirectories assembleAssetsDirectories =
        new AssembleDirectories(
            buildTarget.withAppendedFlavors(AAR_ASSEMBLE_ASSETS_FLAVOR),
            projectFilesystem,
            graphBuilder,
            assetsDirectories);
    aarExtraDepsBuilder.add(graphBuilder.addToIndex(assembleAssetsDirectories));

    ImmutableCollection<SourcePath> resDirectories =
        packageableCollection.getResourceDetails().values().stream()
            .flatMap(resourceDetails -> resourceDetails.getResourceDirectories().stream())
            .collect(ImmutableList.toImmutableList());
    MergeAndroidResourceSources assembleResourceDirectories =
        new MergeAndroidResourceSources(
            buildTarget.withAppendedFlavors(AAR_ASSEMBLE_RESOURCE_FLAVOR),
            projectFilesystem,
            graphBuilder,
            resDirectories);
    aarExtraDepsBuilder.add(graphBuilder.addToIndex(assembleResourceDirectories));

    AndroidResource androidResource =
        new AndroidResource(
            buildTarget.withAppendedFlavors(AAR_ANDROID_RESOURCE_FLAVOR),
            projectFilesystem,
            graphBuilder,
            /* deps */ ImmutableSortedSet.<BuildRule>naturalOrder()
                .add(assembleAssetsDirectories)
                .add(assembleResourceDirectories)
                .addAll(originalBuildRuleParams.getDeclaredDeps().get())
                .build(),
            assembleResourceDirectories.getSourcePathToOutput(),
            /* resSrcs */ ImmutableSortedMap.of(),
            /* rDotJavaPackage */ null,
            assembleAssetsDirectories.getSourcePathToOutput(),
            /* assetsSrcs */ ImmutableSortedMap.of(),
            manifest.getSourcePathToOutput(),
            /* hasWhitelistedStrings */ false,
            buildBuckConfig.areExternalActionsEnabled());
    aarExtraDepsBuilder.add(graphBuilder.addToIndex(androidResource));

    ImmutableSortedSet.Builder<SourcePath> classpathToIncludeInAar =
        ImmutableSortedSet.naturalOrder();
    classpathToIncludeInAar.addAll(packageableCollection.getClasspathEntriesToDex());
    aarExtraDepsBuilder.addAll(
        BuildRules.toBuildRulesFor(
            buildTarget, graphBuilder, packageableCollection.getJavaLibrariesToDex()));

    if (!args.getBuildConfigValues().getNameToField().isEmpty()
        && !args.getIncludeBuildConfigClass()) {
      throw new HumanReadableException(
          "Rule %s has build_config_values set but does not set "
              + "include_build_config_class to True. Either indicate you want to include the "
              + "BuildConfig class in the final .aar or do not specify build config values.",
          buildTarget);
    }
    if (args.getIncludeBuildConfigClass()) {
      ImmutableSortedSet<JavaLibrary> buildConfigRules =
          AndroidBinaryGraphEnhancer.addBuildConfigDeps(
              buildTarget,
              projectFilesystem,
              PackageType.RELEASE,
              EnumSet.noneOf(ExopackageMode.class),
              args.getBuildConfigValues(),
              Optional.empty(),
              graphBuilder,
              javacFactory.create(graphBuilder, args, buildTarget.getTargetConfiguration()),
              toolchainProvider
                  .getByName(
                      JavacOptionsProvider.DEFAULT_NAME,
                      buildTarget.getTargetConfiguration(),
                      JavacOptionsProvider.class)
                  .getJavacOptions(),
              packageableCollection,
              downwardApiConfig.isEnabledForAndroid(),
              javaBuckConfig
                  .getDelegate()
                  .getView(BuildBuckConfig.class)
                  .areExternalActionsEnabled(),
              JavaCDParamsUtils.getJavaCDParams(javaBuckConfig, javaCDBuckConfig));
      buildConfigRules.forEach(graphBuilder::addToIndex);
      aarExtraDepsBuilder.addAll(buildConfigRules);
      classpathToIncludeInAar.addAll(
          buildConfigRules.stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(Collectors.toList()));
    }

    /* native_libraries */
    AndroidNativeLibsPackageableGraphEnhancer packageableGraphEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            toolchainProvider,
            context.getCellPathResolver(),
            graphBuilder,
            buildTarget,
            projectFilesystem,
            ImmutableSet.of(),
            cxxBuckConfig,
            downwardApiConfig,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            args.isEnableRelinker() ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
            args.getRelinkerWhitelist(),
            apkModuleGraph,
            new NoopAndroidNativeTargetConfigurationMatcher());
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> nativeLibrariesOptional =
        packageableGraphEnhancer.enhance(packageableCollection).getCopyNativeLibraries();
    Optional<CopyNativeLibraries> rootModuleCopyNativeLibraries =
        nativeLibrariesOptional.map(
            input -> {
              // there will be only one value for the root module
              CopyNativeLibraries copyNativeLibraries =
                  input.get(apkModuleGraph.getRootAPKModule());
              if (copyNativeLibraries == null) {
                throw new HumanReadableException(
                    "Native libraries are present but not in the root application module.");
              }
              aarExtraDepsBuilder.add(copyNativeLibraries);
              return copyNativeLibraries;
            });
    Optional<SourcePath> assembledNativeLibsDir =
        rootModuleCopyNativeLibraries.map(CopyNativeLibraries::getSourcePathToNativeLibsDir);
    Optional<SourcePath> assembledNativeLibsAssetsDir =
        rootModuleCopyNativeLibraries.map(CopyNativeLibraries::getSourcePathToNativeLibsAssetsDir);
    BuildRuleParams androidAarParams =
        originalBuildRuleParams.withExtraDeps(aarExtraDepsBuilder.build());
    return new AndroidAar(
        buildTarget,
        projectFilesystem,
        androidAarParams,
        manifest,
        androidResource,
        assembleResourceDirectories.getSourcePathToOutput(),
        assembleAssetsDirectories.getSourcePathToOutput(),
        assembledNativeLibsDir,
        assembledNativeLibsAssetsDir,
        args.getRemoveClasses(),
        classpathToIncludeInAar.build());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AndroidAarDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, null, buildTarget.getTargetConfiguration());
  }

  // TODO: Don't inherit from AndroidLibraryDescription if most args are ignored
  @RuleArg
  interface AbstractAndroidAarDescriptionArg extends AndroidLibraryDescription.CoreArg {

    SourcePath getManifestSkeleton();

    @Value.Default
    default BuildConfigFields getBuildConfigValues() {
      return BuildConfigFields.of();
    }

    @Value.Default
    default Boolean getIncludeBuildConfigClass() {
      return false;
    }

    @Value.Default
    default boolean isEnableRelinker() {
      return false;
    }

    ImmutableList<Pattern> getRelinkerWhitelist();

    @Override
    default AndroidAarDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (AndroidAarDescriptionArg) this;
      }
      return AndroidAarDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }

    @Override
    default AndroidAarDescriptionArg withProvidedDepsQuery(Query query) {
      if (getProvidedDepsQuery().equals(Optional.of(query))) {
        return (AndroidAarDescriptionArg) this;
      }
      return AndroidAarDescriptionArg.builder().from(this).setProvidedDepsQuery(query).build();
    }
  }
}
