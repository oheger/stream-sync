/*
 * Copyright 2018-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.github.sync.http.oauth

import java.nio.file.Files

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.github.sync.crypt.Secret
import com.github.sync.http.OAuthStorageConfig
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.xml.SAXParseException

object OAuthStorageServiceImplSpec {
  /** A test OAuth configuration. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = "https://test-idp.org/auth",
    tokenEndpoint = "https://test.idp.org/token", scope = "foo bar baz",
    redirectUri = "http://my-endpoint/get_code", clientID = "my-client")

  /** Constant for the base name of a provider configuration. */
  private val BaseName = "myTestProvider"

  /** A test OAuth client secret. */
  private val ClientSecret = Secret("verySecretClient")

  /** Test token data. */
  private val TestTokens = OAuthTokenData(accessToken = "testAccessToken", refreshToken = "testRefreshToken")
}

/**
  * Test class for ''OAuthStorageServiceImpl''.
  */
class OAuthStorageServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with AsyncTestHelper with FileTestHelper {
  def this() = this(ActorSystem("OAuthStorageServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  after {
    tearDownTestFile()
  }

  import OAuthStorageServiceImplSpec._
  import system.dispatcher

  /**
    * Creates a test storage configuration with some default values. Files are
    * stored in the test folder provided by ''FileTestHelper''.
    *
    * @param baseName    the base name for files
    * @param optPassword the optional password
    */
  private def createStorageConfig(baseName: String = BaseName, optPassword: Option[String] = None) =
    OAuthStorageConfig(baseName = baseName, optPassword = optPassword map (pwd => Secret(pwd)),
      rootDir = testDirectory)

  "OAuthStorageServiceImpl" should "support a round-trip with storing and loading an OAuthConfig" in {
    val storageConfig = createStorageConfig()

    val futConfig = for {_ <- OAuthStorageServiceImpl.saveConfig(storageConfig, TestConfig)
                         readConfig <- OAuthStorageServiceImpl.loadConfig(storageConfig)
                         } yield readConfig
    val config = futureResult(futConfig)
    config should not be theSameInstanceAs(TestConfig)
    config should be(TestConfig)
    Files.exists(storageConfig.resolveFileName(".xml")) shouldBe true
  }

  it should "handle loading an invalid OAuth configuration" in {
    val storageConfig = createStorageConfig("invalid")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      FileTestHelper.TestData)

    expectFailedFuture[SAXParseException](OAuthStorageServiceImpl.loadConfig(storageConfig))
  }

  it should "handle loading an OAuth configuration with missing properties" in {
    val xml = <oauth-config>
      <foo>test</foo>
      <invalid>true</invalid>
    </oauth-config>
    val storageConfig = createStorageConfig("noProperties")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      xml.toString())

