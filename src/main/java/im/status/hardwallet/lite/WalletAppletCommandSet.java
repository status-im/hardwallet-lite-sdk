package im.status.hardwallet.lite;

import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;

/**
 * This class is used to send APDU to the applet. Each method corresponds to an APDU as defined in the APPLICATION.md
 * file. Some APDUs map to multiple methods for the sake of convenience since their payload or response require some
 * pre/post processing.
 */
public class WalletAppletCommandSet {
  static final byte INS_INIT = (byte) 0xFE;
  static final byte INS_GET_STATUS = (byte) 0xF2;
  static final byte INS_SET_NDEF = (byte) 0xF2;
  static final byte INS_VERIFY_PIN = (byte) 0x20;
  static final byte INS_CHANGE_PIN = (byte) 0x21;
  static final byte INS_UNBLOCK_PIN = (byte) 0x22;
  static final byte INS_LOAD_KEY = (byte) 0xD0;
  static final byte INS_DERIVE_KEY = (byte) 0xD1;
  static final byte INS_GENERATE_MNEMONIC = (byte) 0xD2;
  static final byte INS_REMOVE_KEY = (byte) 0xD3;
  static final byte INS_GENERATE_KEY = (byte) 0xD4;
  static final byte INS_SIGN = (byte) 0xC0;
  static final byte INS_SET_PINLESS_PATH = (byte) 0xC1;
  static final byte INS_EXPORT_KEY = (byte) 0xC2;

  public static final byte GET_STATUS_P1_APPLICATION = 0x00;

  static final byte LOAD_KEY_P1_EC = 0x01;
  static final byte LOAD_KEY_P1_EXT_EC = 0x02;
  static final byte LOAD_KEY_P1_SEED = 0x03;

  static final byte DERIVE_P1_SOURCE_MASTER = (byte) 0x00;
  static final byte DERIVE_P1_SOURCE_PARENT = (byte) 0x40;
  static final byte DERIVE_P1_SOURCE_CURRENT = (byte) 0x80;


  static final byte EXPORT_KEY_P2_PRIVATE_AND_PUBLIC = 0x00;
  static final byte EXPORT_KEY_P2_PUBLIC_ONLY = 0x01;

  static final byte TLV_PUB_KEY = (byte) 0x80;
  static final byte TLV_PRIV_KEY = (byte) 0x81;
  static final byte TLV_CHAIN_CODE = (byte) 0x82;
  static final byte TLV_APPLICATION_INFO_TEMPLATE = (byte) 0xA4;


  public static final String APPLET_AID = "53746174757357616C6C6574417070";
  static final byte[] APPLET_AID_BYTES = Hex.decode(APPLET_AID);

  private final CardChannel apduChannel;
  private SecureChannelSession secureChannel;

  public static ResponseAPDU checkOK(ResponseAPDU resp) throws CardException {
    if (resp.getSW() != 0x9000) {
      throw new CardException("Operation failed: " + resp.toString());
    }

    return resp;
  }

  public WalletAppletCommandSet(CardChannel apduChannel) {
    this.apduChannel = apduChannel;
  }

  protected void setSecureChannel(SecureChannelSession secureChannel) {
    this.secureChannel = secureChannel;
  }

  /**
   * Selects the applet. The applet is assumed to have been installed with its default AID. The returned data is a
   * public key which must be used to initialize the secure channel.
   *
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU select() throws CardException {
    CommandAPDU selectApplet = new CommandAPDU(0x00, 0xA4, 4, 0, APPLET_AID_BYTES);
    ResponseAPDU resp =  apduChannel.transmit(selectApplet);

    if (resp.getSW() == 0x9000) {
      byte[] keyData = extractPublicKeyFromSelect(resp.getData());

      if (this.secureChannel == null) {
        setSecureChannel(new SecureChannelSession(keyData));
      } else {
        this.secureChannel.generateSecret(keyData);
      }
    }

    return resp;
  }

  /**
   * Opens the secure channel. Calls the corresponding method of the SecureChannel class.
   *
   * @return the raw card response
   * @throws CardException communication error
   */
  public void autoOpenSecureChannel() throws CardException {
    secureChannel.autoOpenSecureChannel(apduChannel);
  }

  /**
   * Automatically pairs. Derives the secret from the given password.
   *
   * @throws CardException communication error
   */
  public void autoPair(String pairingPassword) throws CardException {
    SecretKey key;

    try {
      SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "BC");
      PBEKeySpec spec = new PBEKeySpec(pairingPassword.toCharArray(), "Status Hardware Wallet Lite".getBytes(), 50000, 32 * 8);
      key = skf.generateSecret(spec);
    } catch (Exception e) {
      throw new RuntimeException("Is Bouncycastle correctly initialized?");
    }

