/*
 * Copyright 2018-2022 The Developers Team.
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

import akka.Done
import akka.actor.ActorSystem
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.crypt.alg.CryptAlgorithm
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.service.CryptService

import java.nio.file.{Files, Path}
import java.security.{Key, SecureRandom}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, XML}

/**
  * A default implementation of the [[OAuthStorageService]] trait.
  *
  * This implementation stores the data related to an OAuth identity provider
  * in files with the same base name, but different suffixes. Sensitive
  * information can be encrypted if a password is provided.
  */
object OAuthStorageServiceImpl extends OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]:
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

  override def saveIdpConfig(storageConfig: SyncOAuthStorageConfig, config: IDPConfig)
                            (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    for
      _ <- saveConfig(storageConfig, config)
      _ <- saveClientSecret(storageConfig, config.oauthConfig.clientSecret)
      _ <- saveTokens(storageConfig, config.oauthConfig.initTokenData)
    yield Done

  override def loadIdpConfig(storageConfig: SyncOAuthStorageConfig)(implicit ec: ExecutionContext, system: ActorSystem):
  Future[IDPConfig] = for
    config <- loadConfig(storageConfig)
    secret <- loadClientSecret(storageConfig)
    tokens <- loadTokens(storageConfig)
  yield config.copy(oauthConfig = config.oauthConfig.copy(clientSecret = secret, initTokenData = tokens))

  override def saveTokens(storageConfig: SyncOAuthStorageConfig, tokens: OAuthTokenData)
                         (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    val tokenData = tokens.accessToken + TokenSeparator + tokens.refreshToken
    val source = cryptSource(Source.single(ByteString(tokenData)), storageConfig.optPassword) { (alg, key, rnd, src) =>
      CryptService.encryptSource(alg, key, src)(rnd)
    }
    saveFile(storageConfig, SuffixTokenFile, source)

  override def removeStorage(storageConfig: SyncOAuthStorageConfig)(implicit ec: ExecutionContext): Future[List[Path]] =
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
    * Saves the part of the configuration data that contains only properties of
    * the IDP and no sensitive data.
    *
    * @param storageConfig the storage configuration
    * @param config        the config to be stored
    * @param ec            the executor context
    * @param system        the actor system
    * @return a ''Future'' with the result of the operation
    */
  private def saveConfig(storageConfig: SyncOAuthStorageConfig, config: IDPConfig)
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
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

  /**
    * Loads the file with properties of the IDP. The resulting ''IDPConfig'' is
    * incomplete, as it does not contain any sensitive data.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the (incomplete) ''IDPConfig''
    */
  private def loadConfig(storageConfig: SyncOAuthStorageConfig)
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

  /**
    * Writes the file with client secret. The file is encrypted if a password
    * has been specified.
    *
    * @param storageConfig the storage configuration
    * @param secret        the secret
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the result of the operation
    */
  private def saveClientSecret(storageConfig: SyncOAuthStorageConfig, secret: Secret)
                              (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    val source = cryptSource(Source.single(ByteString(secret.secret)), storageConfig.optPassword) { (a, k, rnd, src) =>
      CryptService.encryptSource(a, k, src)(rnd)
    }
    saveFile(storageConfig, SuffixSecretFile, source)

  /**
    * Loads the client secret from the corresponding file. If this file is not
    * present, an empty default secret is returned.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the client secret
    */
  private def loadClientSecret(storageConfig: SyncOAuthStorageConfig)
                              (implicit ec: ExecutionContext, system: ActorSystem): Future[Secret] =
    loadAndMapFile(storageConfig, SuffixSecretFile, storageConfig.optPassword,
      optDefault = Some(UndefinedSecret))(buf => Secret(buf.utf8String))

  /**
    * Loads the current token data from the corresponding file. If this file is
    * not present, an object with undefined tokens is returned.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the token information
    */
  private def loadTokens(storageConfig: SyncOAuthStorageConfig)
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] =
    loadAndMapFile(storageConfig, SuffixTokenFile, optPwd = storageConfig.optPassword,
      optDefault = Some(UndefinedTokens)) { buf =>
      val parts = buf.utf8String.split(TokenSeparator)
      if parts.length < 2 then
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} contains too few tokens.")
      else if parts.length > 2 then
        throw new IllegalArgumentException(s"Token file for ${storageConfig.baseName} has unexpected content.")
      OAuthTokenData(accessToken = parts(0), refreshToken = parts(1))
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
  private def extractElem(elem: Elem, child: String): String =
    val text = (elem \ child).text.trim
    if text.isEmpty then
      throw new IllegalArgumentException(s"Missing mandatory property '$child' in OAuth configuration.")
    text

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
  private def saveFile(storageConfig: SyncOAuthStorageConfig, suffix: String, source: Source[ByteString, Any])
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    val sink = FileIO.toPath(storageConfig.resolveFileName(suffix))
    source.runWith(sink).map(_ => Done)

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
  private def loadAndMapFile[T](storageConfig: SyncOAuthStorageConfig, suffix: String, optPwd: Option[Secret] = None,
                                optDefault: Option[T] = None)(f: ByteString => T)
                               (implicit ec: ExecutionContext, system: ActorSystem): Future[T] =
    val path = storageConfig.resolveFileName(suffix)
    if optDefault.isDefined && !Files.isRegularFile(path) then
      Future.successful(optDefault.get)
    else
      val source = cryptSource(fileSource(path), optPwd) { (alg, key, rnd, src) =>
        CryptService.decryptSource(alg, key, src)(rnd)
      }
      val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      source.runWith(sink).map(f)

  /**
    * Applies a cryptographic operation to the given source. Some information
    * managed by this service is sensitive and hence supports encryption. If a
    * secret for encryption is provided, the original source is decorated with
    * encryption or decryption, depending on the ''cryptFunc'' provided.
    *
    * @param source    the original source
    * @param optSecret and option with the secret to be used for encryption
    * @param cryptFunc a function to apply the desired operation to the
    *                  original source
    * @tparam Mat the type of materialization
    * @return the decorated source
    */
  private def cryptSource[Mat](source: Source[ByteString, Mat], optSecret: Option[Secret])
                              (cryptFunc: (CryptAlgorithm, Key, SecureRandom, Source[ByteString, Mat]) =>
                                Source[ByteString, Mat]): Source[ByteString, Mat] =
    optSecret.map { secret =>
      cryptFunc(Aes, Aes.keyFromString(secret.secret), new SecureRandom, source)
    } getOrElse source
