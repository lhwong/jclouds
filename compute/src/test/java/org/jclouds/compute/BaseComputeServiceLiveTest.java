/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.compute;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.ComputeType;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.NodeState;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Size;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.GetNodesOptions;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.compute.predicates.NodePredicates;
import org.jclouds.domain.Credentials;
import org.jclouds.domain.Location;
import org.jclouds.domain.LocationScope;
import org.jclouds.http.HttpResponseException;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.predicates.SocketOpen;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.ssh.ExecResponse;
import org.jclouds.ssh.SshClient;
import org.jclouds.ssh.SshException;
import org.jclouds.util.Utils;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

/**
 * 
 * @author Adrian Cole
 */
@Test(groups = "live", sequential = true, testName = "compute.ComputeServiceLiveTest")
public abstract class BaseComputeServiceLiveTest {
   @BeforeClass
   abstract public void setServiceDefaults();

   protected String service;
   protected SshClient.Factory sshFactory;
   protected String tag;

   protected RetryablePredicate<InetSocketAddress> socketTester;
   protected SortedSet<NodeMetadata> nodes;
   protected ComputeServiceContext context;
   protected ComputeService client;
   protected String user;
   protected String password;
   protected Template template;
   protected Map<String, String> keyPair;

   @BeforeGroups(groups = { "live" })
   public void setupClient() throws InterruptedException, ExecutionException, TimeoutException,
            IOException {
      if (tag == null)
         tag = checkNotNull(service, "service");
      user = checkNotNull(System.getProperty("jclouds.test.user"), "jclouds.test.user");
      password = checkNotNull(System.getProperty("jclouds.test.key"), "jclouds.test.key");
      String secretKeyFile;
      try {
         secretKeyFile = checkNotNull(System.getProperty("jclouds.test.ssh.keyfile"),
                  "jclouds.test.ssh.keyfile");
      } catch (NullPointerException e) {
         secretKeyFile = System.getProperty("user.home") + "/.ssh/id_rsa";
      }
      checkSecretKeyFile(secretKeyFile);
      String secret = Files.toString(new File(secretKeyFile), Charsets.UTF_8);
      assert secret.startsWith("-----BEGIN RSA PRIVATE KEY-----") : "invalid key:\n" + secret;

      initializeContextAndClient();

      Injector injector = Guice.createInjector(getSshModule());
      sshFactory = injector.getInstance(SshClient.Factory.class);
      SocketOpen socketOpen = injector.getInstance(SocketOpen.class);
      socketTester = new RetryablePredicate<InetSocketAddress>(socketOpen, 60, 1, TimeUnit.SECONDS);
      injector.injectMembers(socketOpen); // add logger
      // keyPair = sshFactory.generateRSAKeyPair("", "");
      keyPair = ImmutableMap.<String, String> of("private", secret, "public", Files.toString(
               new File(secretKeyFile + ".pub"), Charsets.UTF_8));
   }

   private void initializeContextAndClient() throws IOException {
      if (context != null)
         context.close();
      context = new ComputeServiceContextFactory().createContext(service, user, password,
               ImmutableSet.of(new Log4JLoggingModule(), getSshModule()));
      client = context.getComputeService();
   }

   private void checkSecretKeyFile(String secretKeyFile) throws FileNotFoundException {
      Utils.checkNotEmpty(secretKeyFile,
               "System property: [jclouds.test.ssh.keyfile] set to an empty string");
      if (!new File(secretKeyFile).exists()) {
         throw new FileNotFoundException("secretKeyFile not found at: " + secretKeyFile);
      }
   }

   abstract protected Module getSshModule();

   @Test(enabled = true, expectedExceptions = AuthorizationException.class)
   public void testCorrectAuthException() throws Exception {
      new ComputeServiceContextFactory().createContext(service, "MOMMA", "MIA").close();
   }

   @Test(enabled = true, dependsOnMethods = "testCorrectAuthException")
   public void testImagesCache() throws Exception {
      client.listImages();
      long time = System.currentTimeMillis();
      client.listImages();
      long duration = System.currentTimeMillis() - time;
      assert duration < 1000 : String.format("%dms to get images", duration);
   }

   @Test(enabled = true, dependsOnMethods = "testImagesCache")
   public void testTemplateMatch() throws Exception {
      template = buildTemplate(client.templateBuilder());
      Template toMatch = client.templateBuilder().imageId(template.getImage().getId()).build();
      assertEquals(toMatch, template);
   }

