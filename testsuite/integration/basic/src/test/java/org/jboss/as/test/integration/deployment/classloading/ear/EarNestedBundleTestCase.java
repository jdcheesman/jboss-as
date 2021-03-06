/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.as.test.integration.deployment.classloading.ear;

import java.io.InputStream;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoader;
import org.jboss.osgi.spi.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * [AS7-945] Cannot deploy EAR/WAR with nested bundle
 *
 * @author thomas.diesler@jboss.com
 * @since 23-Aug-2011
 */
@RunWith(Arquillian.class)
public class EarNestedBundleTestCase {

    private static final String TEST_CLASS_NAME = EarNestedBundleTestCase.class.getName();
    private static final String OTHER_CLASS_NAME = TestAA.class.getName();

    private static final String SIMPLE_EAR = "simple.ear";
    private static final String NESTED_BUNDLE_EAR = "nested-bundle.ear";

    private ModuleLoader moduleLoader;

    @Before
    public void setUp() {
        ModuleClassLoader classLoader = (ModuleClassLoader) EarNestedBundleTestCase.class.getClassLoader();
        moduleLoader = classLoader.getModule().getModuleLoader();
    }

    @Deployment(name = SIMPLE_EAR)
    public static Archive<?> getSimpleDeployment() {
        JavaArchive jarA = ShrinkWrap.create(JavaArchive.class, "jarA.jar");
        jarA.addClass(EarNestedBundleTestCase.class);

        JavaArchive jarB = ShrinkWrap.create(JavaArchive.class, "jarB.jar");
        jarB.addClass(TestAA.class);

        EnterpriseArchive ear = ShrinkWrap.create(EnterpriseArchive.class, SIMPLE_EAR);
        ear.addAsLibrary(jarA);
        ear.addAsLibrary(jarB);
        return ear;
    }

    @Deployment(name = NESTED_BUNDLE_EAR)
    public static Archive<?> getWarDeployment() {
        JavaArchive jarA = ShrinkWrap.create(JavaArchive.class, "jarA.jar");
        jarA.addClass(EarNestedBundleTestCase.class);

        final JavaArchive bundle = ShrinkWrap.create(JavaArchive.class, "nested-bundle.jar");
        bundle.addClass(TestAA.class);
        bundle.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(bundle.getName());
                builder.addBundleManifestVersion(2);
                return builder.openStream();
            }
        });
        EnterpriseArchive ear = ShrinkWrap.create(EnterpriseArchive.class, NESTED_BUNDLE_EAR);
        ear.addAsLibrary(jarA);
        ear.addAsModule(bundle);
        return ear;
    }

    @Test
    @OperateOnDeployment(SIMPLE_EAR)
    public void testNestedJars() throws Exception {
        ModuleIdentifier earModuleId = ModuleIdentifier.fromString("deployment.simple.ear:main");
        Module earModule = moduleLoader.loadModule(earModuleId);
        Class<?> testClass = earModule.getClassLoader().loadClass(TEST_CLASS_NAME);
        ModuleClassLoader testClassLoader = (ModuleClassLoader) testClass.getClassLoader();
        Assert.assertEquals(earModuleId, testClassLoader.getModule().getIdentifier());
        Class<?> clazz = testClassLoader.loadClass(OTHER_CLASS_NAME);
        ModuleClassLoader otherClassLoader = (ModuleClassLoader) clazz.getClassLoader();
        Assert.assertEquals(earModuleId, otherClassLoader.getModule().getIdentifier());
    }

    @Test
    @OperateOnDeployment(NESTED_BUNDLE_EAR)
    public void testNestedBundle() throws Exception {
        ModuleIdentifier earModuleId = ModuleIdentifier.fromString("deployment.nested-bundle.ear:main");
        Module earModule = moduleLoader.loadModule(earModuleId);
        Class<?> testClass = earModule.getClassLoader().loadClass(TEST_CLASS_NAME);
        ModuleClassLoader testClassLoader = (ModuleClassLoader) testClass.getClassLoader();
        Assert.assertEquals(earModuleId, testClassLoader.getModule().getIdentifier());
        Class<?> clazz = testClassLoader.loadClass(OTHER_CLASS_NAME);
        ModuleClassLoader otherClassLoader = (ModuleClassLoader) clazz.getClassLoader();
        ModuleIdentifier otherModuleId = ModuleIdentifier.fromString("deployment.nested-bundle.ear.nested-bundle.jar:main");
        Assert.assertEquals(otherModuleId, otherClassLoader.getModule().getIdentifier());
    }
}
