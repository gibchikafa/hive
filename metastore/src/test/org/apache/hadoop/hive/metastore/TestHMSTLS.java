/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.ssl.CertificateLocalization;
import org.apache.hadoop.security.ssl.CertificateLocalizationCtx;
import org.apache.hadoop.security.ssl.KeyStoreTestUtil;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivilegedExceptionAction;
import java.security.cert.X509Certificate;

public class TestHMSTLS {

  private static Path serverKeyStore, serverTrustStore;
  private static Path clientKeyStore, clientTrustStore;
  private static String outputDir;

  private static final String clientUsername = "Ring__Gandalf";
  private static final String fakeUser = "fake__user";
  private static final String password = "123456";

  private static HiveConf hiveConf;

  private HiveMetaStoreClient hmsc = null;

  @Rule
  public final ExpectedException rule = ExpectedException.none();

  @BeforeClass
  public static void setUp() throws Exception {
    outputDir = KeyStoreTestUtil.getClasspathDir(TestHMSTLS.class);

    generateCerts();
    Configuration sslServerConf = KeyStoreTestUtil.createServerSSLConfig(serverKeyStore.toString(),
        password, password, serverTrustStore.toString(), password, "");
    Path sslServerPath = Paths.get(outputDir, "ssl-server.xml");
    File sslServer = new File(sslServerPath.toUri());
    KeyStoreTestUtil.saveConfig(sslServer, sslServerConf);

    // Configure SSL
    hiveConf = new HiveConf();

    hiveConf.setVar(HiveConf.ConfVars.HIVE_SUPER_USER, UserGroupInformation.getCurrentUser().getUserName());

    hiveConf.setBoolean(CommonConfigurationKeysPublic.IPC_SERVER_SSL_ENABLED, true);
    hiveConf.addResource("ssl-server.xml");
    hiveConf.set(SSLFactory.SSL_ENABLED_PROTOCOLS, "TLSv1.2,TLSv1.1,TLSv1");
    hiveConf.set(SSLFactory.SSL_HOSTNAME_VERIFIER_KEY, "ALLOW_ALL");


    // Start Hivemetastore
    hiveConf.setClass(HiveConf.ConfVars.METASTORE_EXPRESSION_PROXY_CLASS.varname,
        MockPartitionExpressionForMetastore.class, PartitionExpressionProxy.class);
    hiveConf.setBoolVar(HiveConf.ConfVars.METASTORE_EXECUTE_SET_UGI, true);
    int msPort = MetaStoreUtils.startMetaStore(hiveConf);
    hiveConf.setVar(HiveConf.ConfVars.METASTOREURIS, "thrift://localhost:" + msPort);
    hiveConf.setVar(HiveConf.ConfVars.PREEXECHOOKS, "");
    hiveConf.setVar(HiveConf.ConfVars.POSTEXECHOOKS, "");
    hiveConf.setBoolVar(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY, false);
    hiveConf.setIntVar(HiveConf.ConfVars.METASTORETHRIFTCONNECTIONRETRIES, 1);


    /*
     * Test run in a single JVM so the CertificateLocalizationContext is shared between the Metastore and the client
     * Shouldn't cause any issue, except for the testSendCrypto in which we should see that the counter for the number of
     * instances of the client certificates is 2 (1 in the client and 1 in the metastore)
     */
    byte[] keyStore = Files.readAllBytes(clientKeyStore);
    byte[] trustStore = Files.readAllBytes(clientTrustStore);

    CertificateLocalization certificateLocalization = CertificateLocalizationCtx
        .getInstance().getCertificateLocalization();
    certificateLocalization.materializeCertificates(clientUsername, clientUsername, ByteBuffer.wrap(keyStore),
        password, ByteBuffer.wrap(trustStore), password);
    certificateLocalization.materializeCertificates(fakeUser, fakeUser, ByteBuffer.wrap(keyStore),
        password, ByteBuffer.wrap(trustStore), password);
  }

  @After
  public void closeClient() throws Exception {
    if (hmsc != null) {
      hmsc.close();
    }
  }


  @Test
  public void testClientMatchingCNUGI() throws Exception {
    UserGroupInformation ugi =
        UserGroupInformation.createProxyUser(clientUsername, UserGroupInformation.getCurrentUser());
    ugi.doAs((PrivilegedExceptionAction<Object>) () -> {
      hmsc = new HiveMetaStoreClient(hiveConf);
      return null;
    });

    CertificateLocalization certificateLocalization =
        CertificateLocalizationCtx.getInstance().getCertificateLocalization();
    // Assert is 2 as the CertificateLocalization contains the clientUsername certificate both for the client and for the
    // HMS
    Assert.assertEquals(2, certificateLocalization.getX509MaterialLocation(clientUsername).getRequestedApplications());

    hmsc.close();
    hmsc = null;
    Thread.sleep(1000);
    // Check that the counter is decremented correctly
    Assert.assertEquals(1, certificateLocalization.getX509MaterialLocation(clientUsername).getRequestedApplications());
  }

  @Test
  public void testClientNotMatchingCNUGI() throws Exception {
    UserGroupInformation ugi =
        UserGroupInformation.createProxyUser(fakeUser, UserGroupInformation.getCurrentUser());
    ugi.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() throws Exception {
        rule.expect(UndeclaredThrowableException.class);
        hmsc = new HiveMetaStoreClient(hiveConf);
        return null;
      }
    });
  }

  @Test
  public void testClientMachineCertificates() throws Exception {
    hmsc = new HiveMetaStoreClient(hiveConf);
  }

  private static void generateCerts() throws Exception {
    String keyAlg = "RSA";
    String signAlg = "SHA256withRSA";

    // Generate CA
    KeyPair caKeyPair = KeyStoreTestUtil.generateKeyPair(keyAlg);
    X509Certificate caCert = KeyStoreTestUtil.generateCertificate("CN=CARoot", caKeyPair, 42, signAlg);

    // Generate server certificate signed by CA
    KeyPair serverKeyPair = KeyStoreTestUtil.generateKeyPair(keyAlg);
    X509Certificate serverCrt = KeyStoreTestUtil.generateSignedCertificate("CN=" +
            NetUtils.getHostNameOfIP("127.0.0.1"), serverKeyPair, 42,
            signAlg, caKeyPair.getPrivate(), caCert);

    serverKeyStore = Paths.get(outputDir, "server.keystore.jks");
    serverTrustStore = Paths.get(outputDir, "server.truststore.jks");
    KeyStoreTestUtil.createKeyStore(serverKeyStore.toString(), password, password,
            "server_alias", serverKeyPair.getPrivate(), serverCrt);
    KeyStoreTestUtil.createTrustStore(serverTrustStore.toString(), password, "CARoot", caCert);

    // Generate client certificate with the correct CN field and signed by the CA
    KeyPair c_clientKeyPair = KeyStoreTestUtil.generateKeyPair(keyAlg);
    String c_cn = "CN=" + clientUsername;
    X509Certificate c_clientCrt = KeyStoreTestUtil.generateSignedCertificate(c_cn, c_clientKeyPair, 42,
            signAlg, caKeyPair.getPrivate(), caCert);

    clientKeyStore = Paths.get(outputDir, "c_client.keystore.jks");
    clientTrustStore = Paths.get(outputDir, "c_client.truststore.jks");
    KeyStoreTestUtil.createKeyStore(clientKeyStore.toString(), password, password,
            "c_client_alias", c_clientKeyPair.getPrivate(), c_clientCrt);
    KeyStoreTestUtil.createTrustStore(clientTrustStore.toString(), password, "CARoot", caCert);
  }
}