package com.luxoft.pki.tools;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertStoreException;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import javax.security.auth.x500.X500Principal;

import ru.CryptoPro.JCP.ASN.CertificateExtensions.SubjectKeyIdentifier;
import ru.CryptoPro.JCP.ASN.CryptographicMessageSyntax.IssuerAndSerialNumber;
import ru.CryptoPro.JCP.ASN.CryptographicMessageSyntax.SignerIdentifier;

/**
 * 
 * @author Igor Konovalov ikonovalov@luxoft.com
 *
 */
@SuppressWarnings("restriction")
public abstract class CryptoUtils {
	
	private static final Logger LOG = Logger.getLogger(CryptoUtils.class.getName());
	
	private final static ThreadLocal<CertPathBuilder> certPathBuilder = new ThreadLocal<CertPathBuilder>() {

		@Override
		protected CertPathBuilder initialValue() {
			try {
				return CertPathBuilder.getInstance("PKIX");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalArgumentException(e);
			}
		}
		
	};
	
	public static final String SUBJECT_KEY_IDENTEFER_OID = "2.5.29.14";
	
	private KeyStore keyStore = null;
	
	private int verificationOptions = 0;
	
	public final static int OPT_ALL_FLAGS_DOWN  = 0;
	
	public final static int OPT_STORED_CERT_ONLY = 1;
	
	public final static int OPT_ALLOW_SELFSIGNED_CERT = 8;
	
	public final static int OPT_SKIP_SELFSIGNED_CERT = 16;
	
	public final static int OPT_DISABLE_CERT_VALIDATION = 32;
	
	public final static int OPT_STRONG_POLICY = OPT_STORED_CERT_ONLY;
	
	// --- ABSTRACT PART -------------------------------------------
	public static CertPathBuilder getCertPathBuilder() {
		return certPathBuilder.get();
	}
	
	public static void setCertPathBuilder(CertPathBuilder builder) {
		certPathBuilder.set(builder);
	}
	
	public abstract byte[] decrypt(byte[] ciphertext) throws Exception;
	
	public abstract byte[] detach(byte[] signed) throws Exception;
	
	public abstract byte[] encrypt(byte[] plain) throws Exception;
	
	public abstract void verify(byte[] signed) throws Exception;
	
	public abstract byte[] signAttached(byte[] data) throws Exception;
	
	/**
	 * Добавление списка получателей сообщения. Используется в RecipientInfo и при генерации ключа сограсования.
	 * @param recipientsAliases - массив алиасов сертификатов получателей.
	 * @return
	 * @throws Exception
	 */
	public abstract CryptoUtils recipients(String... recipientsAliases) throws Exception;

	/**
	 * Добавление подписчиков сообщения. (По списку выбираются PrivateKey из хранилища и подписывают сообщение)
	 * @param signerAliases - массив алиасов подписчиков
	 * @return
	 * @throws Exception
	 */
	public abstract CryptoUtils signer(String... signerAliases) throws Exception;
	
	// =============================================================
	
	public final int withVerificationOptions(int... flags) {
		this.verificationOptions = OPT_ALL_FLAGS_DOWN;
		for (int f : flags) {
			this.verificationOptions |= f;
		}
		return this.verificationOptions;
	}
	
	protected final boolean isFlagSet(int flagbitN) {
		return (verificationOptions & flagbitN) == flagbitN;
	}
	
	protected final boolean isFlagNotSet(int flagbitN) {
		return !isFlagSet(flagbitN);
	}
	
	/**
	 * 
	 * @param alias
	 * @return
	 * @throws KeyStoreException - если сертификат не найден или проиче проблемы с хранилищем.
	 */
	protected  X509Certificate getCertificateFromStore(String alias) throws KeyStoreException {
		if (alias == null) {
			return null;
		}
		X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
		if (cert == null) {
			throw new KeyStoreException("Certificate for alias '" + alias + "' not found");
		}
		return cert;
	}
	
