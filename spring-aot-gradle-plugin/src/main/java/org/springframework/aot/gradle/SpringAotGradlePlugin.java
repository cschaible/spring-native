/*
 * Copyright 2019-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aot.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.JavaCompile;

import org.springframework.aot.BootstrapCodeGenerator;
import org.springframework.aot.gradle.dsl.SpringAotExtension;
import org.springframework.aot.gradle.tasks.GenerateAotSources;
import org.springframework.boot.gradle.plugin.SpringBootPlugin;
import org.springframework.boot.gradle.tasks.bundling.BootJar;
import org.springframework.boot.gradle.tasks.run.BootRun;
import org.springframework.nativex.utils.VersionExtractor;
import org.springframework.util.FileSystemUtils;

/**
 * {@link Plugin} that generates AOT sources using {@code spring-native-aot} and compiles them.
 *
 * @author Brian Clozel
 * @author Andy Wilkinson
 */
public class SpringAotGradlePlugin implements Plugin<Project> {

	public static final String EXTENSION_NAME = "springAot";

	public static final String AOT_SOURCE_SET_NAME = "aot";

	public static final String GENERATE_TASK_NAME = "generateAot";

	public static final String AOT_TEST_SOURCE_SET_NAME = "aotTest";

	public static final String GENERATE_TEST_TASK_NAME = "generateTestAot";