   @Test(enabled = true, dependsOnMethods = "testTemplateMatch")
   public void testCreateTwoNodesWithRunScript() throws Exception {
      try {
         client.destroyNodesWithTag(tag);
      } catch (HttpResponseException e) {
         // TODO hosting.com throws 400 when we try to delete a vApp
      } catch (NoSuchElementException e) {

      }
      template = buildTemplate(client.templateBuilder());

      template.getOptions().installPrivateKey(keyPair.get("private")).authorizePublicKey(
               keyPair.get("public")).runScript(
               buildScript(template.getImage().getOsFamily()).getBytes());
      nodes = Sets.newTreeSet(client.runNodesWithTag(tag, 2, template));
      assertEquals(nodes.size(), 2);
      checkNodes(nodes, tag);
      NodeMetadata node1 = nodes.first();
      NodeMetadata node2 = nodes.last();
      // credentials aren't always the same
      // assertEquals(node1.getCredentials(), node2.getCredentials());

      assertLocationSameOrChild(node1.getLocation(), template.getLocation());
      assertLocationSameOrChild(node2.getLocation(), template.getLocation());

      assertEquals(node1.getImage(), template.getImage());
      assertEquals(node2.getImage(), template.getImage());

   }

   void assertLocationSameOrChild(Location test, Location expected) {
      if (!test.equals(expected)) {
         assertEquals(test.getParent().getId(), expected.getId());
      } else {
         assertEquals(test, expected);
      }
   }

   @Test(enabled = true, dependsOnMethods = "testCreateTwoNodesWithRunScript")
   public void testCreateAnotherNodeWithANewContextToEnsureSharedMemIsntRequired() throws Exception {
      initializeContextAndClient();
      TreeSet<NodeMetadata> nodes = Sets.newTreeSet(client.runNodesWithTag(tag, 1, template));
      checkNodes(nodes, tag);
      NodeMetadata node = nodes.first();
      this.nodes.add(node);
      assertEquals(nodes.size(), 1);
      assertLocationSameOrChild(node.getLocation(), template.getLocation());
      assertEquals(node.getImage(), template.getImage());
   }

   @Test
   public void testScriptExecutionAfterBootWithBasicTemplate() throws Exception {
      String tag = this.tag + "run";
      Template simpleTemplate = buildTemplate(client.templateBuilder());
      simpleTemplate.getOptions().blockOnPort(22, 120);
      try {
         Set<? extends NodeMetadata> nodes = client.runNodesWithTag(tag, 1, simpleTemplate);
         Credentials good = nodes.iterator().next().getCredentials();
         assert good.account != null;

         try {
            Map<NodeMetadata, ExecResponse> responses = runScriptWithCreds(tag, simpleTemplate
                     .getImage().getOsFamily(), new Credentials(good.account, "romeo"));
            assert false : "shouldn't pass with a bad password\n" + responses;
         } catch (RunScriptOnNodesException e) {
            assert Throwables.getRootCause(e).getMessage().contains("Auth fail") : e;
         }

         runScriptWithCreds(tag, simpleTemplate.getImage().getOsFamily(), good);

         checkNodes(nodes, tag);

      } finally {
         client.destroyNodesWithTag(tag);
      }
   }

   private Map<NodeMetadata, ExecResponse> runScriptWithCreds(final String tag, OsFamily osFamily,
            Credentials creds) throws RunScriptOnNodesException {
      try {
         return client.runScriptOnNodesMatching(new Predicate<NodeMetadata>() {

            @Override
            public boolean apply(NodeMetadata arg0) {
               return arg0.getState() == NodeState.RUNNING && tag.equals(arg0.getTag());
            }
         }, buildScript(osFamily).getBytes(), RunScriptOptions.Builder
                  .overrideCredentialsWith(creds));
      } catch (SshException e) {
         if (Throwables.getRootCause(e).getMessage().contains("Auth fail")) {
            System.err.printf("bad credentials: %s:%s for %s%n", creds.account, creds.key, client
                     .listNodesWithTag(tag));
         }
         throw e;
      }
   }

