package org.ergoplatform.wallet.secrets

import java.io.{File, FileNotFoundException, PrintWriter}
import java.util
import java.util.UUID
import io.circe.parser._
import io.circe.syntax._
import org.ergoplatform.wallet.crypto
import org.ergoplatform.wallet.mnemonic.Mnemonic
import org.ergoplatform.wallet.interface4j.SecretString
import org.ergoplatform.wallet.settings.{EncryptionSettings, SecretStorageSettings}
import scorex.util.encode.Base16

import scala.util.{Failure, Success, Try}

/**
  * Secret storage backend.
  * Stores encrypted seed in json file (structure is described by [[EncryptedSecret]]).
  * Responsible for managing access to the secrets.
  * (detailed storage specification: https://github.com/ergoplatform/ergo-wallet/wiki/Ergo-Secret-Storage)
  */
final class JsonSecretStorage(val secretFile: File, encryptionSettings: EncryptionSettings)
  extends SecretStorage {

  private var unlockedSecret: Option[ExtendedSecretKey] = None

  /**
    * Tells if `secretsIndices` were locked and destroyed.
    */
  override def isLocked: Boolean = unlockedSecret.isEmpty

  /**
    * Returns the `secretsIndices` if already unlocked, or nothing.
    */
  override def secret: Option[ExtendedSecretKey] = unlockedSecret

  /**
    * @param mnemonic - SecretString mnemonic string to be erased after use.
    * @param mnemonicPassOpt - optional SecretString mnemonic password to be erased after use.
    */
  override def checkSeed(mnemonic: SecretString, mnemonicPassOpt: Option[SecretString]): Boolean = {
    val seed = Mnemonic.toSeed(mnemonic, mnemonicPassOpt)
    val secret = ExtendedSecretKey.deriveMasterKey(seed)
    unlockedSecret.fold(false)(s => secret.equals(s))
  }

  /**
    * Checks the seed can be decrypted, provided mnemonic with optional mnemonic password.
    * Makes secrets with `secretsIndices` available through `secrets` call.
    * @param pass - password to be used to decrypt secret, also SecretString to be erased after use
    */
  override def unlock(pass: SecretString): Try[Unit] = {
    val secretFileRaw = scala.io.Source.fromFile(secretFile, "UTF-8").getLines().mkString
    decode[EncryptedSecret](secretFileRaw)
      .right
      .map { encryptedSecret =>
        Base16.decode(encryptedSecret.cipherText)
          .flatMap(txt => Base16.decode(encryptedSecret.salt)
            .flatMap(salt => Base16.decode(encryptedSecret.iv)
              .flatMap(iv => Base16.decode(encryptedSecret.authTag)
                .map(tag => (txt, salt, iv, tag))
              )
            )
          )
          .flatMap { case (cipherText, salt, iv, tag) => {
              val res = crypto.AES.decrypt(cipherText, pass.getData(), salt, iv, tag)(encryptionSettings)
              pass.erase()
              res
            }
          }
      }
      .fold(Failure(_), Success(_))
      .flatten
      .map(seed => unlockedSecret = Some(ExtendedSecretKey.deriveMasterKey(seed)))
  }

  /**
    * Destroys all loaded secrets.
    */
  override def lock(): Unit = {
    unlockedSecret.foreach(_.zeroSecret())
    unlockedSecret = None
  }

}

object JsonSecretStorage {

  /**
    * Initializes storage instance with new wallet file encrypted with the given `pass`.
    */
  def init(seed: Array[Byte], pass: SecretString)(settings: SecretStorageSettings): JsonSecretStorage = {
    val iv = scorex.utils.Random.randomBytes(crypto.AES.NonceBitsLen / 8)
    val salt = scorex.utils.Random.randomBytes(32)
    val (ciphertext, tag) = crypto.AES.encrypt(seed, pass.getData(), salt, iv)(settings.encryption)
    val encryptedSecret = EncryptedSecret(ciphertext, salt, iv, tag, settings.encryption)
    val uuid = UUID.nameUUIDFromBytes(ciphertext)
    new File(settings.secretDir).mkdirs()
    val file = new File(s"${settings.secretDir}/$uuid.json")
    val outWriter = new PrintWriter(file)
    val jsonRaw = encryptedSecret.asJson.noSpaces

    outWriter.write(jsonRaw)
    outWriter.close()
    pass.erase()

    util.Arrays.fill(seed, 0: Byte)

    new JsonSecretStorage(file, settings.encryption)
  }

  /**
    * Initializes storage with the seed derived from an existing mnemonic phrase.
    */
  def restore(mnemonic: SecretString,
              mnemonicPassOpt: Option[SecretString],
              encryptionPass: SecretString,
              settings: SecretStorageSettings): JsonSecretStorage = {
    val seed = Mnemonic.toSeed(mnemonic, mnemonicPassOpt)
    init(seed, encryptionPass)(settings)
  }

  def readFile(settings: SecretStorageSettings): Try[JsonSecretStorage] = {
    val dir = new File(settings.secretDir)
    if (dir.exists()) {
      dir.listFiles().toList match {
        case files if files.size > 1 =>
          val jsonFiles = files.filter(_.getName.contains(".json"))
          jsonFiles.headOption match {
            case Some(headFile) => Success(new JsonSecretStorage(headFile, settings.encryption))
            case None => Failure(new Exception(s"No json files found in dir '$dir'"))
          }
        case headFile :: _ =>
          Success(new JsonSecretStorage(headFile, settings.encryption))
        case Nil =>
          Failure(new Exception(s"Cannot readSecretStorage: Secret file not found in dir '$dir'"))
      }
    } else {
      Failure(new FileNotFoundException(s"Cannot readSecretStorage: dir '$dir' doesn't exist"))
    }
  }

}
