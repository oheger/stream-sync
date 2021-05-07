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

import java.nio.file.{Files, Path}
import akka.Done
import akka.actor.ActorSystem
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.crypt.{CryptOpHandler, CryptStage, DecryptOpHandler, EncryptOpHandler}
import com.github.sync.http.OAuthStorageConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, XML}

/**
  * A default implementation of the [[OAuthStorageService]] trait.
  *
  * This implementation stores the data related to an OAuth identity provider
  * in files with the same base name, but different suffixes. Sensitive
  * information can be encrypted if a password is provided.
  */
object OAuthStorageServiceImpl extends OAuthStorageService[OAuthStorageConfig, IDPConfig, Secret, OAuthTokenData] {
  /** Constant for the suffix used for the file with the OAuth config. */
  final val SuffixConfigFile = ".xml"

  /** Constant for the suffix used for the file with the client secret. */
  final val SuffixSecretFile = ".sec"

  /** Constant for the suffix of the file with token information. */
  final val SuffixTokenFile = ".toc"

  /** Property for the client ID in the persistent config representation. */
  final val PropClientId = "client-id"

  /**
    * Property for the authorization endpoint in the persistent config
    * representation.
    */
  final val PropAuthorizationEndpoint = "authorization-endpoint"

  /**
    * Property for the token endpoint in the persistent config
    * representation.
    */
  final val PropTokenEndpoint = "token-endpoint"

  /** Property for the scope in the persistent config representation. */
  final val PropScope = "scope"

  /** Property for the redirect URI in the persistent config representation. */
  final val PropRedirectUri = "redirect-uri"

  /**
    * Constant for a secret with an empty value. This is set as the client
    * secret by ''loadIdpConfig()'' if no secret file is present.
    */
  final val UndefinedSecret = Secret("")

  /**
    * Constant for token data with empty tokens. This is set as initial tokens
    * by ''loadIdpConfig()'' if no tokens file is present.
    */
  final val UndefinedTokens = OAuthTokenData("", "")

  /** The separator character used within the token file. */
  private val TokenSeparator = "\t"

  override def saveIdpConfig(storageConfig: OAuthStorageConfig, config: IDPConfig)
                            (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    for {
      _ <- saveConfig(storageConfig, config)
      _ <- saveClientSecret(storageConfig, config.oauthConfig.clientSecret)
      _ <- saveTokens(storageConfig, config.oauthConfig.initTokenData)
    } yield Done

  override def loadIdpConfig(storageConfig: OAuthStorageConfig)(implicit ec: ExecutionContext, system: ActorSystem):
  Future[IDPConfig] = for {
    config <- loadConfig(storageConfig)
    secret <- loadClientSecret(storageConfig)
    tokens <- loadTokens(storageConfig)
  } yield config.copy(oauthConfig = config.oauthConfig.copy(clientSecret = secret, initTokenData = tokens))

  override def saveConfig(storageConfig: OAuthStorageConfig, config: IDPConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
    val xml = <oauth-config>
      <client-id>
        {config.oauthConfig.clientID}
      </client-id>
      <authorization-endpoint>
        {config.authorizationEndpoint}
      </authorization-endpoint>
      <token-endpoint>
        {config.oauthConfig.tokenEndpoint}
      </token-endpoint>
      <scope>
        {config.scope}
      </scope>
      <redirect-uri>
        {config.oauthConfig.redirectUri}
      </redirect-uri>
    </oauth-config>

    val source = Source.single(ByteString(xml.toString()))
    saveFile(storageConfig, SuffixConfigFile, source)
  }

  override def loadConfig(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[IDPConfig] =
    loadAndMapFile(storageConfig, SuffixConfigFile) { buf =>
      val nodeSeq = XML.loadString(buf.utf8String)
      val oauthConfig = OAuthConfig(clientID = extractElem(nodeSeq, PropClientId),
        tokenEndpoint = extractElem(nodeSeq, PropTokenEndpoint),
        redirectUri = extractElem(nodeSeq, PropRedirectUri), clientSecret = null,
        initTokenData = OAuthTokenData(null, null))
      IDPConfig(
        authorizationEndpoint = extractElem(nodeSeq, PropAuthorizationEndpoint),
        scope = extractElem(nodeSeq, PropScope), oauthConfig = oauthConfig)
    }

  override def saveClientSecret(storageConfig: OAuthStorageConfig, secret: Secret)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
    val source = cryptSource(Source.single(ByteString(secret.secret)), storageConfig.optPassword,
      EncryptOpHandler)
    saveFile(storageConfig, SuffixSecretFile, source)
  }

  override def loadClientSecret(storageConfig: OAuthStorageConfig)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[Secret] =
    loadAndMapFile(storageConfig, SuffixSecretFile, storageConfig.optPassword,
      optDefault = Some(UndefinedSecret))(buf => Secret(buf.utf8String))

  override def saveTokens(storageConfig: OAuthStorageConfig, tokens: OAuthTokenData)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
    val tokenData = tokens.accessToken + TokenSeparator + tokens.refreshToken
    val source = cryptSource(Source.single(ByteString(tokenData)), storageConfig.optPassword, EncryptOpHandler)
    saveFile(storageConfig, SuffixTokenFile, source)
  }

