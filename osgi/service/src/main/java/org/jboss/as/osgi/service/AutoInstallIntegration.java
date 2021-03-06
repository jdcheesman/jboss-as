/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.osgi.service;

import static org.jboss.as.osgi.OSGiLogger.LOGGER;
import static org.jboss.as.osgi.OSGiMessages.MESSAGES;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.jboss.as.osgi.parser.SubsystemState;
import org.jboss.as.osgi.parser.SubsystemState.OSGiCapability;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.ServerEnvironmentService;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceListener;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.deployment.deployer.DeploymentFactory;
import org.jboss.osgi.framework.AbstractBundleRevisionAdaptor;
import org.jboss.osgi.framework.AutoInstallComplete;
import org.jboss.osgi.framework.AutoInstallPlugin;
import org.jboss.osgi.framework.BundleManager;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.IntegrationServices;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.StorageState;
import org.jboss.osgi.framework.StorageStatePlugin;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.repository.XRequirementBuilder;
import org.jboss.osgi.resolver.MavenCoordinates;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.spi.BundleInfo;
import org.jboss.osgi.spi.OSGiManifestBuilder;
import org.jboss.osgi.vfs.AbstractVFS;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.repository.ContentNamespace;
import org.osgi.service.startlevel.StartLevel;

/**
 * Integration point to auto install bundles at framework startup.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 11-Sep-2010
 */
class AutoInstallIntegration extends AbstractService<AutoInstallPlugin> implements AutoInstallPlugin {

    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final InjectedValue<StorageStatePlugin> injectedStorageProvider = new InjectedValue<StorageStatePlugin>();
    private final InjectedValue<ServerEnvironment> injectedServerEnvironment = new InjectedValue<ServerEnvironment>();
    private final InjectedValue<BundleContext> injectedSystemContext = new InjectedValue<BundleContext>();
    private final InjectedValue<PackageAdmin> injectedPackageAdmin = new InjectedValue<PackageAdmin>();
    private final InjectedValue<StartLevel> injectedStartLevel = new InjectedValue<StartLevel>();
    private final InjectedValue<SubsystemState> injectedSubsystemState = new InjectedValue<SubsystemState>();
    private final InjectedValue<XEnvironment> injectedEnvironment = new InjectedValue<XEnvironment>();
    private File bundlesDir;

    static ServiceController<?> addService(final ServiceTarget target) {
        AutoInstallIntegration service = new AutoInstallIntegration();
        ServiceBuilder<?> builder = target.addService(IntegrationServices.AUTOINSTALL_PLUGIN, service);
        builder.addDependency(ServerEnvironmentService.SERVICE_NAME, ServerEnvironment.class, service.injectedServerEnvironment);
        builder.addDependency(SubsystemState.SERVICE_NAME, SubsystemState.class, service.injectedSubsystemState);
        builder.addDependency(Services.BUNDLE_MANAGER, BundleManager.class, service.injectedBundleManager);
        builder.addDependency(Services.PACKAGE_ADMIN, PackageAdmin.class, service.injectedPackageAdmin);
        builder.addDependency(Services.STORAGE_STATE_PLUGIN, StorageStatePlugin.class, service.injectedStorageProvider);
        builder.addDependency(Services.SYSTEM_CONTEXT, BundleContext.class, service.injectedSystemContext);
        builder.addDependency(Services.START_LEVEL, StartLevel.class, service.injectedStartLevel);
        builder.addDependency(Services.ENVIRONMENT, XEnvironment.class, service.injectedEnvironment);
        builder.addDependency(Services.FRAMEWORK_CREATE);
        builder.setInitialMode(Mode.ON_DEMAND);
        return builder.install();
    }

    AutoInstallIntegration() {
    }