	protected PrivateKey getKeyFromStore(String alias, char[] password) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
		if (alias == null) {
			return null;
		} else {
			return (PrivateKey) getKeyStore().getKey(alias, password);
		}
	}

	/**
	 * Получение текущего хранилища ключей и сертификатов X509
	 * @return KeyStore
	 */
	protected final KeyStore getKeyStore() {
		return keyStore;
	}
	
	/**
	 * Установка хранилища сертификатов. Для инстанса выполняется только одина раз.
	 * @param keyStore
	 */
	protected final void setKeyStore(KeyStore keyStore) {
		if (this.keyStore == null) {
			this.keyStore = keyStore;
		} else {
			String msg = "KeyStore already set";
			LOG.severe(msg);
			throw new RuntimeException(msg);
		}
	}
	
	/**
	 * Вызгузка всех сертификатов в хранилище как CertStore
	 * @return
	 * @throws KeyStoreException
	 * @throws InvalidAlgorithmParameterException
	 * @throws NoSuchAlgorithmException
	 */
	protected CertStore getAllCertificateFromStore() throws KeyStoreException, InvalidAlgorithmParameterException, NoSuchAlgorithmException {
		List<X509Certificate> certs = new ArrayList<X509Certificate>();
		Enumeration<String> aliasesEnum = getKeyStore().aliases();
		while(aliasesEnum.hasMoreElements()) {
			String currentAlias = aliasesEnum.nextElement();
			X509Certificate cert = getCertificateFromStore(currentAlias);
			certs.add(cert);
		}
		return PKIXUtils.createCertStoreFromList(certs);
	}
	
	/**
	 * Поиск в хранилище KeyStore сертификата по серийному номеру
	 * @param serialNumber
	 * @return null - если сертификат не найден.
	 * @throws KeyStoreException
	 */
	protected final String lookupKeyStoreBySerialNumber(BigInteger serialNumber) throws KeyStoreException {
		String res = null;
		Enumeration<String> aliasesEnum = getKeyStore().aliases();
		while(aliasesEnum.hasMoreElements()) {
			String currentAlias = aliasesEnum.nextElement();
			X509Certificate cer = getCertificateFromStore(currentAlias);
			if (cer.getSerialNumber().equals(serialNumber) && getKeyStore().isKeyEntry(currentAlias)) {
				res = currentAlias;
				break;
			}
		}
		return res;
	}
	
	/**
	 * Поиск в хранилище KeyStore сертификата по SubjectKeyIdentefer
	 * @param ski
	 * @return null - если сертификат не найден.
	 * @throws KeyStoreException
	 */
	protected final String lookupKeyStoreBySubjectKeyIdentefer(byte[] ski) throws KeyStoreException {
		String res = null;
		Enumeration<String> aliasesEnum = getKeyStore().aliases();
		while(aliasesEnum.hasMoreElements()) {
			String currentAlias = aliasesEnum.nextElement();
			X509Certificate cer = getCertificateFromStore(currentAlias);
			byte[] currentSKI = cer.getExtensionValue(SUBJECT_KEY_IDENTEFER_OID);
			if (Arrays.equals(ski, currentSKI) && getKeyStore().isKeyEntry(currentAlias)) {
				res = currentAlias;
				break;
			}
		}
		return res;
	}
	
	 /**
     * Поиск сертификата.
     * @param stores хранилища сертификатов.
     * @param issuer имя издателя.
     * @param serial серийный номер.
     * @return сертификат.
     * @throws CertStoreException
     */
	protected final X509Certificate lookupCertificateBySerialNumber(final List<CertStore> stores, final X500Principal issuer, final BigInteger serial) throws CertStoreException {

        X509CertSelector csel = new X509CertSelector();
        csel.setIssuer(issuer);
        csel.setSerialNumber(serial);

        return lookupCertificate(stores, csel);
    }
	
	/**
	 * Поиск сертификата по SubjectKeyIdentefer
	 * @param stores
	 * @param subjectKeyIdentefer
	 * @return
	 * @throws CertStoreException
	 */
	protected final X509Certificate lookupCertificateBySubjectKeyIdentefer(final List<CertStore> stores, final byte[] subjectKeyIdentefer) throws CertStoreException {

        X509CertSelector csel = new X509CertSelector();
        csel.setSubjectKeyIdentifier(subjectKeyIdentefer);
        return lookupCertificate(stores, csel);
    }
	
	/**
	 * Поиск в списке CertStore-ов по селектору
	 * @param stores List<CertStore>
	 * @param selector - настроенный X509CertSelector
	 * @return null - если сертификат не найден
	 * @throws CertStoreException
	 */
	protected X509Certificate lookupCertificate(final List<CertStore> stores, final X509CertSelector selector) throws CertStoreException {
        Iterator<CertStore> it = stores.iterator();
        while (it.hasNext()) {
            CertStore store = it.next();
            Collection col = store.getCertificates(selector);
            if (!col.isEmpty()) {
                return (X509Certificate) col.iterator().next();
            }
        }
        return null;
    }
	
	protected static String signerIdentifierToString(ru.CryptoPro.JCP.ASN.CryptographicMessageSyntax.SignerIdentifier sid) {
		StringBuilder sb = new StringBuilder();

		if (sid.getChoiceID() == SignerIdentifier._ISSUERANDSERIALNUMBER) {
			IssuerAndSerialNumber issuerAndSerialNumber = (IssuerAndSerialNumber) sid.getElement();
			BigInteger serialNumber = issuerAndSerialNumber.serialNumber.value;				
			sb.append("SignerIdentifier: SerialNumber=").append(serialNumber);  
			
		} else if (sid.getChoiceID() == SignerIdentifier._SUBJECTKEYIDENTIFIER) {
			SubjectKeyIdentifier subjectKeyIdentifier = (SubjectKeyIdentifier) sid.getElement();
			byte[] ski = subjectKeyIdentifier.value;
			sb.append("SignerIdentifier: SubjectKeyIdentefer = ");
			for (byte skib : ski) {
				sb.append(String.format("%02X ", skib));
			}
		}
		return sb.toString();
	}
	
	protected boolean isBase64(byte[] input) {
		return new String(input).matches("^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)$");
	}
	
	public static final byte[] convertBASE64toDER(final byte[] array) {
		return javax.xml.bind.DatatypeConverter.parseBase64Binary(new String(array));
	}
	
	public static final byte[] convertDERtoBASE64(byte[] array) {
		return javax.xml.bind.DatatypeConverter.printBase64Binary(array).getBytes();
	}
	
	/**
	 * Если приши данные в BASE64, то производится снятие кодировки, иначе - просто возвращает исходные данные.
	 * @param array
	 * @return
	 */
	protected byte[] forceBASE64(byte[] array) {
		if (isBase64(array)) {
			LOG.fine("Performing convertation from BASE64 to bytes");
			return convertBASE64toDER(array);
		} else {
			return  array;
		}
	}
	
	// ------------------------------------------------------------------------------
	
	public static final int ACTION_DECRYPT = 100;
	
	public static final int ACTION_DETACH = 101;
	
	public static final int ACTION_VERIFY = 102;
	
	public static final int ACTION_SIGN = 200;
	
	public static final int ACTION_ENCRYPT = 201;
	
	public byte[] actions(byte[] data, String bufferToFile, int... actions) throws Exception {
		byte[] buffer = data;
		for (int act : actions) {
			switch (act) {
				case ACTION_DECRYPT: {
					buffer = decrypt(buffer);
					break;
				}
				case ACTION_DETACH: {
					buffer = detach(buffer);
					break;
				}
				case ACTION_VERIFY: {
					verify(buffer);
					break;
				}
				case ACTION_SIGN: {
					buffer = signAttached(buffer);
					break;
				}
				case ACTION_ENCRYPT: {
					buffer = encrypt(buffer);
					break;
				}
			}
		}
		// store to file
		if (bufferToFile != null) {
			FileOutputStream fos = null;
			try {
				fos = new FileOutputStream(bufferToFile);
				fos.write(buffer);
				fos.flush();
			} finally {
				if (fos != null) {
					fos.close();
				}
			}
		}
		return buffer;
	}

}
