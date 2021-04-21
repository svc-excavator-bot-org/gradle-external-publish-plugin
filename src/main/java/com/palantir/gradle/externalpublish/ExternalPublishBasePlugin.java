/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.externalpublish;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nebula.plugin.info.scm.ScmInfoPlugin;
import nebula.plugin.publishing.maven.MavenBasePublishPlugin;
import nebula.plugin.publishing.maven.MavenManifestPlugin;
import nebula.plugin.publishing.maven.MavenScmPlugin;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;

final class ExternalPublishBasePlugin implements Plugin<Project> {
    private static final Set<String> PUBLISHING_UPGRADE_EXCAVATOR_BRANCH_NAMES = Collections.unmodifiableSet(
            Stream.of("roomba/external-publish-plugin-migration", "roomba/latest-oss-publishing")
                    .collect(Collectors.toSet()));

    private final Set<String> sonatypePublicationNames = new HashSet<>();

    private Project project;

    @Override
    public void apply(Project projectVal) {
        this.project = projectVal;

        applyPublishingPlugins();
        linkWithRootProject();
        alwaysRunPublishIfOnExcavatorUpgradeBranch();
        disableOtherPublicationsFromPublishingToSonatype();
        disableModuleMetadata();

        // Sonatype requires we set a description on the pom, but the maven plugin will overwrite anything we set on
        // pom object. So we set the description on the project if it is not set, which the maven plugin reads from.
        if (project.getDescription() == null) {
            project.setDescription("Palantir open source project");
        }
    }

    private void applyPublishingPlugins() {
        // Intentionally not applying nebula.maven-publish, but most of its constituent plugins,
        // because we do _not_ want nebula.maven-compile-only
        Stream.of(
                        MavenPublishPlugin.class,
                        MavenBasePublishPlugin.class,
                        MavenManifestPlugin.class,
                        MavenScmPlugin.class,
                        ScmInfoPlugin.class)
                .forEach(plugin -> project.getPluginManager().apply(plugin));
    }

    private void linkWithRootProject() {
        if (project == project.getRootProject()) {
            // Can only do this on the root project as the Nexus plugin uses afterEvaluates which do not get run if
            // the root project has been evaluated, which happens before subproject evaluation
            project.getPluginManager().apply(ExternalPublishRootPlugin.class);
        }

        ExternalPublishRootPlugin rootPlugin = Optional.ofNullable(
                        project.getRootProject().getPlugins().findPlugin(ExternalPublishRootPlugin.class))
                .orElseThrow(() -> new GradleException(
                        "The com.palantir.external-publish plugin must be applied to the root project "
                                + "*before* this plugin is evaluated"));

        project.getTasks().named("publish").configure(publish -> {
            publish.dependsOn(rootPlugin.sonatypeFinishingTask());
        });
    }

    private void disableOtherPublicationsFromPublishingToSonatype() {
        project.getTasks().withType(PublishToMavenRepository.class).configureEach(publishTask -> {
            publishTask.onlyIf(_ignored -> {
                if (publishTask.getRepository().getName().equals("sonatype")) {
                    return sonatypePublicationNames.contains(
                            publishTask.getPublication().getName());
                }

                return true;
            });
        });
    }

    private void alwaysRunPublishIfOnExcavatorUpgradeBranch() {
        boolean isPublishingExcavatorBranch = EnvironmentVariables.envVarOrFromTestingProperty(project, "CIRCLE_BRANCH")
                .filter(name -> PUBLISHING_UPGRADE_EXCAVATOR_BRANCH_NAMES.contains(name))
                .isPresent();

        // If we're upgrading publishing logic via excavator using a known excavator PR, ensure we test the publish
        // even if the publish command is not called. However, don't force every single PR to do the publish that
        // takes ages.
        if (isPublishingExcavatorBranch) {
            project.afterEvaluate(_ignored -> {
                project.getPluginManager().apply(LifecycleBasePlugin.class);

                // Use the check task as many OSS don't use circle-templates so can't depend on say publishToMavenLocal
                // being run.
                project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, checkTask -> {
                    // We use publishToSonatype rather than each individual publish task we know about to exercise the
                    // sonatype publishes of other maven publications (like gradle plugin descriptors) that cause
                    // errors.
                    checkTask.dependsOn(
                            project.getTasks().named("publishToSonatype"),
                            project.getRootProject().getTasks().named("closeSonatypeStagingRepository"));
                });
            });
        }
    }

    private void disableModuleMetadata() {
        // Turning off module metadata so that all consumers just use regular POMs
        project.getTasks()
                .withType(GenerateModuleMetadata.class)
                .configureEach(generateModuleMetadata -> generateModuleMetadata.setEnabled(false));
    }

    public void addPublication(String publicationName, Action<MavenPublication> publicationConfiguration) {
        sonatypePublicationNames.add(publicationName);

        project.getExtensions().getByType(PublishingExtension.class).publications(publications -> {
            MavenPublication mavenPublication = publications.maybeCreate(publicationName, MavenPublication.class);
            publicationConfiguration.execute(mavenPublication);
            mavenPublication.pom(pom -> {
                pom.licenses(licenses -> {
                    licenses.license(license -> {
                        license.getName().set("The Apache License, Version 2.0");
                        license.getUrl().set("https://www.apache.org/licenses/LICENSE-2.0");
                    });
                });
                pom.developers(developers -> {
                    developers.developer(developer -> {
                        developer.getId().set("palantir");
                        developer.getName().set("Palantir Technologies Inc");
                        developer.getOrganizationUrl().set("https://www.palantir.com");
                    });
                });
            });
            signPublication(mavenPublication);
        });
    }

    private void signPublication(Publication publication) {
        GpgSigningKey.fromEnv(project).ifPresent(gpgSigningKey -> {
            project.getPluginManager().apply(SigningPlugin.class);

            SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
            signing.useInMemoryPgpKeys(gpgSigningKey.keyId(), gpgSigningKey.key(), gpgSigningKey.password());
            signing.sign(publication);
        });
    }

    public static ExternalPublishBasePlugin applyTo(Project project) {
        project.getPluginManager().apply(ExternalPublishBasePlugin.class);
        return project.getPlugins().findPlugin(ExternalPublishBasePlugin.class);
    }
}
