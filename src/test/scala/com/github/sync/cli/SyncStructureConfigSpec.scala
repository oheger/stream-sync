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

package com.github.sync.cli

import java.time.ZoneId

import com.github.sync.cli.ParameterManager.{ParameterContext, Parameters}
import com.github.sync.cli.SyncStructureConfig._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.http.{AuthConfig, BasicAuthConfig, NoAuth, OAuthStorageConfig}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.util.{Failure, Success, Try}

object SyncStructureConfigSpec {
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
  private def toParameters(argsMap: Map[String, String], urlParams: List[String]): Parameters =
    argsMap.map(e => (e._1, List(e._2))) + (ParameterManager.InputOption -> urlParams)

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
    * Executes the processor for the structure config against the parameters
    * specified and returns the result.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @param optReader    an optional ''ConsoleReader''
    * @return the result returned by the processor
    */
  private def runConfigProcessor(args: Map[String, String], structureUrl: String, roleType: RoleType,
                                 optReader: Option[ConsoleReader] = None): (Try[StructureConfig], ParameterContext) = {
    val paramCtx = toParameters(args, createUrlParameter(structureUrl, roleType))
    val reader = optReader getOrElse DummyConsoleReader
    ParameterManager.runProcessor(SyncStructureConfig.structureConfigProcessor(roleType, "uri"),
      paramCtx)(reader)
  }

  /**
    * Executes the processor for the structure config against the parameters
    * specified and expects a success result. The resulting configuration is
    * returned. In case of a failure, the test fails.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @param optReader    an optional ''ConsoleReader''
    * @return the success result returned by the processor
    */
  private def extractConfig(args: Map[String, String], structureUrl: String, roleType: RoleType,
                            optReader: Option[ConsoleReader] = None): (StructureConfig, ParameterContext) = {
    val (triedConfig, nextContext) = runConfigProcessor(args, structureUrl, roleType, optReader)
    triedConfig match {
      case Success(config) => (config, nextContext)
      case Failure(exception) =>
        throw new AssertionError("Failed to extract structure config", exception)
    }
  }