    expectFailedFuture[IllegalArgumentException](OAuthStorageServiceImpl.loadConfig(storageConfig))
  }

  it should "handle whitespace in XML correctly" in {
    val xml = <oauth-config>
      <client-id>
        {TestConfig.clientID}
      </client-id>
      <authorization-endpoint>
        {TestConfig.authorizationEndpoint}
      </authorization-endpoint>
      <token-endpoint>
        {TestConfig.tokenEndpoint}
      </token-endpoint>
      <scope>
        {TestConfig.scope}
      </scope>
      <redirect-uri>
        {TestConfig.redirectUri}
      </redirect-uri>
    </oauth-config>
    val storageConfig = createStorageConfig("formatted")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      xml.toString())

    val readConfig = futureResult(OAuthStorageServiceImpl.loadConfig(storageConfig))
    readConfig should be(TestConfig)
  }

  it should "support a round-trip with saving and loading a client secret" in {
    val storageConfig = createStorageConfig()

    val futSecret = for {_ <- OAuthStorageServiceImpl.saveClientSecret(storageConfig, ClientSecret)
                         loadedSecret <- OAuthStorageServiceImpl.loadClientSecret(storageConfig)
                         } yield loadedSecret
    val secret = futureResult(futSecret)
    secret should not be theSameInstanceAs(ClientSecret)
    secret.secret should be(ClientSecret.secret)
    val content = readDataFile(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixSecretFile))
    content should include(ClientSecret.secret)
  }

  it should "support a round-trip with saving and loading an encrypted secret" in {
    val storageConfig = createStorageConfig(optPassword = Some("secure_storage"))

    val futSecret = for {_ <- OAuthStorageServiceImpl.saveClientSecret(storageConfig, ClientSecret)
                         loadedSecret <- OAuthStorageServiceImpl.loadClientSecret(storageConfig)
                         } yield loadedSecret
    val secret = futureResult(futSecret)
    secret should not be theSameInstanceAs(ClientSecret)
    secret.secret should be(ClientSecret.secret)
    val bytes = Files.readAllBytes(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixSecretFile))
    new String(bytes) should not include ClientSecret.secret
  }

  it should "support a round-trip with saving and loading token information" in {
    val storageConfig = createStorageConfig()

    val futTokens = for {_ <- OAuthStorageServiceImpl.saveTokens(storageConfig, TestTokens)
                         loadedTokens <- OAuthStorageServiceImpl.loadTokens(storageConfig)
                         } yield loadedTokens
    val tokens = futureResult(futTokens)
    tokens should not be theSameInstanceAs(TestTokens)
    tokens should be(TestTokens)
    val content = readDataFile(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile))
    content should include(TestTokens.accessToken)
    content should include(TestTokens.refreshToken)
  }

  it should "handle a token file with too few tokens in it" in {
    val storageConfig = createStorageConfig()
    val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    writeFileContent(tokenFile, "foo")

    expectFailedFuture[IllegalArgumentException](OAuthStorageServiceImpl.loadTokens(storageConfig))
  }

  it should "handle a token file with too many tokens in it" in {
    val storageConfig = createStorageConfig()
    val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    writeFileContent(tokenFile, "foo\tbar\tbaz")

    expectFailedFuture[IllegalArgumentException](OAuthStorageServiceImpl.loadTokens(storageConfig))
  }

  it should "support a round-trip with saving and loading encrypted token information" in {
    val storageConfig = createStorageConfig(optPassword = Some("secret_tokens"))

    val futTokens = for {_ <- OAuthStorageServiceImpl.saveTokens(storageConfig, TestTokens)
                         loadedTokens <- OAuthStorageServiceImpl.loadTokens(storageConfig)
                         } yield loadedTokens
    val tokens = futureResult(futTokens)
    tokens should be(TestTokens)
    val bytes = Files.readAllBytes(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile))
    val encContent = new String(bytes)
    encContent should not include TestTokens.accessToken
    encContent should not include TestTokens.refreshToken
  }

  it should "remove all files related to a storage configuration" in {
    val storageConfig = createStorageConfig()
    val FilePaths = List(OAuthStorageServiceImpl.SuffixConfigFile, OAuthStorageServiceImpl.SuffixSecretFile,
      OAuthStorageServiceImpl.SuffixTokenFile) map storageConfig.resolveFileName
    FilePaths foreach { path =>
      writeFileContent(path, FileTestHelper.TestData)
    }
    val otherFile = createDataFile()

    val removeResult = futureResult(OAuthStorageServiceImpl.removeStorage(storageConfig))
    removeResult should contain theSameElementsAs FilePaths
    FilePaths forall (!Files.exists(_)) shouldBe true
    Files.exists(otherFile) shouldBe true
  }

  it should "ignore non existing files when removing a storage configuration" in {
    val storageConfig = createStorageConfig()

    val removeResult = futureResult(OAuthStorageServiceImpl.removeStorage(storageConfig))
    removeResult should have size 0
  }

  it should "ignore directories when removing a storage configuration" in {
    val storageConfig = createStorageConfig()
    val tokenPath = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    Files.createDirectory(tokenPath)

    val removeResult = futureResult(OAuthStorageServiceImpl.removeStorage(storageConfig))
    removeResult should have size 0
    Files.exists(tokenPath) shouldBe true
  }
}