   protected void checkNodes(Iterable<? extends NodeMetadata> nodes, String tag) throws IOException {
      for (NodeMetadata node : nodes) {
         assertNotNull(node.getId());
         assertNotNull(node.getTag());
         assertEquals(node.getTag(), tag);
         assertEquals(node.getState(), NodeState.RUNNING);
         assert node.getPublicAddresses().size() >= 1 || node.getPrivateAddresses().size() >= 1 : "no ips in"
                  + node;
         assertNotNull(node.getCredentials());
         if (node.getCredentials().account != null) {
            assertNotNull(node.getCredentials().account);
            assertNotNull(node.getCredentials().key);
            sshPing(node);
         }
      }
   }

   protected Template buildTemplate(TemplateBuilder templateBuilder) {
      return templateBuilder.build();
   }

   protected String buildScript(OsFamily osFamily) {
      switch (osFamily) {
         case UBUNTU:
            return new StringBuilder()//
                     .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")//
                     .append("cp /etc/apt/sources.list /etc/apt/sources.list.old\n")//
                     .append(
                              "sed 's~us.archive.ubuntu.com~mirror.anl.gov/pub~g' /etc/apt/sources.list.old >/etc/apt/sources.list\n")//
                     .append("apt-get update\n")//
                     .append("apt-get install -f -y --force-yes openjdk-6-jdk\n")//
                     .append("wget -qO/usr/bin/runurl run.alestic.com/runurl\n")//
                     .append("chmod 755 /usr/bin/runurl\n")//
                     .toString();
         case CENTOS:
         case RHEL:
            return new StringBuilder()
                     .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")
                     .append("echo \"[jdkrepo]\" >> /etc/yum.repos.d/CentOS-Base.repo\n")
                     .append("echo \"name=jdkrepository\" >> /etc/yum.repos.d/CentOS-Base.repo\n")
                     .append(
                              "echo \"baseurl=http://ec2-us-east-mirror.rightscale.com/epel/5/i386/\" >> /etc/yum.repos.d/CentOS-Base.repo\n")
                     .append("echo \"enabled=1\" >> /etc/yum.repos.d/CentOS-Base.repo\n")
                     .append("yum --nogpgcheck -y install java-1.6.0-openjdk\n")
                     .append(
                              "echo \"export PATH=\\\"/usr/lib/jvm/jre-1.6.0-openjdk/bin/:\\$PATH\\\"\" >> /root/.bashrc\n")
                     .toString();
         default:
            throw new IllegalArgumentException(osFamily.toString());
      }
   }

   @Test(enabled = true, dependsOnMethods = "testCreateAnotherNodeWithANewContextToEnsureSharedMemIsntRequired")
   public void testGet() throws Exception {
      Set<? extends NodeMetadata> metadataSet = Sets.newHashSet(Iterables.filter(client
               .listNodesWithTag(tag), Predicates.not(NodePredicates.TERMINATED)));
      for (NodeMetadata node : nodes) {
         metadataSet.remove(node);
         NodeMetadata metadata = client.getNodeMetadata(node);
         assertEquals(metadata.getId(), node.getId());
         assertEquals(metadata.getTag(), node.getTag());
         assertLocationSameOrChild(metadata.getLocation(), template.getLocation());
         assertEquals(metadata.getImage(), template.getImage());
         assertEquals(metadata.getState(), NodeState.RUNNING);
         assertEquals(metadata.getPrivateAddresses(), node.getPrivateAddresses());
         assertEquals(metadata.getPublicAddresses(), node.getPublicAddresses());
      }
      assert metadataSet.size() == 0 : String.format(
               "nodes left in set: [%s] which didn't match set: [%s]", metadataSet, nodes);
   }

   @Test(enabled = true, dependsOnMethods = "testGet")
   public void testReboot() throws Exception {
      client.rebootNodesWithTag(tag);
      testGet();
   }

   @Test(enabled = true/* , dependsOnMethods = "testTemplateMatch" */)
   public void testTemplateOptions() throws Exception {
      TemplateOptions options = new TemplateOptions().withMetadata();
      Template t = client.templateBuilder().smallest().options(options).build();
      assert t.getOptions().isIncludeMetadata() : "The metadata option should be 'true' "
               + "for the created template";
   }

   public void testListNodes() throws Exception {
      for (ComputeMetadata node : client.listNodes()) {
         assert node.getId() != null;
         assert node.getLocation() != null;
         assertEquals(node.getType(), ComputeType.NODE);
      }
   }