  override def loadTokens(storageConfig: OAuthStorageConfig)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] =
    loadAndMapFile(storageConfig, SuffixTokenFile, optPwd = storageConfig.optPassword,
      optDefault = Some(UndefinedTokens)) { buf =>
      val parts = buf.utf8String.split(TokenSeparator)
      if (parts.length < 2)
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} contains too few tokens.")
      else if (parts.length > 2)
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} has unexpected content.")
      OAuthTokenData(accessToken = parts(0), refreshToken = parts(1))
    }

  override def removeStorage(storageConfig: OAuthStorageConfig)(implicit ec: ExecutionContext): Future[List[Path]] =
    Future {
      List(SuffixConfigFile, SuffixSecretFile, SuffixTokenFile)
        .map(storageConfig.resolveFileName)
        .filter(Files.isRegularFile(_))
        .map { path =>
          Files.delete(path)
          path
        }
    }

  /**
    * Extracts the text content of the given child from an XML document. If the
    * child cannot be resolved, an ''IllegalArgumentException'' is thrown; this
    * indicates an invalid configuration file.
    *
    * @param elem  the parent XML element
    * @param child the name of the desired child
    * @return the text content of this child element
    * @throws IllegalArgumentException if the child cannot be found
    */
  private def extractElem(elem: Elem, child: String): String = {
    val text = (elem \ child).text.trim
    if (text.isEmpty)
      throw new IllegalArgumentException(s"Missing mandatory property '$child' in OAuth configuration.")
    text
  }

  /**
    * Returns a source for loading a file based on the given storage
    * configuration.
    *
    * @param storageConfig the storage configuration
    * @param suffix        the suffix of the file to be loaded
    * @return the source for loading this file
    */
  private def fileSource(storageConfig: OAuthStorageConfig, suffix: String): Source[ByteString, Future[IOResult]] =
    fileSource(storageConfig.resolveFileName(suffix))

  /**
    * Returns a source for loading the specified file.
    *
    * @param path the path to the file to be loaded
    * @return the source for loading this file
    */
  private def fileSource(path: Path): Source[ByteString, Future[IOResult]] = FileIO.fromPath(path)

  /**
    * Writes the data produced by the given source to a file based on the
    * storage configuration provided and returns a ''Future'' with the result.
    *
    * @param storageConfig the storage configuration
    * @param suffix        the suffix of the file to be saved
    * @param source        the source producing the file's content
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' indicating the success of this operation
    */
  private def saveFile(storageConfig: OAuthStorageConfig, suffix: String, source: Source[ByteString, Any])
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
    val sink = FileIO.toPath(storageConfig.resolveFileName(suffix))
    source.runWith(sink).map(_ => Done)
  }

  /**
    * Loads a file based on a given storage configuration into memory, applies
    * a mapping function to the loaded data, and returns the result. If a
    * password is provided, the loaded data is decrypted.
    *
    * @param storageConfig the storage configuration
    * @param suffix        the suffix of the file to be loaded
    * @param optPwd        an optional password for decryption
    * @param optDefault    an optional default to be returned if the file is
    *                      not present
    * @param f             the mapping function
    * @param ec            the execution context
    * @param system        the actor system
    * @tparam T the type of the result
    * @return the result generated by the mapping function
    */
  private def loadAndMapFile[T](storageConfig: OAuthStorageConfig, suffix: String, optPwd: Option[Secret] = None,
                                optDefault: Option[T] = None)(f: ByteString => T)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[T] = {
    val path = storageConfig.resolveFileName(suffix)
    if (optDefault.isDefined && !Files.isRegularFile(path))
      Future.successful(optDefault.get)
    else {
      val source = cryptSource(fileSource(path), optPwd, DecryptOpHandler)
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      source.runWith(sink).map(f)
    }
  }

  /**
    * Applies encryption to the given source. Some information managed by this
    * service is sensitive and hence supports encryption. If a secret for
    * encryption is provided, a corresponding [[CryptStage]] is added to the
    * source.
    *
    * @param source         the original source
    * @param optSecret      and option with the secret to be used for encryption
    * @param cryptOpHandler the handler for the crypt operation
    * @tparam Mat the type of materialization
    * @return the decorated source
    */
  private def cryptSource[Mat](source: Source[ByteString, Mat], optSecret: Option[Secret],
                               cryptOpHandler: CryptOpHandler): Source[ByteString, Mat] =
    optSecret.map { secret =>
      source.via(new CryptStage(cryptOpHandler, CryptStage.keyFromString(secret.secret)))
    } getOrElse source
}
