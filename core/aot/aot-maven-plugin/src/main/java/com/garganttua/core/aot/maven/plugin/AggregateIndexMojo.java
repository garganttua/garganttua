package com.garganttua.core.aot.maven.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import com.garganttua.core.aot.commons.AOTMetadataConstants;

/**
 * Merges annotation index files from dependency JARs into the current module's
 * output directory. Index files are located under {@code META-INF/garganttua/index/}
 * and contain one entry per line (class or method reference).
 */
@Mojo(name = "aggregate-index", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class AggregateIndexMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Scans dependency JARs for annotation index files and merges their entries
     * with any local index files into the module output directory.
     *
     * @throws MojoExecutionException if reading or writing an index file fails
     */
    @Override
    public void execute() throws MojoExecutionException {
        Map<String, Set<String>> dependencyEntries = ArtifactScanner.scan(project, AOTMetadataConstants.INDEX_DIR);

        if (dependencyEntries.isEmpty()) {
            getLog().info("No annotation index entries found in dependencies.");
            return;
        }

        int totalFiles = 0;
        int totalEntries = 0;

        for (Map.Entry<String, Set<String>> entry : dependencyEntries.entrySet()) {
            Path localFile = outputDirectory.toPath()
                    .resolve(AOTMetadataConstants.INDEX_DIR)
                    .resolve(entry.getKey());
            Set<String> mergedEntries = mergeWithLocal(localFile, entry.getValue());

            writeIndex(localFile, mergedEntries);

            totalFiles++;
            totalEntries += mergedEntries.size();
        }

        getLog().info("Aggregated " + totalEntries + " index entries across " + totalFiles + " annotation index files.");
    }

    private Set<String> mergeWithLocal(Path localFile, Set<String> dependencyEntries) throws MojoExecutionException {
        Set<String> mergedEntries = new LinkedHashSet<>(dependencyEntries);
        if (Files.exists(localFile)) {
            try (BufferedReader reader = Files.newBufferedReader(localFile, StandardCharsets.UTF_8)) {
                ArtifactScanner.collectNonBlankLines(reader, mergedEntries);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read existing index file: " + localFile, e);
            }
        }
        return mergedEntries;
    }

    private void writeIndex(Path localFile, Set<String> mergedEntries) throws MojoExecutionException {
        try {
            Path parent = localFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(localFile, mergedEntries, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write merged index file: " + localFile, e);
        }
    }
}