   public void testGetNodesWithDetails() throws Exception {
      for (ComputeMetadata node : client.listNodes(new GetNodesOptions().withDetails())) {
         assert node.getId() != null : node;
         assert node.getLocation() != null : node;
         assertEquals(node.getType(), ComputeType.NODE);
         assert node instanceof NodeMetadata;
         NodeMetadata nodeMetadata = (NodeMetadata) node;
         assert nodeMetadata.getId() != null : nodeMetadata;
         // nullable
         // assert nodeMetadata.getImage() != null : node;
         // user specified name is not always supported
         // assert nodeMetadata.getName() != null : nodeMetadata;
         if (nodeMetadata.getState() != NodeState.TERMINATED) {
            assert nodeMetadata.getPublicAddresses() != null : nodeMetadata;
            assert nodeMetadata.getPublicAddresses().size() > 0
                     || nodeMetadata.getPrivateAddresses().size() > 0 : nodeMetadata;
            assertNotNull(nodeMetadata.getPrivateAddresses());
         }
      }
   }

   public void testListImages() throws Exception {
      for (Image image : client.listImages()) {
         assert image.getId() != null : image;
         // image.getLocationId() can be null, if it is a location-free image
         assertEquals(image.getType(), ComputeType.IMAGE);
      }
   }

   @Test(groups = { "integration", "live" })
   public void testGetAssignableLocations() throws Exception {
      for (Location location : client.listAssignableLocations()) {
         System.err.printf("location %s%n", location);
         assert location.getId() != null : location;
         assert location != location.getParent() : location;
         assert location.getScope() != null : location;
         switch (location.getScope()) {
            case PROVIDER:
               assertProvider(location);
               break;
            case REGION:
               assertProvider(location.getParent());
               break;
            case ZONE:
               Location provider = location.getParent().getParent();
               // zone can be a direct descendant of provider
               if (provider == null)
                  provider = location.getParent();
               assertProvider(provider);
               break;
            case HOST:
               Location provider2 = location.getParent().getParent().getParent();
               // zone can be a direct descendant of provider
               if (provider2 == null)
                  provider2 = location.getParent().getParent();
               assertProvider(provider2);
               break;
         }
      }
   }

   private void assertProvider(Location provider) {
      assertEquals(provider.getScope(), LocationScope.PROVIDER);
      assertEquals(provider.getParent(), null);
   }

   public void testListSizes() throws Exception {
      for (Size size : client.listSizes()) {
         assert size.getId() != null;
         assert size.getCores() > 0;
         assert size.getDisk() > 0;
         assert size.getRam() > 0;
         assert size.getSupportedArchitectures() != null;
         assertEquals(size.getType(), ComputeType.SIZE);
      }
   }

   private void sshPing(NodeMetadata node) throws IOException {
      for (int i = 0; i < 5; i++) {// retry loop TODO replace with predicate.
         try {
            doCheckJavaIsInstalledViaSsh(node);
            return;
         } catch (SshException e) {
            try {
               Thread.sleep(10 * 1000);
            } catch (InterruptedException e1) {
            }
            continue;
         }
      }
   }

   protected void doCheckJavaIsInstalledViaSsh(NodeMetadata node) throws IOException {
      InetSocketAddress socket = new InetSocketAddress(Iterables.get(node.getPublicAddresses(), 0),
               22);
      socketTester.apply(socket); // TODO add transitionTo option that accepts a socket conection
      // state.
      SshClient ssh = sshFactory.create(socket, node.getCredentials().account, keyPair.get(
               "private").getBytes());
      try {
         ssh.connect();
         ExecResponse hello = ssh.exec("echo hello");
         assertEquals(hello.getOutput().trim(), "hello");
         ExecResponse exec = ssh.exec("java -version");
         assert exec.getError().indexOf("OpenJDK") != -1 : exec;
      } finally {
         if (ssh != null)
            ssh.disconnect();
      }
   }

   @AfterTest
   protected void cleanup() throws InterruptedException, ExecutionException, TimeoutException {
      if (nodes != null) {
         client.destroyNodesWithTag(tag);
         for (NodeMetadata node : client.listNodesWithTag(tag)) {
            assert node.getState() == NodeState.TERMINATED : node;
         }
      }
      context.close();
   }

}
