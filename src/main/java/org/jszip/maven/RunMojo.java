/*
 * Copyright 2011-2012 Stephen Connolly.
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

package org.jszip.maven;

import org.apache.maven.ProjectDependenciesResolver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginContainerException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DuplicateProjectException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectSorter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.ProjectTransitivityFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.artifact.filter.collection.TypeFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.InvokerLogger;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.dag.CycleDetectedException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jszip.css.CssEngine;
import org.jszip.jetty.CssEngineResource;
import org.jszip.jetty.JettyWebAppContext;
import org.jszip.jetty.SystemProperties;
import org.jszip.jetty.SystemProperty;
import org.jszip.jetty.VirtualDirectoryResource;
import org.jszip.less.LessEngine;
import org.jszip.pseudo.io.PseudoDirectoryScanner;
import org.jszip.pseudo.io.PseudoFile;
import org.jszip.pseudo.io.PseudoFileOutputStream;
import org.jszip.pseudo.io.PseudoFileSystem;
import org.jszip.sass.SassEngine;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

/**
 * Starts a Jetty servlet container with resources resolved from the reactor projects to enable live editing of those
 * resources and pom and classpath scanning to restart the servlet container when the classpath is modified. Note that
 * if the poms are modified in such a way that the reactor build plan is modified, we have no choice but to stop the
 * servlet container and require the maven session to be restarted, but best effort is made to ensure that restart
 * is only when required.
 */
