/*
 * Copyright 2018-2020 The Developers Team.
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

package com.github.sync.cli.oauth

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Locale

import com.github.sync.cli.{CliActorSystemLifeCycle, DefaultConsoleReaderOld}
import com.github.sync.cli.oauth.OAuthParameterManager.LoginCommandConfig
import com.github.sync.http.OAuthStorageConfig
import com.github.sync.http.oauth.{OAuthStorageServiceImpl, OAuthTokenRetrieverServiceImpl}
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Integration test class for the ''OAuth'' CLI application.
  *
  * The class tests the mechanisms for executing commands and the handling of
  * unknown commands. This is achieved by directly invoking the ''main()''
  * function. Thus, only black-box testing is possible.
  */
class OAuthSpec extends AnyFlatSpec with BeforeAndAfterEach with Matchers with FileTestHelper with MockitoSugar
  with AsyncTestHelper {
  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  /**
    * Executes the test application and captures the output it prints. The
    * output is returned as a string.
    *
    * @param args the command line arguments
    * @return the output generated by the application
    */
  private def runAndCaptureOut(args: Array[String]): String = {
    val out = new ByteArrayOutputStream
    Console.withOut(out) {
      OAuth.main(args)
    }
    new String(out.toByteArray)
  }

  /**
    * Executes the test application with the given parameters and expects an
    * output indicating a failure. The output is returned as a string.
    *
    * @param args the command line arguments
    * @return the output generated by the application
    */
  private def outputOfFailedRun(args: Array[String]): String = {
    val output = runAndCaptureOut(args)
    output should not include "success"
    output
  }

  /**
    * Adds the required prefix to a command line option key.
    *
    * @param key the key of the option
    * @return the key with the correct prefix
    */
  private def cmdOpt(key: String): String = "--" + key

  /**
    * Helper function to check whether the command to initialize an IDP can be
    * executed. The command name to specify on the simulated command line is
    * passed in. This is used to test whether case is ignored.
    *
    * @param command the name of the command
    */
  private def checkExecIdpInitCommand(command: String): Unit = {
    val IdpName = "idp"
    val args = Array(command, cmdOpt(OAuthParameterManager.AuthEndpointOption),
      "https://auth.org", cmdOpt(OAuthParameterManager.TokenEndpointOption), "https://token.org",
      cmdOpt(OAuthParameterManager.ClientIDOption), "testIDP",
      cmdOpt(OAuthParameterManager.ScopeOption), "test_scope",
      cmdOpt(OAuthParameterManager.RedirectUrlOption), "https://redirect.org",
      cmdOpt(OAuthParameterManager.ClientSecretOption), "secret",
      cmdOpt(OAuthParameterManager.EncryptOption), "false",
      cmdOpt(OAuthParameterManager.StoragePathOption), testDirectory.toAbsolutePath.toString,
      cmdOpt(OAuthParameterManager.NameOption.toUpperCase(Locale.ROOT)), IdpName)

    val output = runAndCaptureOut(args)
    output should include("success")
    List(".xml", ".sec") foreach { ext =>
      val idpFile = testDirectory.resolve(IdpName + ext)
      Files.exists(idpFile) shouldBe true
    }
  }

  "OAuth" should "execute a command to create an IDP" in {
    checkExecIdpInitCommand(OAuthParameterManager.CommandInitIDP)
  }

  it should "ignore case in command names" in {
    checkExecIdpInitCommand(OAuthParameterManager.CommandInitIDP.toUpperCase(Locale.ROOT))
  }

  it should "handle an unknown command" in {
    val UnknownCommand = "just_do_it"
    val args = Array(UnknownCommand, cmdOpt(OAuthParameterManager.StoragePathOption),
      testDirectory.toAbsolutePath.toString, cmdOpt(OAuthParameterManager.NameOption), "foo")

    val output = outputOfFailedRun(args)
    output should include(UnknownCommand)
  }

  it should "execute a command to remove an IDP" in {
    val storageConfig = OAuthStorageConfig(testDirectory, "testIdp", None)
    val configFile = storageConfig.resolveFileName(OAuthStorageServiceImpl.SuffixConfigFile)
    writeFileContent(configFile, FileTestHelper.TestData)
    val args = Array(OAuthParameterManager.CommandRemoveIDP, cmdOpt(OAuthParameterManager.StoragePathOption),
      storageConfig.rootDir.toString, cmdOpt(OAuthParameterManager.NameOption), storageConfig.baseName)

    val output = runAndCaptureOut(args)
    output should include(configFile.toString)
    output should include(storageConfig.baseName)
    Files.exists(configFile) shouldBe false
  }

  it should "handle a remove command that does not remove any files" in {
    val storageConfig = OAuthStorageConfig(testDirectory, "unknownIdp", None)
    val args = Array(OAuthParameterManager.CommandRemoveIDP, cmdOpt(OAuthParameterManager.StoragePathOption),
      storageConfig.rootDir.toString, cmdOpt(OAuthParameterManager.NameOption), storageConfig.baseName)

    val output = runAndCaptureOut(args)
    output should include(storageConfig.baseName)
    output should include("no files")
  }

  it should "handle a command to log into an IDP" in {
    val ResultText = "Login successful :-)"
    val storageConfig = OAuthStorageConfig(testDirectory, "testIdp", None)
    val args = Array(OAuthParameterManager.CommandLoginIDP, cmdOpt(OAuthParameterManager.StoragePathOption),
      storageConfig.rootDir.toString, cmdOpt(OAuthParameterManager.NameOption), storageConfig.baseName,
      cmdOpt(OAuthParameterManager.PasswordOption), "password")
    val commands = mock[OAuthCommands]
    when(commands.login(any(), any(), any(), any(), any(), any())(any(), any()))
      .thenReturn(Future.successful(ResultText))

    val oauth = new OAuth(commands)
    oauth.run(args)
    val captor = ArgumentCaptor.forClass(classOf[LoginCommandConfig])
    verify(commands).login(captor.capture(), argEq(OAuthStorageServiceImpl),
      argEq(OAuthTokenRetrieverServiceImpl), any(), argEq(DefaultConsoleReaderOld), any())(any(), any())
    captor.getValue.storageConfig.baseName should be(storageConfig.baseName)
  }

  it should "report an error if superfluous parameters are passed in" in {
    val args = Array(OAuthParameterManager.CommandRemoveIDP, cmdOpt(OAuthParameterManager.StoragePathOption),
      "./some/dir", cmdOpt(OAuthParameterManager.NameOption), "test-idp", "--foo", "bar")

    val output = outputOfFailedRun(args)
    output should include("foo")
  }

  it should "print a help text if no command is specified" in {
    val SupportedCommands = List(OAuthParameterManager.CommandLoginIDP, OAuthParameterManager.CommandInitIDP,
      OAuthParameterManager.CommandRemoveIDP)

    val output = outputOfFailedRun(Array.empty)
    SupportedCommands foreach { cmd =>
      output should include(cmd)
    }
    output should not include OAuthParameterManager.StoragePathOptionName
    output should not include OAuthParameterManager.AuthEndpointOption
    output should include("--" + CliActorSystemLifeCycle.FileOption)
  }

  it should "print a help text for the login command" in {
    val args = Array(OAuthParameterManager.CommandLoginIDP, "--encrypt-idp-data", "false")
    val output = outputOfFailedRun(args)

    output should include(OAuthParameterManager.StoragePathOptionName)
    output should include(OAuthParameterManager.NameOptionName)
    output should include(OAuthParameterManager.PasswordOptionName)
    output should include(OAuthParameterManager.EncryptOptionName)
    output should not include OAuthParameterManager.AuthEndpointOption
  }

  it should "print a help text for the remove command" in {
    val output = outputOfFailedRun(Array(OAuthParameterManager.CommandRemoveIDP))

    output should include(OAuthParameterManager.StoragePathOptionName)
    output should include(OAuthParameterManager.NameOptionName)
    output should include(OAuthParameterManager.PasswordOptionName)
    output should include(OAuthParameterManager.EncryptOptionName)
    output should not include OAuthParameterManager.AuthEndpointOption
  }

  it should "print a help text for the init command" in {
    val args = Array(OAuthParameterManager.CommandInitIDP, "--encrypt-idp-data", "false",
      "--client-secret", "some-secret")
    val output = outputOfFailedRun(args)

    output should include(OAuthParameterManager.StoragePathOptionName)
    output should include(OAuthParameterManager.AuthEndpointOption)
    output should include(OAuthParameterManager.TokenEndpointOption)
    output should include(OAuthParameterManager.RedirectUrlOption)
    output should include(OAuthParameterManager.ScopeOption)
    output should include(OAuthParameterManager.ClientIDOption)
    output should include(OAuthParameterManager.ClientSecretOption)
    output should include("--" + CliActorSystemLifeCycle.FileOption)
  }
}