    @Override
    public synchronized void start(StartContext context) throws StartException {
        ServiceController<?> serviceController = context.getController();
        LOGGER.tracef("Starting: %s in mode %s", serviceController.getName(), serviceController.getMode());

        final BundleContext syscontext = injectedSystemContext.getValue();
        final String startLevelProp = syscontext.getProperty(Constants.FRAMEWORK_BEGINNING_STARTLEVEL);
        final int beginningStartLevel = startLevelProp != null ? Integer.parseInt(startLevelProp) : 1;

        try {
            ServerEnvironment serverEnvironment = injectedServerEnvironment.getValue();
            bundlesDir = serverEnvironment.getBundlesDir();
            if (bundlesDir.isDirectory() == false)
                throw MESSAGES.illegalStateCannotFindBundleDir(bundlesDir);

            final List<OSGiCapability> configcaps = new ArrayList<OSGiCapability>();
            configcaps.add(new OSGiCapability("org.osgi.enterprise", null));
            configcaps.addAll(injectedSubsystemState.getValue().getCapabilities());
            Iterator<OSGiCapability> iterator = configcaps.iterator();
            while (iterator.hasNext()) {
                OSGiCapability configcap = iterator.next();
                if (installInitialModuleCapability(configcap))
                    iterator.remove();
            }

            final Set<ServiceName> resolvableServices = new LinkedHashSet<ServiceName>();
            AutoInstallComplete installComplete = new AutoInstallComplete() {

                @Override
                protected boolean allServicesAdded(Set<ServiceName> trackedServices) {
                    return configcaps.size() == trackedServices.size();
                }

                @Override
                public void start(StartContext context) throws StartException {
                    // Resolve all bundles up until and including the Framework beginning start level
                    Set<Bundle> resolvableBundles = new LinkedHashSet<Bundle>();
                    ServiceContainer serviceContainer = context.getController().getServiceContainer();
                    for (ServiceName serviceName : resolvableServices) {
                        ServiceController<?> requiredService = serviceContainer.getRequiredService(serviceName);
                        resolvableBundles.add((Bundle) requiredService.getValue());
                    }
                    Bundle[] bundleArr = resolvableBundles.toArray(new Bundle[resolvableBundles.size()]);
                    PackageAdmin packageAdmin = injectedPackageAdmin.getValue();
                    packageAdmin.resolveBundles(bundleArr);
                    super.start(context);
                }
            };
            installComplete.install(context.getChildTarget());
            ServiceListener<Bundle> listener = installComplete.getListener();

            for (OSGiCapability configcap : configcaps) {
                ServiceName serviceName = installInitialCapability(syscontext, configcap, listener);
                int startLevel = configcap.getStartLevel() != null ? configcap.getStartLevel() : 1;
                if (serviceName != null && startLevel <= beginningStartLevel) {
                    resolvableServices.add(serviceName);
                }
            }
        } catch (Exception ex) {
            throw MESSAGES.startFailedToProcessInitialCapabilites(ex);
        }
    }

    private boolean installInitialModuleCapability(OSGiCapability osgicap) throws Exception {
        String identifier = osgicap.getIdentifier();
        if (isValidModuleIdentifier(identifier)) {
            ModuleIdentifier moduleId = ModuleIdentifier.fromString(identifier);

            // Find the module in the bundles hierarchy
            File bundleFile = ModuleIdentityRepository.getRepositoryEntry(bundlesDir, moduleId);
            if (bundleFile == null) {
                LOGGER.tracef("Installing initial module capability: %s", identifier);

                // Attempt to load the module from the modules hierarchy
                final Module module;
                try {
                    ModuleLoader moduleLoader = Module.getBootModuleLoader();
                    module = moduleLoader.loadModule(moduleId);
                } catch (ModuleLoadException ex) {
                    throw MESSAGES.startFailedCannotResolveInitialCapability(ex, identifier);
                }
                if (module != null) {
                    final OSGiMetaData metadata = getModuleMetadata(module);
                    final BundleContext syscontext = injectedSystemContext.getValue();
                    XBundleRevisionBuilderFactory factory = new XBundleRevisionBuilderFactory() {
                        @Override
                        public XBundleRevision createResource() {
                            return new AbstractBundleRevisionAdaptor(syscontext, module);
                        }
                    };
                    XResourceBuilder builder = XBundleRevisionBuilderFactory.create(factory);
                    if (metadata != null) {
                        builder.loadFrom(metadata);
                    } else {
                        builder.loadFrom(module);
                    }
                    XResource resource = builder.getResource();
                    injectedEnvironment.getValue().installResources(resource);
                    return true;
                }
            }
        }
        return false;
    }