	@Override
	public void apply(Project project) {

		project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
			project.getExtensions().create(EXTENSION_NAME, SpringAotExtension.class, project.getObjects());

			addSpringNativeDependency(project);

			String buildPath = project.getBuildDir().getAbsolutePath();
			Path generatedSourcesPath = Paths.get(buildPath, "generated", "sources");
			Path generatedResourcesPath = Paths.get(buildPath, "generated", "resources");
			SourceSetContainer sourceSets = project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();

			recreateGeneratedSourcesFolder(Paths.get(buildPath, "generated"));
			File aotSourcesDirectory = generatedSourcesPath.resolve(AOT_SOURCE_SET_NAME).toFile();
			File aotResourcesDirectory = generatedResourcesPath.resolve(AOT_SOURCE_SET_NAME).toFile();
			SourceSet aotSourceSet = createAotSourceSet(sourceSets, aotSourcesDirectory, aotResourcesDirectory);
			GenerateAotSources generateAotSources = createGenerateAotSourcesTask(project.getTasks(), sourceSets, aotSourcesDirectory, aotResourcesDirectory);
			configureAotTasks(project, aotSourceSet, generateAotSources);

			File aotTestSourcesDirectory = generatedSourcesPath.resolve(AOT_TEST_SOURCE_SET_NAME).toFile();
			File aotTestResourcesDirectory = generatedResourcesPath.resolve(AOT_TEST_SOURCE_SET_NAME).toFile();
			SourceSet aotTestSourceSet = createAotTestSourceSet(sourceSets, aotTestSourcesDirectory, aotTestResourcesDirectory);
			GenerateAotSources generateAotTestSources = createGenerateAotTestSourcesTask(project.getTasks(), sourceSets, aotTestSourcesDirectory, aotTestResourcesDirectory);
			configureAotTestTasks(project.getTasks(), sourceSets, aotSourceSet, aotTestSourceSet, generateAotTestSources);

			project.getPlugins().withType(SpringBootPlugin.class, springBootPlugin -> {
				project.getTasks().named(SpringBootPlugin.BOOT_JAR_TASK_NAME, BootJar.class, (bootJar) ->
						bootJar.classpath(aotSourceSet.getRuntimeClasspath()));
				project.getTasks().named("bootRun", BootRun.class, (bootRun) ->
						bootRun.classpath(aotSourceSet.getRuntimeClasspath()));
			});
			
			project.getPlugins().withId("org.jetbrains.kotlin.jvm", kotlinPlugin -> {
				project.getTasks().named("compileAotKotlin")
						.configure(compileKotlin -> compileKotlin.dependsOn(project.getTasks().named(GENERATE_TASK_NAME)));
				project.getTasks().named("compileAotTestKotlin")
						.configure(compileKotlin -> compileKotlin.dependsOn(project.getTasks().named(GENERATE_TEST_TASK_NAME)));
			});
		});
	}

	private void recreateGeneratedSourcesFolder(Path generatedSourcesFolder) {
		try {
			FileSystemUtils.deleteRecursively(generatedSourcesFolder);
			Files.createDirectories(generatedSourcesFolder);
		}
		catch (IOException exc) {
			throw new GradleException("Failed to recreate folder '" + generatedSourcesFolder.toAbsolutePath() + "'", exc);
		}
	}

	private void addSpringNativeDependency(Project project) {
		String springNativeVersion = VersionExtractor.forClass(BootstrapCodeGenerator.class);
		if (springNativeVersion != null) {
			project.getDependencies().add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
					"org.springframework.experimental:spring-native:" + springNativeVersion);
		}
	}

	private SourceSet createAotSourceSet(SourceSetContainer sourceSets, File aotSourcesDirectory, File aotResourcesDirectory) {
		SourceSet aotSourceSet = sourceSets.create(AOT_SOURCE_SET_NAME);
		aotSourceSet.setCompileClasspath(sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath());
		aotSourceSet.getJava().setSrcDirs(Collections.singletonList(aotSourcesDirectory));
		aotSourceSet.getResources().setSrcDirs(Collections.singletonList(aotResourcesDirectory));
		return aotSourceSet;
	}

	private GenerateAotSources createGenerateAotSourcesTask(TaskContainer tasks, SourceSetContainer sourceSets,
			File aotSourcesDirectory, File aotResourcesDirectory) {
		SourceSet mainSourceSet = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
		GenerateAotSources generate = tasks.create(GENERATE_TASK_NAME, GenerateAotSources.class);
		generate.setClasspath(mainSourceSet.getRuntimeClasspath());
		generate.setResourceInputDirectories(mainSourceSet.getResources());
		generate.getSourcesOutputDirectory().set(aotSourcesDirectory);
		generate.getResourcesOutputDirectory().set(aotResourcesDirectory);
		return generate;
	}

	private void configureAotTasks(Project project, SourceSet aotSourceSet, GenerateAotSources generateAotSources) {
		project.getTasks().named(aotSourceSet.getCompileJavaTaskName(), JavaCompile.class, (aotCompileJava) -> {
			aotCompileJava.source(generateAotSources.getSourcesOutputDirectory());
		});
		project.getTasks().named(aotSourceSet.getProcessResourcesTaskName(), Copy.class, (aotProcessResources) -> {
			aotProcessResources.from(generateAotSources.getResourcesOutputDirectory());
			aotProcessResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
		});
	}

	private SourceSet createAotTestSourceSet(SourceSetContainer sourceSets, File aotTestSourcesDirectory, File aotTestResourcesDirectory) {
		SourceSet aotTestSourceSet = sourceSets.create(AOT_TEST_SOURCE_SET_NAME);
		SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
		aotTestSourceSet.setCompileClasspath(testSourceSet.getCompileClasspath().plus(testSourceSet.getOutput()));
		aotTestSourceSet.getJava().setSrcDirs(Collections.singletonList(aotTestSourcesDirectory));
		aotTestSourceSet.getResources().setSrcDirs(Collections.singletonList(aotTestResourcesDirectory));
		return aotTestSourceSet;
	}

	private GenerateAotSources createGenerateAotTestSourcesTask(TaskContainer tasks, SourceSetContainer sourceSets,
			File aotTestSourcesDirectory, File aotTestResourcesDirectory) {
		SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
		GenerateAotSources generate = tasks.create(GENERATE_TEST_TASK_NAME, GenerateAotSources.class);
		generate.setClasspath(testSourceSet.getCompileClasspath().plus(testSourceSet.getOutput()));
		generate.setResourceInputDirectories(testSourceSet.getResources());
		generate.getSourcesOutputDirectory().set(aotTestSourcesDirectory);
		generate.getResourcesOutputDirectory().set(aotTestResourcesDirectory);
		return generate;
	}

	private void configureAotTestTasks(TaskContainer tasks, SourceSetContainer sourceSets, SourceSet aotSourceSet,
			SourceSet aotTestSourceSet, GenerateAotSources generateAotTestSources) {
		SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
		aotTestSourceSet.getJava().srcDir(generateAotTestSources.getSourcesOutputDirectory());
		aotTestSourceSet.getResources().srcDir(generateAotTestSources.getResourcesOutputDirectory());
		tasks.named(aotTestSourceSet.getCompileJavaTaskName()).configure(task -> task.dependsOn(generateAotTestSources));
		testSourceSet.setRuntimeClasspath(aotTestSourceSet.getOutput().minus(aotSourceSet.getOutput()).plus(testSourceSet.getRuntimeClasspath()));
		tasks.named(aotTestSourceSet.getProcessResourcesTaskName(), Copy.class, (aotProcessTestResources) -> {
			aotProcessTestResources.from(generateAotTestSources.getResourcesOutputDirectory());
			aotProcessTestResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
		});
	}

}