    secureChannel.autoPair(apduChannel, key.getEncoded());
  }

  /**
   * Automatically pairs. Calls the corresponding method of the SecureChannel class.
   *
   * @throws CardException communication error
   */
  public void autoPair(byte[] sharedSecret) throws CardException {
    secureChannel.autoPair(apduChannel, sharedSecret);
  }

  /**
   * Automatically unpairs. Calls the corresponding method of the SecureChannel class.
   *
   * @throws CardException communication error
   */
  public void autoUnpair() throws CardException {
    secureChannel.autoUnpair(apduChannel);
  }

  /**
   * Sends a OPEN SECURE CHANNEL APDU. Calls the corresponding method of the SecureChannel class.
   */
  public ResponseAPDU openSecureChannel(byte index, byte[] data) throws CardException {
    return secureChannel.openSecureChannel(apduChannel, index, data);
  }

  /**
   * Sends a MUTUALLY AUTHENTICATE APDU. Calls the corresponding method of the SecureChannel class.
   */
  public ResponseAPDU mutuallyAuthenticate() throws CardException {
    return secureChannel.mutuallyAuthenticate(apduChannel);
  }

  /**
   * Sends a MUTUALLY AUTHENTICATE APDU. Calls the corresponding method of the SecureChannel class.
   */
  public ResponseAPDU mutuallyAuthenticate(byte[] data) throws CardException {
    return secureChannel.mutuallyAuthenticate(apduChannel, data);
  }

  /**
   * Sends a PAIR APDU. Calls the corresponding method of the SecureChannel class.
   */
  public ResponseAPDU pair(byte p1, byte[] data) throws CardException {
    return secureChannel.pair(apduChannel, p1, data);
  }

  /**
   * Sends a UNPAIR APDU. Calls the corresponding method of the SecureChannel class.
   */
  public ResponseAPDU unpair(byte p1) throws CardException {
    return secureChannel.unpair(apduChannel, p1);
  }

  /**
   * Unpair all other clients.
   */
  public void unpairOthers() throws CardException {
    secureChannel.unpairOthers(apduChannel);
  }

  /**
   * Sends a GET STATUS APDU. The info byte is the P1 parameter of the command, valid constants are defined in the applet
   * class itself.
   *
   * @param info the P1 of the APDU
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU getStatus(byte info) throws CardException {
    CommandAPDU getStatus = secureChannel.protectedCommand(0x80, INS_GET_STATUS, info, 0, new byte[0]);
    return secureChannel.transmit(apduChannel, getStatus);
  }

  /**
   * Sends a SET NDEF APDU.
   *
   * @param ndef the data field of the APDU
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU setNDEF(byte[] ndef) throws CardException {
    CommandAPDU setNDEF = secureChannel.protectedCommand(0x80, INS_SET_NDEF, 0, 0, ndef);
    return secureChannel.transmit(apduChannel, setNDEF);
  }

  /**
   * Sends a GET STATUS APDU to retrieve the APPLICATION STATUS template and reads the byte indicating key initialization
   * status
   *
   * @return whether public key derivation is supported or not
   * @throws CardException communication error
   */
  public boolean getKeyInitializationStatus() throws CardException {
    ResponseAPDU resp = getStatus(GET_STATUS_P1_APPLICATION);
    byte[] data = resp.getData();
    return data[data.length - 1] != 0x00;
  }

  /**
   * Sends a VERIFY PIN APDU. The raw bytes of the given string are encrypted using the secure channel and used as APDU
   * data.
   *
   * @param pin the pin
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU verifyPIN(String pin) throws CardException {
    CommandAPDU verifyPIN = secureChannel.protectedCommand(0x80, INS_VERIFY_PIN, 0, 0, pin.getBytes());
    return secureChannel.transmit(apduChannel, verifyPIN);
  }

  /**
   * Sends a CHANGE PIN APDU. The raw bytes of the given string are encrypted using the secure channel and used as APDU
   * data.
   *
   * @param pinType the PIN type
   * @param pin the new PIN
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU changePIN(int pinType, String pin) throws CardException {
    return changePIN(pinType, pin.getBytes());
  }

  /**
   * Sends a CHANGE PIN APDU. The raw bytes of the given string are encrypted using the secure channel and used as APDU
   * data.
   *
   * @param pinType the PIN type
   * @param pin the new PIN
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU changePIN(int pinType, byte[] pin) throws CardException {
    CommandAPDU changePIN = secureChannel.protectedCommand(0x80, INS_CHANGE_PIN, pinType, 0, pin);
    return secureChannel.transmit(apduChannel, changePIN);
  }

  /**
   * Sends an UNBLOCK PIN APDU. The PUK and PIN are concatenated and the raw bytes are encrypted using the secure
   * channel and used as APDU data.
   *
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU unblockPIN(String puk, String newPin) throws CardException {
    CommandAPDU unblockPIN = secureChannel.protectedCommand(0x80, INS_UNBLOCK_PIN, 0, 0, (puk + newPin).getBytes());
    return secureChannel.transmit(apduChannel, unblockPIN);
  }

  /**
   * Sends a LOAD KEY APDU. The given private key and chain code are formatted as a raw binary seed and the P1 of
   * the command is set to LOAD_KEY_P1_SEED (0x03). This works on cards which support public key derivation.
   * The loaded keyset is extended and support further key derivation.
   *
   * @param aPrivate a private key
   * @param chainCode the chain code
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU loadKey(PrivateKey aPrivate, byte[] chainCode) throws CardException {
    byte[] privateKey = ((ECPrivateKey) aPrivate).getD().toByteArray();

    int privLen = privateKey.length;
    int privOff = 0;

    if(privateKey[0] == 0x00) {
      privOff++;
      privLen--;
    }

    byte[] data = new byte[chainCode.length + privLen];
    System.arraycopy(privateKey, privOff, data, 0, privLen);
    System.arraycopy(chainCode, 0, data, privLen, chainCode.length);

    return loadKey(data, LOAD_KEY_P1_SEED);
  }

  /**
   * Sends a LOAD KEY APDU. The key is sent in TLV format, includes the public key and no chain code, meaning that
   * the card will not be able to do further key derivation.
   *
   * @param ecKeyPair a key pair
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU loadKey(KeyPair ecKeyPair) throws CardException {
    return loadKey(ecKeyPair, false, null);
  }

  /**
   * Sends a LOAD KEY APDU. The key is sent in TLV format. The public key is included or not depending on the value
   * of the omitPublicKey parameter. The chain code is included if the chainCode is not null. P1 is set automatically
   * to either LOAD_KEY_P1_EC or LOAD_KEY_P1_EXT_EC depending on the presence of the chainCode.
   *
   * @param keyPair a key pair
   * @param omitPublicKey whether the public key is sent or not
   * @param chainCode the chain code
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU loadKey(KeyPair keyPair, boolean omitPublicKey, byte[] chainCode) throws CardException {
    byte[] publicKey = omitPublicKey ? null : ((ECPublicKey) keyPair.getPublic()).getQ().getEncoded(false);
    byte[] privateKey = ((ECPrivateKey) keyPair.getPrivate()).getD().toByteArray();

    return loadKey(publicKey, privateKey, chainCode);
  }

  /**
   * Sends a LOAD KEY APDU. The key is sent in TLV format. The public key is included if not null. The chain code is
   * included if not null. P1 is set automatically to either LOAD_KEY_P1_EC or
   * LOAD_KEY_P1_EXT_EC depending on the presence of the chainCode.
   *
   * @param publicKey a raw public key
   * @param privateKey a raw private key
   * @param chainCode the chain code
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU loadKey(byte[] publicKey, byte[] privateKey, byte[] chainCode) throws CardException {
    int privLen = privateKey.length;
    int privOff = 0;

    if(privateKey[0] == 0x00) {
      privOff++;
      privLen--;
    }

    int off = 0;
    int totalLength = publicKey == null ? 0 : (publicKey.length + 2);
    totalLength += (privLen + 2);
    totalLength += chainCode == null ? 0 : (chainCode.length + 2);

    if (totalLength > 127) {
      totalLength += 3;
    } else {
      totalLength += 2;
    }

    byte[] data = new byte[totalLength];
    data[off++] = (byte) 0xA1;

    if (totalLength > 127) {
      data[off++] = (byte) 0x81;
      data[off++] = (byte) (totalLength - 3);
    } else {
      data[off++] = (byte) (totalLength - 2);
    }

    if (publicKey != null) {
      data[off++] = TLV_PUB_KEY;
      data[off++] = (byte) publicKey.length;
      System.arraycopy(publicKey, 0, data, off, publicKey.length);
      off += publicKey.length;
    }

    data[off++] = TLV_PRIV_KEY;
    data[off++] = (byte) privLen;
    System.arraycopy(privateKey, privOff, data, off, privLen);
    off += privLen;

    byte p1;

    if (chainCode != null) {
      p1 = LOAD_KEY_P1_EXT_EC;
      data[off++] = (byte) TLV_CHAIN_CODE;
      data[off++] = (byte) chainCode.length;
      System.arraycopy(chainCode, 0, data, off, chainCode.length);
    } else {
      p1 = LOAD_KEY_P1_EC;
    }

    return loadKey(data, p1);
  }

  /**
   * Sends a LOAD KEY APDU. The data is encrypted and sent as-is. The keyType parameter is used as P1.
   *
   * @param data key data
   * @param keyType the P1 parameter
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU loadKey(byte[] data, byte keyType) throws CardException {
    CommandAPDU loadKey = secureChannel.protectedCommand(0x80, INS_LOAD_KEY, keyType, 0, data);
    return secureChannel.transmit(apduChannel, loadKey);
  }

  /**
   * Sends a GENERATE MNEMONIC APDU. The cs parameter is the length of the checksum and is used as P1.
   *
   * @param cs the P1 parameter
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU generateMnemonic(int cs) throws CardException {
    CommandAPDU generateMnemonic = secureChannel.protectedCommand(0x80, INS_GENERATE_MNEMONIC, cs, 0, new byte[0]);
    return secureChannel.transmit(apduChannel, generateMnemonic);
  }

  /**
   * Sends a REMOVE KEY APDU.
   *
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU removeKey() throws CardException {
    CommandAPDU removeKey = secureChannel.protectedCommand(0x80, INS_REMOVE_KEY, 0, 0, new byte[0]);
    return secureChannel.transmit(apduChannel, removeKey);
  }

  /**
   * Sends a GENERATE KEY APDU.
   *
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU generateKey() throws CardException {
    CommandAPDU generateKey = secureChannel.protectedCommand(0x80, INS_GENERATE_KEY, 0, 0, new byte[0]);
    return secureChannel.transmit(apduChannel, generateKey);
  }

  /**
   * Sends a SIGN APDU. This signs a precomputed hash so the input must be exactly 32-bytes long.
   *
   * @param data the data to sign
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU sign(byte[] data) throws CardException {
    CommandAPDU sign = secureChannel.protectedCommand(0x80, INS_SIGN, 0x00, 0x00, data);
    return secureChannel.transmit(apduChannel, sign);
  }

  /**
   * Sends a DERIVE KEY APDU. The data is encrypted and sent as-is. The P1 is forced to 0, meaning that the derivation
   * starts from the master key.
   *
   * @param data the raw key path
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU deriveKey(byte[] data) throws CardException {
    return deriveKey(data, DERIVE_P1_SOURCE_MASTER);
  }

  /**
   * Sends a DERIVE KEY APDU. The data is encrypted and sent as-is. The source parameter is used as P1.
   *
   * @param data the raw key path or a public key
   * @param source the source to start derivation
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU deriveKey(byte[] data, int source) throws CardException {
    CommandAPDU deriveKey = secureChannel.protectedCommand(0x80, INS_DERIVE_KEY, source, 0, data);
    return secureChannel.transmit(apduChannel, deriveKey);
  }

  /**
   * Sends a SET PINLESS PATH APDU. The data is encrypted and sent as-is.
   *
   * @param data the raw key path
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU setPinlessPath(byte [] data) throws CardException {
    CommandAPDU setPinlessPath = secureChannel.protectedCommand(0x80, INS_SET_PINLESS_PATH, 0x00, 0x00, data);
    return secureChannel.transmit(apduChannel, setPinlessPath);
  }

  /**
   * Sends an EXPORT KEY APDU. The keyPathIndex is used as P1. Valid values are defined in the applet itself
   *
   * @param keyPathIndex the P1 parameter
   * @param publicOnly the P2 parameter
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU exportKey(byte keyPathIndex, boolean publicOnly) throws CardException {
    byte p2 = publicOnly ? EXPORT_KEY_P2_PUBLIC_ONLY : EXPORT_KEY_P2_PRIVATE_AND_PUBLIC;
    CommandAPDU exportKey = secureChannel.protectedCommand(0x80, INS_EXPORT_KEY, keyPathIndex, p2, new byte[0]);
    return secureChannel.transmit(apduChannel, exportKey);
  }

  /**
   * Sends the INIT command to the card.
   *
   * @param pin the PIN
   * @param puk the PUK
   * @param sharedSecret the shared secret for pairing
   * @return the raw card response
   * @throws CardException communication error
   */
  public ResponseAPDU init(String pin, String puk, byte[] sharedSecret) throws CardException {
    byte[] initData = Arrays.copyOf(pin.getBytes(), pin.length() + puk.length() + sharedSecret.length);
    System.arraycopy(puk.getBytes(), 0, initData, pin.length(), puk.length());
    System.arraycopy(sharedSecret, 0, initData, pin.length() + puk.length(), sharedSecret.length);
    CommandAPDU init = new CommandAPDU(0x80, INS_INIT, 0, 0, secureChannel.oneShotEncrypt(initData));
    return apduChannel.transmit(init);
  }

  private byte[] extractPublicKeyFromSelect(byte[] select) {
    if (select[0] == TLV_APPLICATION_INFO_TEMPLATE) {
      return Arrays.copyOfRange(select, 22, 22 + select[21]);
    } else if (select[0] == TLV_PUB_KEY) {
      return Arrays.copyOfRange(select, 2, select.length);
    } else {
      throw new RuntimeException("Unexpected card response");
    }
  }
}
