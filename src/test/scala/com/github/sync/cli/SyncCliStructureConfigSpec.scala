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

package com.github.sync.cli

import com.github.scli.ParameterExtractor.{ExtractionContext, Parameters}
import com.github.scli.{ConsoleReader, DummyConsoleReader, ParameterExtractor, ParameterParser}
import com.github.sync.cli.ExtractorTestHelper.toExtractionContext
import com.github.sync.cli.SyncCliStructureConfig._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.http.{SyncAuthConfig, SyncBasicAuthConfig, SyncNoAuth, SyncOAuthStorageConfig}
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, OneDriveStructureConfig}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.ZoneId
import scala.util.{Failure, Success, Try}

object SyncCliStructureConfigSpec {
  /** Test user name. */
  private val User = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test last-modified property. */
  private val LastModifiedProperty = "lastModifiedProp"

  /** Test namespace for the last-modified property. */
  private val LastModifiedNamespace = "testNamespace"

  /** Test storage path. */
  private val StoragePath = "/data/idp-providers"

  /** Test IDP name. */
  private val IdpName = "MyTestIDP"

  /** A test URI. */
  private val TestUri = "https://test.server.svr"

  /**
    * Converts the given simple key-value map to a ''Parameters'' object. The
    * input parameters are added as well. This simplifies the generation of
    * parameters in test cases.
    *
    * @param argsMap   the simple map with arguments
    * @param urlParams the list with URLs passed as input parameters
    * @return the ''Parameters'' object
    */
  private def toParameters(argsMap: Map[String, String], urlParams: List[String]): Parameters = {
    val allArgs = ExtractorTestHelper.toParametersMap(argsMap) + (ParameterParser.InputParameter.key -> urlParams)
    ExtractorTestHelper.toParameters(allArgs)
  }

  /**
    * Generates the values of the input parameters option. The option contains
    * the URLs for the source and destination structures. Depending on the role
    * type, the passed in URL must be set to the correct position.
    *
    * @param structureUrl the URL for the structure
    * @param roleType     the role type
    * @return the resulting input parameter values
    */
  private def createUrlParameter(structureUrl: String, roleType: RoleType): List[String] =
    roleType match {
      case SourceRoleType =>
        List(structureUrl, "ignored")
      case DestinationRoleType =>
        List("ignored", structureUrl)
    }

  /**
    * Executes the extractor for the structure config against the parameters
    * specified and returns the result.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @param optReader    an optional ''ConsoleReader''
    * @return the result returned by the extractor
    */
  private def runConfigExtractor(args: Map[String, String], structureUrl: String, roleType: RoleType,
                                 optReader: Option[ConsoleReader] = None):
  (Try[StructureAuthConfig], ExtractionContext) = {
    val reader = optReader getOrElse DummyConsoleReader
    val paramCtx = toExtractionContext(toParameters(args, createUrlParameter(structureUrl, roleType)),
      reader = reader)
    ParameterExtractor.runExtractor(SyncCliStructureConfig.structureConfigExtractor(roleType, "uri"),
      paramCtx)
  }

  /**
    * Executes the extractor for the structure config against the parameters
    * specified and expects a success result. The resulting configuration is
    * returned. In case of a failure, the test fails.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @param optReader    an optional ''ConsoleReader''
    * @return the success result returned by the extractor
    */
  private def extractConfig(args: Map[String, String], structureUrl: String, roleType: RoleType,
                            optReader: Option[ConsoleReader] = None): (StructureAuthConfig, ExtractionContext) = {
    val (triedConfig, nextContext) = runConfigExtractor(args, structureUrl, roleType, optReader)
    triedConfig match {
      case Success(config) => (config, nextContext)
      case Failure(exception) =>
        throw new AssertionError("Failed to extract structure config", exception)
    }
  }

  /**
    * Executes the extractor for the structure config against the parameters
    * specified and expects a failure result. The exception is returned. An
    * unexpected success result causes the test to fail.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @return the exception and the updated extraction context
    */
  private def expectFailure(args: Map[String, String], structureUrl: String, roleType: RoleType):
  (Throwable, ExtractionContext) = {
    val (triedConfig, nextContext) = runConfigExtractor(args, structureUrl, roleType)
    triedConfig match {
      case Failure(exception) => (exception, nextContext)
      case Success(value) =>
        throw new AssertionError("Unexpected success result: " + value)
    }
  }
}

/**
  * Test class for ''SyncStructureConfig''.
  */
class SyncCliStructureConfigSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import SyncCliStructureConfigSpec._

  /**
    * Checks whether the set of parameters accessed by the extractors for the
    * structure type config contains all the option names of the passed in set.
    *
    * @param context   the extraction context
    * @param expParams the set with expected option names
    */
  private def checkAccessedParameters(context: ExtractionContext, expParams: Set[String]): Unit = {
    val accessedParams = expParams + ParameterParser.InputParameter.key
    ExtractorTestHelper.accessedKeys(context) should contain theSameElementsAs accessedParams
  }

  /**
    * Checks whether the passed in parameter keys have been accessed by the
    * extractors for the structure type config.
    *
    * @param context   the extraction context
    * @param roleType  the role type
    * @param expParams the names of the expected parameters
    */
  private def checkAccessedParameters(context: ExtractionContext, roleType: RoleType, expParams: String*): Unit = {
    val accessedParams = expParams.map(roleType.configPropertyName).toSet
    checkAccessedParameters(context, accessedParams)
  }

  /**
    * Checks whether the given auth configuration is for basic auth with the
    * expected properties.
    *
    * @param authConfig the auth config to be checked
    */
  private def checkBasicAuthConfig(authConfig: SyncAuthConfig): Unit = {
    val basicAuthConfig = authConfig.asInstanceOf[SyncBasicAuthConfig]
    basicAuthConfig.user should be(User)
    basicAuthConfig.password.secret should be(Password)
  }

  "SyncStructureConfig" should "create a correct file system config for the source structure" in {
    val TimeZoneId = "UTC+02:00"
    val uri = "/my/sync/dir"
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropLocalFsTimeZone) ->
      TimeZoneId)

    val (config, processedArgs) = extractConfig(args, uri, SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncCliStructureConfig.PropLocalFsTimeZone)
    config.structureConfig should be(FsStructureConfig(Some(ZoneId.of(TimeZoneId))))
    config.authConfig should be(SyncNoAuth)
  }

  it should "create a correct file system config for the source structure with defaults" in {
    val uri = "/my/sync/dir"

    val (config, processedArgs) = extractConfig(Map.empty, uri, SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncCliStructureConfig.PropLocalFsTimeZone)
    config.structureConfig should be(FsStructureConfig(None))
    config.authConfig should be(SyncNoAuth)
  }

  it should "generate a failure for invalid parameters of a local FS config" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropLocalFsTimeZone) ->
      "invalid zone ID!")

    val (exception, processedArgs) = expectFailure(args, "/some/folder", SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncCliStructureConfig.PropLocalFsTimeZone)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncCliStructureConfig.PropLocalFsTimeZone))
  }

  it should "create a correct file system config for the destination structure" in {
    val TimeZoneId = "UTC-02:00"
    val uri = "/my/sync/target"
    val args = Map(DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropLocalFsTimeZone) ->
      TimeZoneId)

    val (config, processedArgs) = extractConfig(args, uri, DestinationRoleType)
    checkAccessedParameters(processedArgs, DestinationRoleType, SyncCliStructureConfig.PropLocalFsTimeZone)
    config.structureConfig should be(FsStructureConfig(Some(ZoneId.of(TimeZoneId))))
    config.authConfig should be(SyncNoAuth)
  }

  it should "create a correct DavConfig for the source structure if all basic auth properties are defined" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavDeleteBeforeOverride) -> "true")

    val (config, processedArgs) =
      extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config.structureConfig match {
      case davConfig: DavStructureConfig =>
        davConfig.optLastModifiedProperty should be(Some(LastModifiedProperty))
        davConfig.optLastModifiedNamespace should be(Some(LastModifiedNamespace))
        davConfig.deleteBeforeOverride shouldBe true
        checkBasicAuthConfig(config.authConfig)
      case c => fail("Unexpected result: " + c)
    }
  }

  it should "create a correct DavConfig for the source structure with defaults" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password)

    val (config, _) =
      extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    config.structureConfig match {
      case davConfig: DavStructureConfig =>
        davConfig.optLastModifiedProperty should be(None)
        davConfig.optLastModifiedNamespace should be(None)
        davConfig.deleteBeforeOverride shouldBe false
      case c => fail("Unexpected result: " + c)
    }
  }

  it should "generate a failure for invalid parameters of a DavConfig" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavDeleteBeforeOverride) -> "xx",
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace)

    val (exception, _) = expectFailure(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncCliStructureConfig.PropDavDeleteBeforeOverride))
  }

  it should "create a correct DavConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavDeleteBeforeOverride) -> "true",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOption) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOption) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOption) -> Password
    )
    val expAccessedKeys = args.keySet + ParameterParser.InputParameter.key +
      SourceRoleType.configPropertyName(OAuthParameterManager.EncryptOption) +
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser)

    val (config, processedArgs) = extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    ExtractorTestHelper.accessedKeys(processedArgs) should be(expAccessedKeys)
    val oauthConfig = config.authConfig.asInstanceOf[SyncOAuthStorageConfig]
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "fail parsing parameters if properties for both basic auth and OAuth are set" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropDavDeleteBeforeOverride) -> "true",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOption) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOption) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOption) -> Password,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password
    )

    val (_, context) = extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    val notAccessedKeys = context.parameters.notAccessedKeys map (_.key)
    notAccessedKeys should contain allOf(SourceRoleType.configPropertyName(
      OAuthParameterManager.NameOption), SourceRoleType.configPropertyName(
      OAuthParameterManager.PasswordOption))
  }

  it should "create a correct DavConfig for the destination structure" in {
    val args = Map(DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedProperty) ->
        LastModifiedProperty,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) ->
        LastModifiedNamespace,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropDavDeleteBeforeOverride) -> "true")

    val (config, processedArgs) =
      extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, DestinationRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config.structureConfig match {
      case davConfig: DavStructureConfig =>
        checkBasicAuthConfig(config.authConfig)
        davConfig.optLastModifiedProperty should be(Some(LastModifiedProperty))
        davConfig.optLastModifiedNamespace should be(Some(LastModifiedNamespace))
        davConfig.deleteBeforeOverride shouldBe true
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "read the Auth password from the console if it is not specified" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User)
    val Password = "$ecretPwd"
    val reader = mock[ConsoleReader]
    val propPwd = SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword)
    when(reader.readOption(propPwd, password = true)).thenReturn(Password)

    val (config, processedCtx) = extractConfig(args, SyncCliStructureConfig.PrefixWebDav + TestUri, SourceRoleType,
      optReader = Some(reader))
    ExtractorTestHelper.accessedKeys(processedCtx) should contain(propPwd)
    val authConfig = config.authConfig.asInstanceOf[SyncBasicAuthConfig]
    authConfig.password.secret should be(Password)
  }

  it should "create a correct OneDriveConfig for the source structure with basic auth properties" in {
    val ChunkSize = 42
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveServer) -> TestUri,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDrivePath) -> StoragePath,
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveUploadChunkSize) -> ChunkSize.toString)

    val (config, processedArgs) = extractConfig(args, SyncCliStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config.structureConfig match {
      case oneConfig: OneDriveStructureConfig =>
        oneConfig.optUploadChunkSizeMB should be(Some(ChunkSize))
        oneConfig.syncPath should be(StoragePath)
        oneConfig.optServerUri should be(Some(TestUri))
        checkBasicAuthConfig(config.authConfig)
      case r => fail("Unexpected config " + r)
    }
  }

  it should "create a correct OneDriveConfig for the source structure with defaults" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDrivePath) -> StoragePath)
    val ExpConfig = OneDriveStructureConfig(syncPath = StoragePath, optServerUri = None,
      optUploadChunkSizeMB = None)

    val (config, _) = extractConfig(args, SyncCliStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    config.structureConfig should be(ExpConfig)
    config.authConfig should be(SyncNoAuth)
  }

  it should "generate a failure for invalid properties of a OneDrive configuration" in {
    val args = Map(SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveUploadChunkSize) -> "xx")

    val (exception, _) = expectFailure(args, SyncCliStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncCliStructureConfig.PropOneDriveUploadChunkSize))
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncCliStructureConfig.PropOneDrivePath))
  }

  it should "create a correct OneDriveConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDrivePath) -> "/a-path",
      SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveUploadChunkSize) -> "4",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOption) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOption) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOption) -> Password
    )
    val expAccessedKeys = args.keySet ++
      Set(SourceRoleType.configPropertyName(OAuthParameterManager.EncryptOption),
        SourceRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveServer),
        SourceRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser),
        SourceRoleType.configPropertyName(OAuthParameterManager.EncryptOption))
    val (config, processedArgs) = extractConfig(args, SyncCliStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, expAccessedKeys)
    val oauthConfig = config.authConfig.asInstanceOf[SyncOAuthStorageConfig]
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "create a correct OneDriveConfig for the destination structure" in {
    val ChunkSize = 11
    val args = Map(DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropAuthUser) -> User,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropAuthPassword) -> Password,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveServer) -> TestUri,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropOneDrivePath) -> StoragePath,
      DestinationRoleType.configPropertyName(SyncCliStructureConfig.PropOneDriveUploadChunkSize) ->
        ChunkSize.toString)

    val (config, processedArgs) =
      extractConfig(args, SyncCliStructureConfig.PrefixOneDrive + TestUri, DestinationRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config.structureConfig match {
      case oneConfig: OneDriveStructureConfig =>
        oneConfig.optUploadChunkSizeMB should be(Some(ChunkSize))
        oneConfig.syncPath should be(StoragePath)
        oneConfig.optServerUri should be(Some(TestUri))
        checkBasicAuthConfig(config.authConfig)
      case r => fail("Unexpected config " + r)
    }
  }
}
