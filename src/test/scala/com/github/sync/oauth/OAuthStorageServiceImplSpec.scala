/*
 * Copyright 2018-2025 The Developers Team.
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

package com.github.sync.oauth

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.FileTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.nio.file.Files
import scala.xml.SAXParseException

object OAuthStorageServiceImplSpec:
  /** Constant for the base name of a provider configuration. */
  private val BaseName = "myTestProvider"

  /** A test OAuth client secret. */
  private val ClientSecret = Secret("verySecretClient")

  /** Test token data. */
  private val TestTokens = OAuthTokenData(accessToken = "testAccessToken", refreshToken = "testRefreshToken")

  /** A test OAuth configuration. */
  private val TestConfig = IDPConfig(authorizationEndpoint = "https://test-idp.org/auth", scope = "foo bar baz",
    oauthConfig = OAuthConfig(tokenEndpoint = "https://test.idp.org/token", clientSecret = ClientSecret,
      redirectUri = "http://my-endpoint/get_code", clientID = "my-client",
      initTokenData = TestTokens))

  /**
    * Compares two ''IDPConfig'' instances. Because the contained ''Secret''s
    * do not have an ''equals()'' implementation, they have to be handled in a
    * special way.
    *
    * @param c1 configuration 1
    * @param c2 configuration 2
    * @return a flag whether these configurations are equal
    */
  private def configEquals(c1: IDPConfig, c2: IDPConfig): Boolean =
    c1.copy(oauthConfig = null) == c2.copy(oauthConfig = null) &&
      c1.oauthConfig.copy(clientSecret = null) == c2.oauthConfig.copy(clientSecret = null) &&
      c1.oauthConfig.clientSecret.secret == c2.oauthConfig.clientSecret.secret

/**
  * Test class for ''OAuthStorageServiceImpl''.
  */
class OAuthStorageServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper:
  def this() = this(ActorSystem("OAuthStorageServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  after {
    tearDownTestFile()
  }

  import OAuthStorageServiceImplSpec.*
  import system.dispatcher

  /**
    * Creates a test storage configuration with some default values. Files are
    * stored in the test folder provided by ''FileTestHelper''.
    *
    * @param baseName    the base name for files
    * @param optPassword the optional password
    */
  private def createStorageConfig(baseName: String = BaseName, optPassword: Option[String] = None) =
    SyncOAuthStorageConfig(baseName = baseName, optPassword = optPassword map (pwd => Secret(pwd)),
      rootDir = testDirectory)

  "OAuthStorageServiceImpl" should "support a round-trip with storing and loading an OAuthConfig" in {
    val storageConfig = createStorageConfig()

    val futConfig = for _ <- OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig)
                        readConfig <- OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
    yield readConfig
    futConfig map { config =>
      config should not be theSameInstanceAs(TestConfig)
      configEquals(TestConfig, config) shouldBe true
      forEvery(List("xml", "sec", "toc")) { suffix =>
        Files.exists(storageConfig.resolveFileName("." + suffix)) shouldBe true
      }
    }
  }

  it should "leave tokens and the client secret empty if undefined" in {
    val storageConfig = createStorageConfig()
    val expOAuthConfig = TestConfig.oauthConfig.copy(clientSecret = Secret(""),
      initTokenData = OAuthTokenData("", ""))
    val expConfig = TestConfig.copy(oauthConfig = expOAuthConfig)

    OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig) flatMap { _ =>
      List("sec", "toc") foreach { suffix =>
        Files.delete(storageConfig.resolveFileName("." + suffix))
      }

      OAuthStorageServiceImpl.loadIdpConfig(storageConfig) map { config =>
        configEquals(config, expConfig) shouldBe true
      }
    }
  }

  it should "handle loading an invalid OAuth configuration" in {
    val storageConfig = createStorageConfig("invalid")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      FileTestHelper.TestData)

    recoverToSucceededIf[SAXParseException] {
      OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
    }
  }

  it should "handle loading an OAuth configuration with missing properties" in {
    val xml = <oauth-config>
      <foo>test</foo>
      <invalid>true</invalid>
    </oauth-config>
    val storageConfig = createStorageConfig("noProperties")
    writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
      xml.toString())

    recoverToSucceededIf[IllegalArgumentException] {
      OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
    }
  }

  it should "handle whitespace in XML correctly" in {
    val xml = <oauth-config>
      <client-id>
        {TestConfig.oauthConfig.clientID}
      </client-id>
      <authorization-endpoint>
        {TestConfig.authorizationEndpoint}
      </authorization-endpoint>
      <token-endpoint>
        {TestConfig.oauthConfig.tokenEndpoint}
      </token-endpoint>
      <scope>
        {TestConfig.scope}
      </scope>
      <redirect-uri>
        {TestConfig.oauthConfig.redirectUri}
      </redirect-uri>
    </oauth-config>
    val storageConfig = createStorageConfig("formatted")
    OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig) flatMap { _ =>
      writeFileContent(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile),
        xml.toString())

      OAuthStorageServiceImpl.loadIdpConfig(storageConfig) map { readConfig =>
        configEquals(TestConfig, readConfig) shouldBe true
      }
    }
  }

  it should "support a round-trip with saving and loading encrypted data" in {
    val storageConfig = createStorageConfig(optPassword = Some("secure_storage"))

    val futSecret = for _ <- OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig)
                        loadedConfig <- OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
    yield loadedConfig
    futSecret map { secretConfig =>
      configEquals(secretConfig, TestConfig) shouldBe true
      val bytes = Files.readAllBytes(storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixSecretFile))
      new String(bytes) should not include ClientSecret.secret
    }
  }

  it should "handle a token file with too few tokens in it" in {
    val storageConfig = createStorageConfig()
    OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig) flatMap { _ =>
      val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
      writeFileContent(tokenFile, "foo")

      recoverToSucceededIf[IllegalArgumentException] {
        OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
      }
    }
  }

  it should "handle a token file with too many tokens in it" in {
    val storageConfig = createStorageConfig()
    OAuthStorageServiceImpl.saveIdpConfig(storageConfig, TestConfig) flatMap { _ =>
      val tokenFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
      writeFileContent(tokenFile, "foo\tbar\tbaz")

      recoverToSucceededIf[IllegalArgumentException] {
        OAuthStorageServiceImpl.loadIdpConfig(storageConfig)
      }
    }
  }

  it should "remove all files related to a storage configuration" in {
    val storageConfig = createStorageConfig()
    val FilePaths = List(OAuthStorageServiceImpl.SuffixConfigFile, OAuthStorageServiceImpl.SuffixSecretFile,
      OAuthStorageServiceImpl.SuffixTokenFile) map storageConfig.resolveFileName
    FilePaths foreach { path =>
      writeFileContent(path, FileTestHelper.TestData)
    }
    val otherFile = createDataFile()

    OAuthStorageServiceImpl.removeStorage(storageConfig) map { removeResult =>
      removeResult should contain theSameElementsAs FilePaths
      FilePaths forall (!Files.exists(_)) shouldBe true
      Files.exists(otherFile) shouldBe true
    }
  }

  it should "ignore non existing files when removing a storage configuration" in {
    val storageConfig = createStorageConfig()

    OAuthStorageServiceImpl.removeStorage(storageConfig) map { removeResult =>
      removeResult should have size 0
    }
  }

  it should "ignore directories when removing a storage configuration" in {
    val storageConfig = createStorageConfig()
    val tokenPath = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixTokenFile)
    Files.createDirectory(tokenPath)

    OAuthStorageServiceImpl.removeStorage(storageConfig) map { removeResult =>
      removeResult should have size 0
      Files.exists(tokenPath) shouldBe true
    }
  }
