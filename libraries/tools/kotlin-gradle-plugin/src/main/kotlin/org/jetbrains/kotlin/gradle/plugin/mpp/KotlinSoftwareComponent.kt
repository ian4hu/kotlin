/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Usage
import org.gradle.api.capabilities.Capability
import org.gradle.api.component.ComponentWithVariants
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.component.UsageContext
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.utils.ifEmpty

class KotlinSoftwareComponent(
    private val name: String,
    private val kotlinTargets: Iterable<KotlinTarget>
) : SoftwareComponentInternal, ComponentWithVariants {

    override fun getUsages(): Set<UsageContext> = emptySet()

    override fun getVariants(): Set<KotlinTargetComponent> =
        kotlinTargets.flatMap { it.components }.toSet()

    override fun getName(): String = name
}

// At the moment all KN artifacts have JAVA_API usage.
// TODO: Replace it with a specific usage
object NativeUsage {
    const val KOTLIN_KLIB = "kotlin-klib"
}

interface KotlinUsageContext : UsageContext {
    val compilation: KotlinCompilation<*>
    val dependencyConfigurationName: String
}

class DefaultKotlinUsageContext(
    override val compilation: KotlinCompilation<*>,
    private val usage: Usage,
    override val dependencyConfigurationName: String,
    private val publishWithGradleMetadata: Boolean,
    val sourcesArtifact: PublishArtifact? = null,
    private val overrideConfigurationArtifacts: Set<PublishArtifact>? = null
) : KotlinUsageContext {

    private val kotlinTarget: KotlinTarget get() = compilation.target
    private val project: Project get() = kotlinTarget.project

    override fun getUsage(): Usage = usage

    override fun getName(): String = kotlinTarget.targetName + when (dependencyConfigurationName) {
        kotlinTarget.apiElementsConfigurationName -> "-api"
        kotlinTarget.runtimeElementsConfigurationName -> "-runtime"
        else -> "-$dependencyConfigurationName" // for Android variants
    }

    private val configuration: Configuration
        get() = project.configurations.getByName(dependencyConfigurationName)

    override fun getDependencies(): MutableSet<out ModuleDependency> =
        if (publishWithGradleMetadata)
            configuration.incoming.dependencies.withType(ModuleDependency::class.java)
        else
            rewriteMppDependenciesToTargetModuleDependencies(this, configuration).toMutableSet()

    override fun getDependencyConstraints(): MutableSet<out DependencyConstraint> =
        configuration.incoming.dependencyConstraints

    override fun getArtifacts(): Set<PublishArtifact> =
        (overrideConfigurationArtifacts ?:
        // TODO Gradle Java plugin does that in a different way; check whether we can improve this
        configuration.artifacts)
            .plus(listOfNotNull(sourcesArtifact))

    override fun getAttributes(): AttributeContainer =
        HierarchyAttributeContainer(configuration.outgoing.attributes) { it != ProjectLocalConfigurations.ATTRIBUTE }

    override fun getCapabilities(): Set<Capability> = emptySet()

    override fun getGlobalExcludes(): Set<ExcludeRule> = emptySet()
}

private fun rewriteMppDependenciesToTargetModuleDependencies(
    usageContext: KotlinUsageContext,
    fromConfiguration: Configuration
): Set<ModuleDependency> = with(usageContext.compilation.target.project) {
    val compilation = usageContext.compilation
    val moduleDependencies = fromConfiguration.incoming.dependencies.withType(ModuleDependency::class.java).ifEmpty { return emptySet() }

    val targetCompileDependenciesConfiguration = project.configurations.getByName(
        when (compilation) {
            is KotlinJvmAndroidCompilation -> {
                // TODO handle Android configuration names in a general way once we drop AGP < 3.0.0
                val variantName = compilation.name
                when (usageContext.usage.name) {
                    Usage.JAVA_API -> variantName + "CompileClasspath"
                    Usage.JAVA_RUNTIME_JARS -> variantName + "RuntimeClasspath"
                    else -> error("Unexpected Usage for usage context: ${usageContext.usage}")
                }
            }
            else -> when (usageContext.usage.name) {
                Usage.JAVA_API -> compilation.compileDependencyConfigurationName
                Usage.JAVA_RUNTIME_JARS -> (compilation as KotlinCompilationToRunnableFiles).runtimeDependencyConfigurationName
                else -> error("Unexpected Usage for usage context: ${usageContext.usage}")
            }
        }
    )

    val resolvedCompileDependencies by lazy {
        // don't resolve if no project dependencies on MPP projects are found
        targetCompileDependenciesConfiguration.resolvedConfiguration.lenientConfiguration.allModuleDependencies.associateBy {
            Triple(it.moduleGroup, it.moduleName, it.moduleVersion)
        }
    }

    moduleDependencies.map { dependency ->
        when (dependency) {
            !is ProjectDependency -> dependency
            else -> {
                val dependencyProject = dependency.dependencyProject
                val dependencyProjectKotlinExtension = dependencyProject.multiplatformExtension
                    ?: return@map dependency

                if (dependencyProjectKotlinExtension.isGradleMetadataAvailable)
                    return@map dependency

                val resolved = resolvedCompileDependencies[Triple(dependency.group, dependency.name, dependency.version)]
                    ?: return@map dependency

                val resolvedToConfiguration = resolved.configuration

                val dependencyTargetComponent: KotlinTargetComponent = run {
                    dependencyProjectKotlinExtension.targets.forEach { target ->
                        target.components.forEach { component ->
                            if (component.findUsageContext(resolvedToConfiguration) != null)
                                return@run component
                        }
                    }
                    // Failed to find a matching component:
                    return@map dependency
                }

                val publicationDelegate = (dependencyTargetComponent as? KotlinTargetComponentWithPublication)?.publicationDelegate

                dependencies.module(
                    listOf(
                        publicationDelegate?.groupId ?: dependency.group,
                        publicationDelegate?.artifactId ?: dependencyTargetComponent.defaultArtifactId,
                        publicationDelegate?.version ?: dependency.version
                    ).joinToString(":")
                ) as ModuleDependency
            }
        }
    }.toSet()
}

internal fun KotlinTargetComponent.findUsageContext(configurationName: String): UsageContext? {
    val usageContexts = when (this) {
        is KotlinVariantWithMetadataDependency -> originalUsages
        is SoftwareComponentInternal -> usages
        else -> emptySet()
    }
    return usageContexts.find { usageContext ->
        if (usageContext !is KotlinUsageContext) return@find false
        val compilation = usageContext.compilation
        configurationName in compilation.relatedConfigurationNames ||
                configurationName == compilation.target.apiElementsConfigurationName ||
                configurationName == compilation.target.runtimeElementsConfigurationName ||
                configurationName == compilation.target.defaultConfigurationName
    }
}