    private ServiceName installInitialCapability(BundleContext context, OSGiCapability osgicap, ServiceListener<Bundle> listener) throws Exception {
        String identifier = osgicap.getIdentifier();
        Integer level = osgicap.getStartLevel();

        ServiceName result = null;

        // Try the identifier as ModuleIdentifier
        if (isValidModuleIdentifier(identifier)) {
            ModuleIdentifier moduleId = ModuleIdentifier.fromString(identifier);

            // Attempt to install the bundle from the bundles hierarchy
            File bundleFile = ModuleIdentityRepository.getRepositoryEntry(bundlesDir, moduleId);
            if (bundleFile != null) {
                LOGGER.tracef("Installing initial bundle capability: %s", identifier);
                URL bundleURL = bundleFile.toURI().toURL();
                result = installBundleFromURL(bundleURL, identifier, level, listener);
            }
        }

        // Try the identifier as MavenCoordinates
        else if (isValidMavenIdentifier(identifier)) {
            LOGGER.tracef("Installing initial maven capability: %s", identifier);
            ServiceReference sref = context.getServiceReference(XRepository.class.getName());
            XRepository repository = (XRepository) context.getService(sref);
            MavenCoordinates mavenId = MavenCoordinates.parse(identifier);
            Requirement req = XRequirementBuilder.create(mavenId).getRequirement();
            Collection<Capability> caps = repository.findProviders(req);
            if (caps.isEmpty() == false) {
                XResource resource = (XResource) caps.iterator().next().getResource();
                XCapability ccap = (XCapability) resource.getCapabilities(ContentNamespace.CONTENT_NAMESPACE).get(0);
                URL bundleURL = new URL((String) ccap.getAttribute(ContentNamespace.CAPABILITY_URL_ATTRIBUTE));
                result = installBundleFromURL(bundleURL, identifier, level, listener);
            }
        }

        if (result == null)
            throw MESSAGES.startFailedCannotResolveInitialCapability(null, identifier);

        return result;
    }

    private boolean isValidModuleIdentifier(String identifier) {
        try {
            ModuleIdentifier.fromString(identifier);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isValidMavenIdentifier(String identifier) {
        try {
            MavenCoordinates.parse(identifier);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ServiceName installBundleFromURL(URL bundleURL, String location, Integer level, ServiceListener<Bundle> listener) throws Exception {
        BundleManager bundleManager = injectedBundleManager.getValue();
        BundleInfo info = BundleInfo.createBundleInfo(AbstractVFS.toVirtualFile(bundleURL), location);
        Deployment dep = DeploymentFactory.createDeployment(info);
        if (level != null) {
            dep.setStartLevel(level.intValue());
            dep.setAutoStart(true);
        }
        StorageStatePlugin storageProvider = injectedStorageProvider.getValue();
        StorageState storageState = storageProvider.getByLocation(dep.getLocation());
        if (storageState != null) {
            dep.addAttachment(StorageState.class, storageState);
        }
        return bundleManager.installBundle(dep, listener);
    }

    @Override
    public synchronized AutoInstallIntegration getValue() throws IllegalStateException {
        return this;
    }

    private OSGiMetaData getModuleMetadata(Module module) throws IOException {
        URL manifestURL = module.getClassLoader().getResource(JarFile.MANIFEST_NAME);
        if (manifestURL != null) {
            InputStream input = manifestURL.openStream();
            try {
                Manifest manifest = new Manifest(input);
                if (OSGiManifestBuilder.isValidBundleManifest(manifest)) {
                    return OSGiMetaDataBuilder.load(manifest);
                }
            } finally {
                input.close();
            }
        }
        File homeDir = injectedServerEnvironment.getValue().getHomeDir();
        final File modulesDir = new File(homeDir + File.separator + "modules");
        final ModuleIdentifier identifier = module.getIdentifier();

        String identifierPath = identifier.getName().replace('.', File.separatorChar) + File.separator + identifier.getSlot();
        File entryFile = new File(modulesDir + File.separator + identifierPath + File.separator + "jbosgi-xservice.properties");
        if (entryFile.exists()) {
            FileInputStream input = new FileInputStream(entryFile);
            try {
                Properties props = new Properties();
                props.load(input);
                return OSGiMetaDataBuilder.load(props);
            } finally {
                input.close();
            }
        }
        return null;
    }
}