  /**
    * Executes the processor for the structure config against the parameters
    * specified and expects a failure result. The exception is returned. An
    * unexpected success result causes the test to fail.
    *
    * @param args         the map with arguments
    * @param structureUrl the URL to be used for the structure
    * @param roleType     the role type of the structure
    * @return the exception and the updated parameters
    */
  private def expectFailure(args: Map[String, String], structureUrl: String, roleType: RoleType):
  (Throwable, ParameterContext) = {
    val (triedConfig, nextContext) = runConfigProcessor(args, structureUrl, roleType)
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
class SyncStructureConfigSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import SyncStructureConfigSpec._

  /**
    * Checks whether the set of parameters accessed by the processors for the
    * structure type config contains all the option names of the passed in set.
    *
    * @param paramCtx  the parameter context
    * @param expParams the set with expected option names
    */
  private def checkAccessedParameters(paramCtx: ParameterContext, expParams: Set[String]): Unit = {
    val accessedParams = expParams + ParameterManager.InputOption
    paramCtx.parameters.accessedParameters should contain theSameElementsAs accessedParams
  }

  /**
    * Checks whether the passed in parameter keys have been accessed by the
    * processors for the structure type config.
    *
    * @param paramCtx  the parameter context
    * @param roleType  the role type
    * @param expParams the names of the expected parameters
    */
  private def checkAccessedParameters(paramCtx: ParameterContext, roleType: RoleType, expParams: String*): Unit = {
    val accessedParams = expParams.map(roleType.configPropertyName).toSet
    checkAccessedParameters(paramCtx, accessedParams)
  }

  /**
    * Checks whether the given auth configuration is for basic auth with the
    * expected properties.
    *
    * @param authConfig the auth config to be checked
    */
  private def checkBasicAuthConfig(authConfig: AuthConfig): Unit = {
    val basicAuthConfig = authConfig.asInstanceOf[BasicAuthConfig]
    basicAuthConfig.user should be(User)
    basicAuthConfig.password.secret should be(Password)
  }

  "SyncStructureConfig" should "create a correct file system config for the source structure" in {
    val TimeZoneId = "UTC+02:00"
    val uri = "/my/sync/dir"
    val args = Map(SourceRoleType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      TimeZoneId)

    val (config, processedArgs) = extractConfig(args, uri, SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncStructureConfig.PropLocalFsTimeZone)
    config should be(FsStructureConfig(Some(ZoneId.of(TimeZoneId))))
  }

  it should "create a correct file system config for the source structure with defaults" in {
    val uri = "/my/sync/dir"

    val (config, processedArgs) = extractConfig(Map.empty, uri, SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncStructureConfig.PropLocalFsTimeZone)
    config should be(FsStructureConfig(None))
  }

  it should "generate a failure for invalid parameters of a local FS config" in {
    val args = Map(SourceRoleType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      "invalid zone ID!")

    val (exception, processedArgs) = expectFailure(args, "/some/folder", SourceRoleType)
    checkAccessedParameters(processedArgs, SourceRoleType, SyncStructureConfig.PropLocalFsTimeZone)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncStructureConfig.PropLocalFsTimeZone))
  }

  it should "create a correct file system config for the destination structure" in {
    val TimeZoneId = "UTC-02:00"
    val uri = "/my/sync/target"
    val args = Map(DestinationRoleType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      TimeZoneId)

    val (config, processedArgs) = extractConfig(args, uri, DestinationRoleType)
    checkAccessedParameters(processedArgs, DestinationRoleType, SyncStructureConfig.PropLocalFsTimeZone)
    config should be(FsStructureConfig(Some(ZoneId.of(TimeZoneId))))
  }

  it should "create a correct DavConfig for the source structure if all basic auth properties are defined" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavDeleteBeforeOverride) -> "true")

    val (config, processedArgs) =
      extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config match {
      case davConfig: DavStructureConfig =>
        davConfig.optLastModifiedProperty should be(Some(LastModifiedProperty))
        davConfig.optLastModifiedNamespace should be(Some(LastModifiedNamespace))
        davConfig.deleteBeforeOverride shouldBe true
        checkBasicAuthConfig(davConfig.authConfig)
      case c => fail("Unexpected result: " + c)
    }
  }

  it should "create a correct DavConfig for the source structure with defaults" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password)

    val (config, _) =
      extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    config match {
      case davConfig: DavStructureConfig =>
        davConfig.optLastModifiedProperty should be(None)
        davConfig.optLastModifiedNamespace should be(None)
        davConfig.deleteBeforeOverride shouldBe false
      case c => fail("Unexpected result: " + c)
    }
  }

  it should "generate a failure for invalid parameters of a DavConfig" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropDavDeleteBeforeOverride) -> "xx",
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace)

    val (exception, _) = expectFailure(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncComponentsFactory.PropDavDeleteBeforeOverride))
  }

  it should "create a correct DavConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavDeleteBeforeOverride) -> "true",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password
    )
    val expAccessedKeys = args.keySet + ParameterManager.InputOption +
      SourceRoleType.configPropertyName(OAuthParameterManager.EncryptOptionName) +
      SourceRoleType.configPropertyName(SyncComponentsFactory.PropDavUser)

    val (config, processedArgs) = extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    processedArgs.parameters.accessedParameters should be(expAccessedKeys)
    val oauthConfig = config.asInstanceOf[DavStructureConfig].authConfig.asInstanceOf[OAuthStorageConfig]
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "fail parsing parameters if properties for both basic auth and OAuth are set" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropDavDeleteBeforeOverride) -> "true",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password
    )

    val (_, params) = extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType)
    params.parameters.notAccessedKeys should contain allOf(SourceRoleType.configPropertyName(
      OAuthParameterManager.NameOptionName), SourceRoleType.configPropertyName(
      OAuthParameterManager.PasswordOptionName))
  }

  it should "create a correct DavConfig for the destination structure" in {
    val args = Map(DestinationRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedProperty) ->
        LastModifiedProperty,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) ->
        LastModifiedNamespace,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropDavDeleteBeforeOverride) -> "true")

    val (config, processedArgs) =
      extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, DestinationRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config match {
      case davConfig: DavStructureConfig =>
        checkBasicAuthConfig(davConfig.authConfig)
        davConfig.optLastModifiedProperty should be(Some(LastModifiedProperty))
        davConfig.optLastModifiedNamespace should be(Some(LastModifiedNamespace))
        davConfig.deleteBeforeOverride shouldBe true
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "read the Auth password from the console if it is not specified" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User)
    val Password = "$ecretPwd"
    val reader = mock[ConsoleReader]
    val propPwd = SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword)
    when(reader.readOption(propPwd, password = true)).thenReturn(Password)

    val (config, processedArgs) = extractConfig(args, SyncStructureConfig.PrefixWebDav + TestUri, SourceRoleType,
      optReader = Some(reader))
    processedArgs.parameters.accessedParameters should contain(propPwd)
    val davConfig = config.asInstanceOf[DavStructureConfig]
    val authConfig = davConfig.authConfig.asInstanceOf[BasicAuthConfig]
    authConfig.password.secret should be(Password)
  }

  it should "create a correct OneDriveConfig for the source structure with basic auth properties" in {
    val ChunkSize = 42
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDriveServer) -> TestUri,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDrivePath) -> StoragePath,
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDriveUploadChunkSize) -> ChunkSize.toString)

    val (config, processedArgs) = extractConfig(args, SyncStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config match {
      case oneConfig: OneDriveStructureConfig =>
        oneConfig.optUploadChunkSizeMB should be(Some(ChunkSize))
        oneConfig.syncPath should be(StoragePath)
        oneConfig.optServerUri should be(Some(TestUri))
        checkBasicAuthConfig(oneConfig.authConfig)
      case r => fail("Unexpected config " + r)
    }
  }

  it should "create a correct OneDriveConfig for the source structure with defaults" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDrivePath) -> StoragePath)
    val ExpConfig = OneDriveStructureConfig(syncPath = StoragePath, optServerUri = None,
      optUploadChunkSizeMB = None, authConfig = NoAuth)

    val (config, _) = extractConfig(args, SyncStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    config should be(ExpConfig)
  }

  it should "generate a failure for invalid properties of a OneDrive configuration" in {
    val args = Map(SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDriveUploadChunkSize) -> "xx")

    val (exception, _) = expectFailure(args, SyncStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncComponentsFactory.PropOneDriveUploadChunkSize))
    exception.getMessage should include(SourceRoleType.configPropertyName(
      SyncComponentsFactory.PropOneDrivePath))
  }

  it should "create a correct OneDriveConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDrivePath) -> "/a-path",
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDriveUploadChunkSize) -> "4",
      SourceRoleType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceRoleType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceRoleType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password
    )
    val expAccessedKeys = args.keySet +
      SourceRoleType.configPropertyName(OAuthParameterManager.EncryptOptionName) +
      SourceRoleType.configPropertyName(SyncStructureConfig.PropOneDriveServer) +
      SourceRoleType.configPropertyName(SyncStructureConfig.PropAuthUser)

    val (config, processedArgs) = extractConfig(args, SyncStructureConfig.PrefixOneDrive + TestUri, SourceRoleType)
    checkAccessedParameters(processedArgs, expAccessedKeys)
    val oauthConfig = config.asInstanceOf[OneDriveStructureConfig].authConfig.asInstanceOf[OAuthStorageConfig]
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "create a correct OneDriveConfig for the destination structure" in {
    val ChunkSize = 11
    val args = Map(DestinationRoleType.configPropertyName(SyncStructureConfig.PropAuthUser) -> User,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropAuthPassword) -> Password,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropOneDriveServer) -> TestUri,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropOneDrivePath) -> StoragePath,
      DestinationRoleType.configPropertyName(SyncStructureConfig.PropOneDriveUploadChunkSize) ->
        ChunkSize.toString)

    val (config, processedArgs) =
      extractConfig(args, SyncStructureConfig.PrefixOneDrive + TestUri, DestinationRoleType)
    checkAccessedParameters(processedArgs, args.keySet)
    config match {
      case oneConfig: OneDriveStructureConfig =>
        oneConfig.optUploadChunkSizeMB should be(Some(ChunkSize))
        oneConfig.syncPath should be(StoragePath)
        oneConfig.optServerUri should be(Some(TestUri))
        checkBasicAuthConfig(oneConfig.authConfig)
      case r => fail("Unexpected config " + r)
    }
  }
}