@org.apache.maven.plugins.annotations.Mojo(name = "run",
        defaultPhase = LifecyclePhase.TEST_COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class RunMojo extends AbstractJSZipMojo {
    /**
     * The artifact path mappings for unpacking.
     */
    @Parameter(property = "mappings")
    private Mapping[] mappings;

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */
    @Parameter(alias = "useTextClasspath", defaultValue = "false")
    private boolean useTestScope;


    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter(property = "maven.war.webxml", readonly = true)
    private String webXml;


    /**
     * The directory containing generated classes.
     */
    @Parameter(property = "project.build.outputDirectory", required = true)
    private File classesDirectory;


    /**
     * The directory containing generated test classes.
     */
    @Parameter(property = "project.build.testOutputDirectory", required = true)
    private File testClassesDirectory;

    /**
     * Root directory for all html/jsp etc files
     */
    @Parameter(defaultValue = "${basedir}/src/main/webapp", required = true)
    private File warSourceDirectory = null;

    /**
     * The directory where the webapp is built.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    private File webappDirectory;

    /**
     * List of connectors to use. If none are configured
     * then the default is a single SelectChannelConnector at port 8080. You can
     * override this default port number by using the system property jetty.port
     * on the command line, eg:  mvn -Djetty.port=9999 jszip:run. Consider using instead
     * the &lt;jettyXml&gt; element to specify external jetty xml config file.
     */
    @Parameter
    protected Connector[] connectors;

    /**
     * The module that the goal should apply to. Specify either groupId:artifactId or just plain artifactId.
     */
    @Parameter(property = "jszip.run.module")
    private String runModule;

    /**
     * List of the packaging types will be considered for executing this goal. Normally you do not
     * need to configure this parameter unless you have a custom war packaging type. Defaults to <code>war</code>
     */
    @Parameter
    private String[] runPackages;

    /**
     * System properties to set before execution.
     * Note that these properties will NOT override System properties
     * that have been set on the command line or by the JVM. They WILL
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     */
    @Parameter
    private SystemProperties systemProperties;

    /**
     * The project builder
     */
    @Component
    private ProjectBuilder projectBuilder;

    /**
     * The reactor project
     */
    @Parameter(property = "reactorProjects", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    /**
     * Location of the local repository.
     */
    @Parameter(property = "localRepository", required = true, readonly = true)
    private ArtifactRepository localRepository;

    /**
     * The current build session instance. This is used for plugin manager API calls.
     */
    @Parameter(property = "session", required = true, readonly = true)
    private MavenSession session;

    /**
     * The forked project.
     */
    @Parameter(property = "executedProject", required = true, readonly = true)
    private MavenProject executedProject;

    /**
     * Directory containing the less processor.
     */
    @Parameter(defaultValue = "src/build/js/less-rhino.js")
    private File customLessScript;

    /**
     * Skip compilation.
     */
    @Parameter(property = "jszip.less.skip", defaultValue = "false")
    private boolean lessSkip;

    /**
     * Force compilation even if the source LESS file is older than the destination CSS file.
     */
    @Parameter(property = "jszip.less.forceIfOlder", defaultValue = "false")
    private boolean lessForceIfOlder;

    /**
     * Compress CSS.
     */
    @Parameter(property = "jszip.less.compress", defaultValue = "true")
    private boolean lessCompress;

    /**
     * Indicates whether the build will continue even if there are compilation errors.
     */
    @Parameter(property = "jszip.less.failOnError", defaultValue = "true")
    private boolean lessFailOnError;

    /**
     * Indicates whether to show extracts of the code where errors occur.
     */
    @Parameter(property = "jszip.less.showErrorExtracts", defaultValue = "false")
    private boolean showErrorExtracts;

    /**
     * A list of &lt;include&gt; elements specifying the less files (by pattern) that should be included in
     * processing.
     */
    @Parameter
    private List<String> lessIncludes;

    /**
     * A list of &lt;exclude&gt; elements specifying the less files (by pattern) that should be excluded from
     * processing.
     */
    @Parameter
    private List<String> lessExcludes;

    /**
     * Skip compilation.
     */
    @Parameter(property = "jszip.sass.skip", defaultValue = "false")
    private boolean sassSkip;

    /**
     * Force compilation even if the source Sass file is older than the destination CSS file.
     */
    @Parameter(property = "jszip.sass.forceIfOlder", defaultValue = "false")
    private boolean sassForceIfOlder;

    /**
     * Indicates whether the build will continue even if there are compilation errors.
     */
    @Parameter(property = "jszip.sass.failOnError", defaultValue = "true")
    private boolean sassFailOnError;

    /**
     * A list of &lt;include&gt; elements specifying the sass files (by pattern) that should be included in
     * processing.
     */
    @Parameter
    private List<String> sassIncludes;

    /**
     * A list of &lt;exclude&gt; elements specifying the sass files (by pattern) that should be excluded from
     * processing.
     */
    @Parameter
    private List<String> sassExcludes;

    /**
     * The character encoding scheme to be applied when reading SASS files.
     */
    @Parameter( defaultValue = "${project.build.sourceEncoding}" )
    private String encoding;

    /**
     * Used to resolve transitive dependencies.
     */
    @Component
    private ProjectDependenciesResolver projectDependenciesResolver;

    /**
     * Maven ProjectHelper.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The Maven plugin Manager
     */
    @Component
    private MavenPluginManager mavenPluginManager;

    /**
     * This plugin's descriptor
     */
    @Parameter(property = "plugin")
    private PluginDescriptor pluginDescriptor;

    /**
     * Our resource filterer
     */
    @Component(role = org.apache.maven.shared.filtering.MavenResourcesFiltering.class, hint = "default")
    protected MavenResourcesFiltering mavenResourcesFiltering;

    private final String scope = "test";
    private final long classpathCheckInterval = TimeUnit.SECONDS.toMillis(10);

    public void execute()
            throws MojoExecutionException, MojoFailureException {
        if (runPackages == null || runPackages.length == 0) {
            runPackages = new String[]{"war"};
        }

        injectMissingArtifacts(project, executedProject);

        if (!Arrays.asList(runPackages).contains(project.getPackaging())) {
            getLog().info("Skipping JSZip run: module " + ArtifactUtils.versionlessKey(project.getGroupId(),
                    project.getArtifactId()) + " as not specified in runPackages");
            return;
        }
        if (StringUtils.isNotBlank(runModule)
                && !project.getArtifactId().equals(runModule)
                && !ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId()).equals(runModule)) {
            getLog().info("Skipping JSZip run: module " + ArtifactUtils.versionlessKey(project.getGroupId(),
                    project.getArtifactId()) + " as requested runModule is " + runModule);
            return;
        }
        getLog().info("Starting JSZip run: module " + ArtifactUtils.versionlessKey(project.getGroupId(),
                project.getArtifactId()));
        MavenProject project = this.project;
        long lastResourceChange = System.currentTimeMillis();
        long lastClassChange = System.currentTimeMillis();
        long lastPomChange = getPomsLastModified();

        Server server = new Server();
        if (connectors == null || connectors.length == 0) {
            SelectChannelConnector selectChannelConnector = new SelectChannelConnector();
            selectChannelConnector.setPort(8080);
            connectors = new Connector[]{
                    selectChannelConnector
            };
        }
        server.setConnectors(connectors);
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        HandlerCollection handlerCollection = new HandlerCollection(true);
        DefaultHandler defaultHandler = new DefaultHandler();
        handlerCollection.setHandlers(new Handler[]{contexts, defaultHandler});
        server.setHandler(handlerCollection);
        try {
            server.start();
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        List<MavenProject> reactorProjects = this.reactorProjects;
        WebAppContext webAppContext;
        Resource webXml;
        List<Resource> resources;
        try {
            resources = new ArrayList<Resource>();
            addCssEngineResources(project, reactorProjects, mappings, resources);
            for (Artifact a : getOverlayArtifacts(project, scope)) {
                addOverlayResources(reactorProjects, resources, a);
            }
            if (warSourceDirectory == null) {
                warSourceDirectory = new File(project.getBasedir(), "src/main/webapp");
            }
            if (warSourceDirectory.isDirectory()) {
                resources.add(Resource.newResource(warSourceDirectory));
            }
            Collections.reverse(resources);
            getLog().debug("Overlays:");
            int index = 0;
            for (Resource r : resources) {
                getLog().debug("  [" + index++ + "] = " + r);
            }
            final ResourceCollection resourceCollection =
                    new ResourceCollection(resources.toArray(new Resource[resources.size()]));

            webAppContext = new JettyWebAppContext();
            webAppContext.setWar(warSourceDirectory.getAbsolutePath());
            webAppContext.setBaseResource(resourceCollection);

            WebAppClassLoader classLoader = new WebAppClassLoader(webAppContext);
            for (String s : getClasspathElements(project, scope)) {
                classLoader.addClassPath(s);
            }
            webAppContext.setClassLoader(classLoader);

            contexts.setHandlers(new Handler[]{webAppContext});
            contexts.start();
            webAppContext.start();
            Resource webInf = webAppContext.getWebInf();
            webXml = webInf != null ? webInf.getResource("web.xml") : null;
        } catch (MojoExecutionException e) {
            throw e;
        } catch (MojoFailureException e) {
            throw e;
        } catch (ArtifactFilterException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        long webXmlLastModified = webXml == null ? 0L : webXml.lastModified();
        try {

            getLog().info("Context started. Will restart if changes to poms detected.");
            long nextClasspathCheck = System.currentTimeMillis() + classpathCheckInterval;
            while (true) {
                long nextCheck = System.currentTimeMillis() + 500;
                long pomsLastModified = getPomsLastModified();
                boolean pomsChanged = lastPomChange < pomsLastModified;
                boolean overlaysChanged = false;
                boolean classPathChanged = webXmlLastModified < (webXml == null ? 0L : webXml.lastModified());
                if (nextClasspathCheck < System.currentTimeMillis()) {
                    long classChange = classpathLastModified(project);
                    if (classChange > lastClassChange) {
                        classPathChanged = true;
                        lastClassChange = classChange;
                    }
                    nextClasspathCheck = System.currentTimeMillis() + classpathCheckInterval;
                }
                if (!classPathChanged && !overlaysChanged && !pomsChanged) {

                    try {
                        lastResourceChange = processResourceSourceChanges(reactorProjects, project, lastResourceChange);
                    } catch (ArtifactFilterException e) {
                        getLog().debug("Couldn't process resource changes", e);
                    }
                    try {
                        Thread.sleep(Math.max(100L, nextCheck - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        getLog().debug("Interrupted", e);
                    }
                    continue;
                }
                if (pomsChanged) {
                    getLog().info("Change in poms detected, re-parsing to evaluate impact...");
                    // we will now process this change,
                    // so from now on don't re-process
                    // even if we have issues processing
                    lastPomChange = pomsLastModified;
                    List<MavenProject> newReactorProjects;
                    try {
                        newReactorProjects = buildReactorProjects();
                    } catch (ProjectBuildingException e) {
                        getLog().info("Re-parse aborted due to malformed pom.xml file(s)", e);
                        continue;
                    } catch (CycleDetectedException e) {
                        getLog().info("Re-parse aborted due to dependency cycle in project model", e);
                        continue;
                    } catch (DuplicateProjectException e) {
                        getLog().info("Re-parse aborted due to duplicate projects in project model", e);
                        continue;
                    } catch (Exception e) {
                        getLog().info("Re-parse aborted due a problem that prevented sorting the project model", e);
                        continue;
                    }
                    if (!buildPlanEqual(newReactorProjects, this.reactorProjects)) {
                        throw new BuildPlanModifiedException("A pom.xml change has impacted the build plan.");
                    }
                    MavenProject newProject = findProject(newReactorProjects, this.project);
                    if (newProject == null) {
                        throw new BuildPlanModifiedException(
                                "A pom.xml change appears to have removed " + this.project.getId()
                                        + " from the build plan.");
                    }

                    newProject.setArtifacts(resolve(newProject, "runtime"));

                    getLog().debug("Comparing effective classpath of new and old models");
                    try {
                        classPathChanged = classPathChanged || classpathsEqual(project, newProject, scope);
                    } catch (DependencyResolutionRequiredException e) {
                        getLog().info("Re-parse aborted due to dependency resolution problems", e);
                        continue;
                    }
                    if (classPathChanged) {
                        getLog().info("Effective classpath of " + project.getId() + " has changed.");
                    } else {
                        getLog().debug("Effective classpath is unchanged.");
                    }

                    getLog().debug("Comparing effective overlays of new and old models");
                    try {
                        overlaysChanged = overlaysEqual(project, newProject);
                    } catch (OverConstrainedVersionException e) {
                        getLog().info("Re-parse aborted due to dependency resolution problems", e);
                        continue;
                    } catch (ArtifactFilterException e) {
                        getLog().info("Re-parse aborted due to overlay resolution problems", e);
                        continue;
                    }
                    if (overlaysChanged) {
                        getLog().info("Overlay modules of " + project.getId() + " have changed.");
                    } else {
                        getLog().debug("Overlay modules are unchanged.");
                    }

                    getLog().debug("Comparing overlays paths of new and old models");
                    try {
                        List<Resource> newResources = new ArrayList<Resource>();
                        // TODO newMappings
                        addCssEngineResources(newProject, newReactorProjects, mappings, resources);
                        for (Artifact a : getOverlayArtifacts(project, scope)) {
                            addOverlayResources(newReactorProjects, newResources, a);
                        }
                        if (warSourceDirectory.isDirectory()) {
                            newResources.add(Resource.newResource(warSourceDirectory));
                        }
                        Collections.reverse(newResources);
                        getLog().debug("New overlays:");
                        int index = 0;
                        for (Resource r : newResources) {
                            getLog().debug("  [" + index++ + "] = " + r);
                        }
                        boolean overlayPathsChanged = !resources.equals(newResources);
                        if (overlayPathsChanged) {
                            getLog().info("Overlay module paths of " + project.getId() + " have changed.");
                        } else {
                            getLog().debug("Overlay module paths are unchanged.");
                        }
                        overlaysChanged = overlaysChanged || overlayPathsChanged;
                    } catch (ArtifactFilterException e) {
                        getLog().info("Re-parse aborted due to overlay evaluation problems", e);
                        continue;
                    } catch (PluginConfigurationException e) {
                        getLog().info("Re-parse aborted due to overlay evaluation problems", e);
                        continue;
                    } catch (PluginContainerException e) {
                        getLog().info("Re-parse aborted due to overlay evaluation problems", e);
                        continue;
                    } catch (IOException e) {
                        getLog().info("Re-parse aborted due to overlay evaluation problems", e);
                        continue;
                    }

                    project = newProject;
                    reactorProjects = newReactorProjects;
                }

                if (!overlaysChanged && !classPathChanged) {
                    continue;
                }
                getLog().info("Restarting context to take account of changes...");
                try {
                    webAppContext.stop();
                } catch (Exception e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }

                if (classPathChanged) {
                    getLog().info("Updating classpath...");
                    try {
                        WebAppClassLoader classLoader = new WebAppClassLoader(webAppContext);
                        for (String s : getClasspathElements(project, scope)) {
                            classLoader.addClassPath(s);
                        }
                        webAppContext.setClassLoader(classLoader);
                    } catch (Exception e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    }
                }

                if (overlaysChanged || classPathChanged) {
                    getLog().info("Updating overlays...");
                    try {
                        resources = new ArrayList<Resource>();
                        addCssEngineResources(project, reactorProjects, mappings, resources);
                        for (Artifact a : getOverlayArtifacts(project, scope)) {
                            addOverlayResources(reactorProjects, resources, a);
                        }
                        if (warSourceDirectory.isDirectory()) {
                            resources.add(Resource.newResource(warSourceDirectory));
                        }
                        Collections.reverse(resources);
                        getLog().debug("Overlays:");
                        int index = 0;
                        for (Resource r : resources) {
                            getLog().debug("  [" + index++ + "] = " + r);
                        }
                        final ResourceCollection resourceCollection =
                                new ResourceCollection(resources.toArray(new Resource[resources.size()]));
                        webAppContext.setBaseResource(resourceCollection);
                    } catch (Exception e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    }
                }
                try {
                    webAppContext.start();
                } catch (Exception e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
                webXmlLastModified = webXml == null ? 0L : webXml.lastModified();
                getLog().info("Context restarted.");
            }

        } finally {
            try {
                server.stop();
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private void addOverlayResources(List<MavenProject> reactorProjects, List<Resource> _resources, Artifact a)
            throws PluginConfigurationException, PluginContainerException, IOException, MojoExecutionException {
        List<Resource> resources = new ArrayList<Resource>();
        MavenProject fromReactor = findProject(reactorProjects, a);
        if (fromReactor != null) {
            MavenSession session = this.session.clone();
            session.setCurrentProject(fromReactor);
            Plugin plugin = findThisPluginInProject(fromReactor);

            // we cheat here and use our version of the plugin... but this is less of a cheat than the only
            // other way which is via reflection.
            MojoDescriptor jszipDescriptor = findMojoDescriptor(pluginDescriptor, JSZipMojo.class);

            for (PluginExecution pluginExecution : plugin.getExecutions()) {
                if (!pluginExecution.getGoals().contains(jszipDescriptor.getGoal())) {
                    continue;
                }
                MojoExecution mojoExecution =
                        createMojoExecution(plugin, pluginExecution, jszipDescriptor);
                JSZipMojo mojo = (JSZipMojo) mavenPluginManager
                        .getConfiguredMojo(Mojo.class, session, mojoExecution);
                try {
                    File contentDirectory = mojo.getContentDirectory();
                    if (contentDirectory.isDirectory()) {
                        getLog().debug(
                                "Adding resource directory " + contentDirectory);
                        resources.add(Resource.newResource(contentDirectory));
                    }
                    // TODO filtering support
                    //
                    // The good news:
                    //  * resources:resources gets the list of resources from /project/build/resources *only*
                    // The bad news:
                    //  * looks like maven-invoker is the only way to safely invoke it again
                    //
                    // probable solution
                    //
                    // 1. get the list of all resource directories, add on the scan for changes
                    // 2. if a change to a non-filtered file, just copy it over
                    // 3. if a change to a filtered file or a change to effective pom, use maven-invoker to run the
                    //    lifecycle up to 'compile' or 'process-resources' <-- preferred
                    //
                    File resourcesDirectory = mojo.getResourcesDirectory();
                    if (resourcesDirectory.isDirectory()) {
                        getLog().debug(
                                "Adding resource directory " + resourcesDirectory);
                        resources.add(Resource.newResource(resourcesDirectory));
                    }
                } finally {
                    mavenPluginManager.releaseMojo(mojo, mojoExecution);
                }
            }
        } else {
            resources.add(Resource.newResource("jar:" + a.getFile().toURI().toURL() + "!/"));
        }

        // TODO support live reloading of mappings
        String path = "";
        if (mappings != null) {
            for (Mapping mapping : mappings) {
                if (mapping.isMatch(a)) {
                    path = StringUtils.clean(mapping.getPath());
                    break;
                }
            }
        }

        if (StringUtils.isBlank(path)) {
            _resources.addAll(resources);
        } else {
            ResourceCollection child = new ResourceCollection(resources.toArray(new Resource[resources.size()]));
            _resources.add(new VirtualDirectoryResource(child, path));
        }
    }

    private void addCssEngineResources(MavenProject project, List<MavenProject> reactorProjects, Mapping[] mappings, List<Resource> _resources)
            throws MojoExecutionException, IOException {
        List<PseudoFileSystem.Layer> layers = new ArrayList<PseudoFileSystem.Layer>();
        layers.add(new PseudoFileSystem.FileLayer("/virtual", warSourceDirectory));
        FilterArtifacts filter = new FilterArtifacts();

        filter.addFilter(new ProjectTransitivityFilter(project.getDependencyArtifacts(), false));

        filter.addFilter(new ScopeFilter("runtime", ""));

        filter.addFilter(new TypeFilter(JSZIP_TYPE, ""));

        // start with all artifacts.
        Set<Artifact> artifacts = project.getArtifacts();

        // perform filtering
        try {
            artifacts = filter.filter(artifacts);
        } catch (ArtifactFilterException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        for (Artifact artifact : artifacts) {
            String path = Mapping.getArtifactPath(mappings, artifact);
            getLog().info("Adding " + ArtifactUtils.key(artifact) + " to virtual filesystem");
            File file = artifact.getFile();
            if (file.isDirectory()) {
                MavenProject fromReactor = findProject(reactorProjects, artifact);
                if (fromReactor != null) {
                    MavenSession session = this.session.clone();
                    session.setCurrentProject(fromReactor);
                    Plugin plugin = findThisPluginInProject(fromReactor);
                    try {
                        // we cheat here and use our version of the plugin... but this is less of a cheat than the only
                        // other way which is via reflection.
                        MojoDescriptor jszipDescriptor = findMojoDescriptor(this.pluginDescriptor, JSZipMojo.class);

                        for (PluginExecution pluginExecution : plugin.getExecutions()) {
                            if (!pluginExecution.getGoals().contains(jszipDescriptor.getGoal())) {
                                continue;
                            }
                            MojoExecution mojoExecution =
                                    createMojoExecution(plugin, pluginExecution, jszipDescriptor);
                            JSZipMojo mojo = (JSZipMojo) mavenPluginManager
                                    .getConfiguredMojo(org.apache.maven.plugin.Mojo.class, session, mojoExecution);
                            try {
                                File contentDirectory = mojo.getContentDirectory();
                                if (contentDirectory.isDirectory()) {
                                    getLog().debug("Merging directory " + contentDirectory + " into " + path);
                                    layers.add(new PseudoFileSystem.FileLayer(path, contentDirectory));
                                }
                                File resourcesDirectory = mojo.getResourcesDirectory();
                                if (resourcesDirectory.isDirectory()) {
                                    getLog().debug("Merging directory " + contentDirectory + " into " + path);
                                    layers.add(new PseudoFileSystem.FileLayer(path, resourcesDirectory));
                                }
                            } finally {
                                mavenPluginManager.releaseMojo(mojo, mojoExecution);
                            }
                        }
                    } catch (PluginConfigurationException e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    } catch (PluginContainerException e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    }
                } else {
                    throw new MojoExecutionException("Cannot find jzsip artifact: " + artifact.getId());
                }
            } else {
                try {
                    getLog().debug("Merging .zip file " + file + " into " + path);
                    layers.add(new PseudoFileSystem.ZipLayer(path, file));
                } catch (IOException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            }
        }

        final PseudoFileSystem fs = new PseudoFileSystem(layers);

        CssEngine engine = new LessEngine(fs, encoding == null ? "utf-8" : encoding, getLog(), lessCompress, customLessScript, showErrorExtracts);

        // look for files to compile

        PseudoDirectoryScanner scanner = new PseudoDirectoryScanner();

        scanner.setFileSystem(fs);

        scanner.setBasedir(fs.getPseudoFile("/virtual"));

        if (lessIncludes != null && !lessIncludes.isEmpty()) {
            scanner.setIncludes(processIncludesExcludes(lessIncludes));
        } else {
            scanner.setIncludes(new String[]{"**/*.less"});
        }

        if (lessExcludes != null && !lessExcludes.isEmpty()) {
            scanner.setExcludes(processIncludesExcludes(lessExcludes));
        } else {
            scanner.setExcludes(new String[0]);
        }

        scanner.scan();

        for (String fileName : new ArrayList<String>(Arrays.asList(scanner.getIncludedFiles()))) {
            final CssEngineResource child = new CssEngineResource(fs, engine, "/virtual/" + fileName,
                    new File(webappDirectory, engine.mapName(fileName)));
            final String path = FileUtils.dirname(fileName);
            if (StringUtils.isBlank(path)) {
                _resources.add(new VirtualDirectoryResource(new VirtualDirectoryResource(child, child.getName()), ""));
            } else {
                _resources.add(new VirtualDirectoryResource(new VirtualDirectoryResource(child, child.getName()), path));
            }
        }

        engine = new SassEngine(fs, encoding == null ? "utf-8" : encoding);

        if (sassIncludes != null && !sassIncludes.isEmpty()) {
            scanner.setIncludes(processIncludesExcludes(sassIncludes));
        } else {
            scanner.setIncludes(new String[]{"**/*.sass","**/*.scss"});
        }

        if (sassExcludes != null && !sassExcludes.isEmpty()) {
            scanner.setExcludes(processIncludesExcludes(sassExcludes));
        } else {
            scanner.setExcludes(new String[]{"**/_*.sass","**/_*.scss"});
        }

        scanner.scan();

        for (String fileName : new ArrayList<String>(Arrays.asList(scanner.getIncludedFiles()))) {
            final CssEngineResource child = new CssEngineResource(fs, engine, "/virtual/" + fileName,
                    new File(webappDirectory, engine.mapName(fileName)));
            final String path = FileUtils.dirname(fileName);
            if (StringUtils.isBlank(path)) {
                _resources.add(new VirtualDirectoryResource(new VirtualDirectoryResource(child, child.getName()), ""));
            } else {
                _resources.add(new VirtualDirectoryResource(new VirtualDirectoryResource(child, child.getName()), path));
            }
        }

    }

    private void injectMissingArtifacts(MavenProject destination, MavenProject source) {
        if (destination.getArtifact().getFile() == null && source.getArtifact().getFile() != null) {
            getLog().info("Pushing primary artifact from forked execution into current execution");
            destination.getArtifact().setFile(source.getArtifact().getFile());
        }
        for (Artifact executedArtifact : source.getAttachedArtifacts()) {
            String executedArtifactId =
                    (executedArtifact.getClassifier() == null ? "." : "-" + executedArtifact.getClassifier() + ".")
                            + executedArtifact.getType();
            if (StringUtils.equals(executedArtifact.getGroupId(), destination.getGroupId())
                    && StringUtils.equals(executedArtifact.getArtifactId(), destination.getArtifactId())
                    && StringUtils.equals(executedArtifact.getVersion(), destination.getVersion())) {
                boolean found = false;
                for (Artifact artifact : destination.getAttachedArtifacts()) {
                    if (StringUtils.equals(artifact.getGroupId(), destination.getGroupId())
                            && StringUtils.equals(artifact.getArtifactId(), destination.getArtifactId())
                            && StringUtils.equals(artifact.getVersion(), destination.getVersion())
                            && StringUtils.equals(artifact.getClassifier(), executedArtifact.getClassifier())
                            && StringUtils.equals(artifact.getType(), executedArtifact.getType())) {
                        if (artifact.getFile() == null) {
                            getLog().info("Pushing " + executedArtifactId
                                    + " artifact from forked execution into current execution");
                            artifact.setFile(executedArtifact.getFile());
                        }
                        found = true;
                    }
                }
                if (!found) {
                    getLog().info("Attaching " +
                            executedArtifactId
                            + " artifact from forked execution into current execution");
                    projectHelper
                            .attachArtifact(destination, executedArtifact.getType(), executedArtifact.getClassifier(),
                                    executedArtifact.getFile());
                }
            }
        }
    }

    private long processResourceSourceChanges(List<MavenProject> reactorProjects, MavenProject project,
                                              long lastModified)
            throws ArtifactFilterException {
        long newLastModified = lastModified;
        getLog().debug("Last modified for resource sources = " + lastModified);

        Set<File> checked = new HashSet<File>();
        for (Artifact a : getOverlayArtifacts(project, scope)) {
            MavenProject p = findProject(reactorProjects, a);
            if (p == null || p.getBuild() == null || p.getBuild().getResources() == null) {
                continue;
            }
            boolean changed = false;
            boolean changedFiltered = false;
            for (org.apache.maven.model.Resource r : p.getBuild().getResources()) {
                File dir = new File(r.getDirectory());
                getLog().debug("Checking last modified for " + dir);
                if (checked.contains(dir)) {
                    continue;
                }
                checked.add(dir);
                long dirLastModified = recursiveLastModified(dir);
                if (lastModified < dirLastModified) {
                    changed = true;
                    if (r.isFiltering()) {
                        changedFiltered = true;
                    }
                }
            }
            if (changedFiltered) {
                getLog().info("Detected change in resources of " + ArtifactUtils.versionlessKey(a) + "...");
                getLog().debug("Resource filtering is used by project, invoking Maven to handle update");
                // need to let Maven handle it as its the only (although slower) safe way to do it right with filters
                InvocationRequest request = new DefaultInvocationRequest();
                request.setPomFile(p.getFile());
                request.setInteractive(false);
                request.setRecursive(false);
                request.setGoals(Collections.singletonList("process-resources"));

                Invoker invoker = new DefaultInvoker();
                invoker.setLogger(new MavenProxyLogger());
                try {
                    invoker.execute(request);
                    newLastModified = System.currentTimeMillis();
                    getLog().info("Change in resources of " + ArtifactUtils.versionlessKey(a) + " processed");
                } catch (MavenInvocationException e) {
                    getLog().info(e);
                }
            } else if (changed) {
                getLog().info("Detected change in resources of " + ArtifactUtils.versionlessKey(a) + "...");
                getLog().debug("Resource filtering is not used by project, handling update ourselves");
                // can do it fast ourselves
                MavenResourcesExecution mavenResourcesExecution =
                        new MavenResourcesExecution(p.getResources(), new File(p.getBuild().getOutputDirectory()), p,
                                p.getProperties().getProperty("project.build.sourceEncoding"), Collections.emptyList(),
                                Collections.<String>emptyList(), session);
                try {
                    mavenResourcesFiltering.filterResources(mavenResourcesExecution);
                    newLastModified = System.currentTimeMillis();
                    getLog().info("Change in resources of " + ArtifactUtils.versionlessKey(a) + " processed");
                } catch (MavenFilteringException e) {
                    getLog().info(e);
                }
            }
        }
        return newLastModified;
    }

    private List<MavenProject> buildReactorProjects() throws Exception {

        List<MavenProject> projects = new ArrayList<MavenProject>();
        for (MavenProject p : reactorProjects) {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest();

            request.setProcessPlugins(true);
            request.setProfiles(request.getProfiles());
            request.setActiveProfileIds(session.getRequest().getActiveProfiles());
            request.setInactiveProfileIds(session.getRequest().getInactiveProfiles());
            request.setRemoteRepositories(session.getRequest().getRemoteRepositories());
            request.setSystemProperties(session.getSystemProperties());
            request.setUserProperties(session.getUserProperties());
            request.setRemoteRepositories(session.getRequest().getRemoteRepositories());
            request.setPluginArtifactRepositories(session.getRequest().getPluginArtifactRepositories());
            request.setRepositorySession(session.getRepositorySession());
            request.setLocalRepository(localRepository);
            request.setBuildStartTime(session.getRequest().getStartTime());
            request.setResolveDependencies(true);
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_STRICT);
            projects.add(projectBuilder.build(p.getFile(), request).getProject());
        }
        return new ProjectSorter(projects).getSortedProjects();
    }

    private long classpathLastModified(MavenProject project) {
        long result = Long.MIN_VALUE;
        try {
            for (String element : getClasspathElements(project, scope)) {
                File elementFile = new File(element);
                result = Math.max(recursiveLastModified(elementFile), result);
            }
        } catch (DependencyResolutionRequiredException e) {
            // ignore
        }
        return result;

    }

    private long recursiveLastModified(File fileOrDirectory) {
        long result = Long.MIN_VALUE;
        if (fileOrDirectory.exists()) {
            result = Math.max(fileOrDirectory.lastModified(), result);
            if (fileOrDirectory.isDirectory()) {
                Stack<Iterator<File>> stack = new Stack<Iterator<File>>();
                stack.push(contentsAsList(fileOrDirectory).iterator());
                while (!stack.empty()) {
                    Iterator<File> i = stack.pop();
                    while (i.hasNext()) {
                        File file = i.next();
                        result = Math.max(file.lastModified(), result);
                        if (file.isDirectory()) {
                            stack.push(i);
                            i = contentsAsList(file).iterator();
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<File> contentsAsList(File directory) {
        File[] files = directory.listFiles();
        return files == null ? Collections.<File>emptyList() : Arrays.asList(files);
    }

    private boolean classpathsEqual(MavenProject oldProject, MavenProject newProject, String scope)
            throws DependencyResolutionRequiredException {
        int seq = 0;
        List<String> newCP = getClasspathElements(newProject, scope);
        List<String> oldCP = getClasspathElements(oldProject, scope);
        boolean classPathChanged = newCP.size() != oldCP.size();
        for (Iterator<String> i = newCP.iterator(), j = oldCP.iterator(); i.hasNext() || j.hasNext(); ) {
            String left = i.hasNext() ? i.next() : "(empty)";
            String right = j.hasNext() ? j.next() : "(empty)";
            if (!StringUtils.equals(left, right)) {
                getLog().debug("classpath[" + seq + "]");
                getLog().debug("  old = " + left);
                getLog().debug("  new = " + right);
                classPathChanged = true;
            }
            seq++;
        }
        return classPathChanged;
    }

    private MavenProject findProject(List<MavenProject> newReactorProjects, MavenProject oldProject) {
        final String targetId = oldProject.getId();
        for (MavenProject newProject : newReactorProjects) {
            if (targetId.equals(newProject.getId())) {
                return newProject;
            }
        }
        return null;
    }

    private boolean buildPlanEqual(List<MavenProject> newPlan, List<MavenProject> oldPlan) {
        if (newPlan.size() != oldPlan.size()) {
            return false;
        }
        int seq = 0;
        for (Iterator<MavenProject> i = newPlan.iterator(), j = oldPlan.iterator(); i.hasNext() && j.hasNext(); ) {
            MavenProject left = i.next();
            MavenProject right = j.next();
            getLog().debug(
                    "[" + (seq++) + "] = " + left.equals(right) + (left == right ? " same" : " diff") + " : "
                            + left.getName() + "[" + left.getDependencies().size() + "], " + right.getName()
                            + "["
                            + right.getDependencies().size() + "]");
            if (!left.equals(right)) {
                return false;
            }
            if (left.getDependencies().size() != right.getDependencies().size()) {
                getLog().info("Dependency tree of " + left.getId() + " has been modified");
            }
        }
        return true;
    }

    private boolean overlaysEqual(MavenProject oldProject, MavenProject newProject)
            throws ArtifactFilterException, OverConstrainedVersionException {
        boolean overlaysChanged;
        Set<Artifact> newOA = getOverlayArtifacts(newProject, scope);
        Set<Artifact> oldOA = getOverlayArtifacts(oldProject, scope);
        overlaysChanged = newOA.size() != oldOA.size();
        for (Artifact n : newOA) {
            boolean found = false;
            for (Artifact o : oldOA) {
                if (StringUtils.equals(n.getArtifactId(), o.getArtifactId()) && StringUtils
                        .equals(n.getGroupId(), o.getGroupId())) {
                    if (o.getSelectedVersion().equals(n.getSelectedVersion())) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                getLog().debug("added overlay artifact: " + n);
                overlaysChanged = true;
            }
        }
        for (Artifact o : oldOA) {
            boolean found = false;
            for (Artifact n : newOA) {
                if (StringUtils.equals(n.getArtifactId(), o.getArtifactId()) && StringUtils
                        .equals(n.getGroupId(), o.getGroupId())) {
                    if (o.getSelectedVersion().equals(n.getSelectedVersion())) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                getLog().debug("removed overlay artifact: " + o);
                overlaysChanged = true;
            }
        }
        if (overlaysChanged) {
            getLog().info("Effective overlays of " + oldProject.getId() + " have changed.");
        } else {
            getLog().debug("Effective overlays are unchanged.");
        }
        return overlaysChanged;
    }

    @SuppressWarnings("unchecked")
    private Set<Artifact> getOverlayArtifacts(MavenProject project, String scope) throws ArtifactFilterException {
        FilterArtifacts filter = new FilterArtifacts();

        filter.addFilter(new ProjectTransitivityFilter(project.getDependencyArtifacts(), false));

        filter.addFilter(new ScopeFilter(scope, ""));

        filter.addFilter(new TypeFilter(JSZIP_TYPE, ""));

        return filter.filter(project.getArtifacts());
    }

    private long getPomsLastModified() {
        long result = Long.MIN_VALUE;
        for (MavenProject p : reactorProjects) {
            result = Math.max(p.getFile().lastModified(), result);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> getClasspathElements(MavenProject project, String scope)
            throws DependencyResolutionRequiredException {
        if ("test".equals(scope)) {
            return project.getTestClasspathElements();
        }
        if ("compile".equals(scope)) {
            return project.getCompileClasspathElements();
        }
        if ("runtime".equals(scope)) {
            return project.getRuntimeClasspathElements();
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Set<Artifact> resolve(MavenProject newProject, String scope)
            throws MojoExecutionException {
        try {
            return projectDependenciesResolver.resolve(newProject, Collections.singletonList(scope), session);
        } catch (ArtifactNotFoundException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public void setSystemProperties(SystemProperties systemProperties) {
        if (this.systemProperties == null) {
            this.systemProperties = systemProperties;
        } else {
            Iterator itor = systemProperties.getSystemProperties().iterator();
            while (itor.hasNext()) {
                SystemProperty prop = (SystemProperty) itor.next();
                this.systemProperties.setSystemProperty(prop);
            }
        }
    }

    public SystemProperties getSystemProperties() {
        return this.systemProperties;
    }


    private class MavenProxyLogger implements InvokerLogger {

        public void debug(String content) {
            getLog().debug(content);
        }

        public void info(Throwable error) {
            getLog().info(error);
        }

        public void info(String content, Throwable error) {
            getLog().info(content, error);
        }

        public void info(String content) {
            getLog().info(content);
        }

        public void warn(Throwable error) {
            getLog().warn(error);
        }

        public void error(String content, Throwable error) {
            getLog().error(content, error);
        }

        public void debug(String content, Throwable error) {
            getLog().debug(content, error);
        }

        public void debug(Throwable error) {
            getLog().debug(error);
        }

        public void warn(String content) {
            getLog().warn(content);
        }

        public void error(Throwable error) {
            getLog().error(error);
        }

        public void error(String content) {
            getLog().error(content);
        }

        public void warn(String content, Throwable error) {
            getLog().warn(content, error);
        }

        public void fatalError(String s) {
            getLog().error(s);
        }

        public boolean isDebugEnabled() {
            return getLog().isDebugEnabled();
        }

        public boolean isInfoEnabled() {
            return getLog().isInfoEnabled();
        }

        public boolean isWarnEnabled() {
            return getLog().isWarnEnabled();
        }

        public boolean isErrorEnabled() {
            return getLog().isErrorEnabled();
        }

        public void fatalError(String s, Throwable throwable) {
            getLog().error(s, throwable);
        }

        public boolean isFatalErrorEnabled() {
            return getLog().isErrorEnabled();
        }

        public void setThreshold(int i) {
        }

        public int getThreshold() {
            return 0;
        }
    }
}
