package com.luxoft.pki.tools;

import static com.luxoft.pki.tools.PKIXUtils.chechValidDate;
import static com.luxoft.pki.tools.PKIXUtils.downloadCRLFromWebDP;
import static com.luxoft.pki.tools.PKIXUtils.getAuthorityInformationAccess;
import static com.luxoft.pki.tools.PKIXUtils.getCrlDistributionPoints;
import static com.luxoft.pki.tools.PKIXUtils.isIBMJ9;
import static com.luxoft.pki.tools.PKIXUtils.isIbmCRLDPEnabled;
import static com.luxoft.pki.tools.PKIXUtils.isIndirectCRL;
import static com.luxoft.pki.tools.PKIXUtils.isOCSPEnabled;
import static com.luxoft.pki.tools.PKIXUtils.isSelfSigned;
import static com.luxoft.pki.tools.PKIXUtils.isSunCRLDPEnabled;
import static com.luxoft.pki.tools.PKIXUtils.isX509Certificate;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertPathValidatorResult;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class for building a certification chain for given certificate and verifying
 * it. Relies on a set of root CA certificates and intermediate certificates
 * that will be used for building the certification chain. The verification
 * process assumes that all self-signed certificates in the set are trusted root
 * CA certificates and all other certificates in the set are intermediate
 * certificates.
 * 
 * @author Svetlin Nakov and some modified by Igor Konovalov
 */
public class CertificateVerifier {
	
	private static final String CERT_BUILDER_ALG_PKIX = "PKIX";

	private static final Logger LOG = Logger.getLogger(CertificateVerifier.class.getName());
		
	
	/**
	 * ���������� ������� ������������ � �� �������� � CRLDP �(���) OCSP
	 * @param cert - ����������� ����������
	 * @param keyStore - ��������� ������, �� �������� �������� �����������. ��� �������� ����������� ����� ��������� � TrustedAnchors, � ��������� � �������������.
	 * @param allowSelfSigned - true - ���������, ��� ������ �������� ����� ��������������� ������������.
	 * @return CertificateVerificationResult
	 * @throws CertificateVerificationException
	 */
	public static CertificateVerificationResult verifyCertificate(X509Certificate cert, KeyStore keyStore, boolean allowSelfSigned) throws CertificateVerificationException {
		Set<X509Certificate> allStoredCerts = new HashSet<X509Certificate>();
		try {
			Enumeration<String> aliases = keyStore.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				Certificate c = keyStore.getCertificate(alias);
				if (!isX509Certificate(c)) {
					continue;
				}
				try {
					chechValidDate(c);
				} catch (CertificateExpiredException cee) {
					LOG.fine(alias + " expired " + cee.getMessage());
					continue;
				} catch (CertificateNotYetValidException cnyve) {
					LOG.fine(alias + " not yet valide " + cnyve.getMessage());
					continue;
				}
				allStoredCerts.add((X509Certificate) c);
			}
		} catch (KeyStoreException e) {
			throw new CertificateVerificationException("Key store access problem.", e);
		}
       
        return verifyCertificate(cert, allStoredCerts, allowSelfSigned);
	}

	

	/**
	 * Attempts to build a certification chain for given certificate and to
	 * verify it. Relies on a set of root CA certificates and intermediate
	 * certificates that will be used for building the certification chain. The
	 * verification process assumes that all self-signed certificates in the set
	 * are trusted root CA certificates and all other certificates in the set
	 * are intermediate certificates.
	 * 
	 * @param cert
	 *            - certificate for validation
	 * @param additionalCerts
	 *            - set of trusted root CA certificates that will be used as
	 *            "trust anchors" and intermediate CA certificates that will be
	 *            used as part of the certification chain. All self-signed
	 *            certificates are considered to be trusted root CA
	 *            certificates. All the rest are considered to be intermediate
	 *            CA certificates.
	 * @param allowSelfSigned - ��������� ��������������� �����������
	 * @return the certification chain (if verification is successful)
	 * @throws CertificateVerificationException
	 *             - if the certification is not successful (e.g. certification
	 *             path cannot be built or some certificate in the chain is
	 *             expired or CRL checks are failed)
	 */
	public static CertificateVerificationResult verifyCertificate(X509Certificate cert, Set<X509Certificate> additionalCerts, boolean allowSelfSigned) throws CertificateVerificationException {
		try {
			// check for valid date
			chechValidDate(cert);
			// Check for self-signed certificate
			if (!allowSelfSigned && isSelfSigned(cert)) {
				if (LOG.isLoggable(Level.FINE)) {
					LOG.log(Level.FINE, "Certificate " + cert.getSubjectDN().getName() + " is self-signed and not allowed. Exception in air!");
				}
				throw new CertificateVerificationException("Self-signed certificates are not allowed.");
			}

			// Prepare a set of trusted root CA certificates
			// and a set of intermediate certificates
			Set<X509Certificate> trustedRootCerts = new HashSet<X509Certificate>();
			Set<X509Certificate> intermediateCerts = new HashSet<X509Certificate>();
			for (X509Certificate additionalCert : additionalCerts) {
				if (isSelfSigned(additionalCert)) {
					trustedRootCerts.add(additionalCert);
					if (LOG.isLoggable(Level.FINE)) {
						LOG.fine("Certificate " + additionalCert.getSubjectDN().getName() + " added as trusted certificate");
					}
				} else {
					intermediateCerts.add(additionalCert);
				}
			}
			
			// Attempt to build the certification chain
			PKIXCertPathBuilderResult verifiedCertChain = buildCertificateChain(cert, trustedRootCerts, intermediateCerts);

			// Check whether the certificate is revoked by the CRL
			// given in its CRL distribution point extension
			CertPathValidatorResult validatedCertChain = null;
			if (!isIBMJ9()) { // non-IBM VMs
				if (isOCSPEnabled() || isSunCRLDPEnabled()) {
					validatedCertChain = verifyCertificateCRLsAutomatic(cert, verifiedCertChain.getCertPath(), trustedRootCerts, intermediateCerts);
				}
			} else { // for IBM J9
				boolean certHasOCSPUrls = getAuthorityInformationAccess(cert).size() > 0;
				boolean certHasCRLDPUrls = getCrlDistributionPoints(cert).size() > 0;
				if (isOCSPEnabled() && certHasOCSPUrls) {
					if (LOG.isLoggable(Level.FINE)) {
						LOG.log(Level.FINE, "Switch OCSP check to automatic mode for IBM VM.");
					} 
					validatedCertChain = verifyCertificateCRLsAutomatic(cert, verifiedCertChain.getCertPath(), trustedRootCerts, intermediateCerts);
				} else if (isIbmCRLDPEnabled() && certHasCRLDPUrls) {
					if (LOG.isLoggable(Level.FINE)) {
						LOG.log(Level.FINE, "Switch CRLDP check to manual mode for IBM VM. IBMs CRLDP enabled and certificate has CRLDP urls.");
					} 
					validatedCertChain = verifyCertificateCRLsManually(cert, additionalCerts);
				}
				
			}
			
			// The chain is built and verified. Return it as a result
			return new CertificateVerificationResult(verifiedCertChain, validatedCertChain);
			
		} catch (CertPathBuilderException certPathEx) {
			
			throw new CertificateVerificationException("Error building certification path: " + cert.getSubjectX500Principal() + ". " + certPathEx.getMessage(), certPathEx);
			
		} catch (CertificateVerificationException cvex) {
			
			throw cvex;
			
		} catch (Exception ex) {

			throw new CertificateVerificationException("Error verifying the certificate: " + cert.getSubjectX500Principal() + ". " + ex.getMessage(), ex);
			
		}
	}

	/**
	 * Attempts to build a certification chain for given certificate and to
	 * verify it. Relies on a set of root CA certificates (trust anchors) and a
	 * set of intermediate certificates (to be used as part of the chain).
	 * 
	 * @param cert
	 *            - certificate for validation
	 * @param trustedRootCerts
	 *            - set of trusted root CA certificates
	 * @param intermediateCerts
	 *            - set of intermediate certificates
	 * @return the certification chain (if verification is successful)
	 * @throws GeneralSecurityException
	 *             - if the verification is not successful (e.g. certification
	 *             path cannot be built or some certificate in the chain is
	 *             expired)
	 */
	private static PKIXCertPathBuilderResult buildCertificateChain(X509Certificate cert, Set<X509Certificate> trustedRootCerts, Set<X509Certificate> intermediateCerts) throws GeneralSecurityException {

		// Create the selector that specifies the starting certificate
		X509CertSelector selector = new X509CertSelector();
		selector.setCertificate(cert);

		// Create the trust anchors (set of root CA certificates)
		Set<TrustAnchor> trustAnchors = new HashSet<TrustAnchor>();
		for (X509Certificate trustedRootCert : trustedRootCerts) {
			trustAnchors.add(new TrustAnchor(trustedRootCert, null));
		}

		// Configure the PKIX certificate builder algorithm parameters
		PKIXBuilderParameters pkixParams = new PKIXBuilderParameters(trustAnchors, selector);

		// Disable CRL checks (this is done manually as additional step)
		pkixParams.setRevocationEnabled(false);

		// Specify a list of intermediate certificates
		CertStore intermediateCertStore = CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediateCerts));
		pkixParams.addCertStore(intermediateCertStore);

		// Build and verify the certification chain
		CertPathBuilder builder = CertPathBuilder.getInstance(CERT_BUILDER_ALG_PKIX);
		PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult) builder.build(pkixParams);
		return result;
	}
	
	/**
	 * �������������� ��������� ����������� ����� ����� ��������� OCSP � CRLDP � ������� CertPathValidator
	 * @param cert - ����������, ������� ����� ��������� � CRL
	 * @param certPath - ������� ������������ ���������� �� ������� ��� �� ���������
	 * @param trustedRootCerts - ����� ���������� ������������
	 * @param intermediateCerts - ����� ������������� ������������ ��� ���������� ������� (�� ��� ����� ����� API � java � ���� �����, ������ ��� �� ������� ������ ������� ������� trusted � intermid �����������)
	 * @return
	 * @throws CertPathValidatorException
	 * @throws InvalidAlgorithmParameterException
	 * @throws NoSuchAlgorithmException
	 */
	private static CertPathValidatorResult verifyCertificateCRLsAutomatic(X509Certificate cert, CertPath certPath, Set<X509Certificate> trustedRootCerts, Set<X509Certificate> intermediateCerts) throws CertPathValidatorException, InvalidAlgorithmParameterException, NoSuchAlgorithmException {

		// Create the selector that specifies the starting certificate
		X509CertSelector selector = new X509CertSelector();
		selector.setCertificate(cert);

		// Create the trust anchors (set of root CA certificates)
		Set<TrustAnchor> trustAnchors = new HashSet<TrustAnchor>();
		for (X509Certificate trustedRootCert : trustedRootCerts) {
			trustAnchors.add(new TrustAnchor(trustedRootCert, null));
		}
		
		// Configure the PKIX certificate builder algorithm parameters
		PKIXBuilderParameters pkixParams = new PKIXBuilderParameters(trustAnchors, selector);

		// Enable CRL checks
		pkixParams.setRevocationEnabled(true);
		
		// Specify a list of intermediate certificates
		CertStore intermediateCertStore = CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediateCerts));
		pkixParams.addCertStore(intermediateCertStore);

		final CertPathValidator validator = CertPathValidator.getInstance(CERT_BUILDER_ALG_PKIX);
		final CertPathValidatorResult validationResult = validator.validate(certPath, pkixParams);
		return validationResult;
	}
	
	public static class LocalCertPathValidatorResult implements CertPathValidatorResult {
		@Override
		public Object clone() {
			return this;
		}
		
	}
	
	/**
	 * �������� ����������� � CRLDP �� ����� CertPathValidator, � ������� � X509CRL. ��� ���� ����������� CRL �� ���� ������������ � ��� �������.
	 * � ���� ������ �� ����������� ���������� CRLDP � ����������� �� ����. ����� ��� ������������� � X509CRL � � ��� ��� ����������� �� ������������.
	 * @param cert - ���������� ��� ��������.
	 * @param certsForCRLSignatureValidation - ����������� ��� �������� ������� ������ CRL
	 * @return
	 * @throws CertificateVerificationException
	 */
	private static CertPathValidatorResult verifyCertificateCRLsManually(X509Certificate cert, Set<X509Certificate> certsForCRLSignatureValidation) throws CertificateVerificationException {
		Date currentDate = new Date();
		try {
			List<String> crlDistPoints = getCrlDistributionPoints(cert);
			boolean crldpURLaccepted = false;
			for (String crlDPointURL : crlDistPoints) { // iterate over CRL distribution points
				LOG.log(Level.FINE, "Downloading CRL: " + crlDPointURL );
				X509CRL crl = null;
				try {
					crl = downloadCRLFromWebDP(crlDPointURL);
					crldpURLaccepted = true;
				} catch (java.net.ConnectException connectionProblem) {
					LOG.severe("Problem occured with CRL " +crlDPointURL + " because " + connectionProblem.getMessage() + " and we going th the next CRLDP... (if failorev available)");
					continue;
				}
				// we dont support indirect crls
				boolean indirectCRL = isIndirectCRL(crl);
				if (indirectCRL) {
					LOG.log(Level.INFO, "We don't support indirect CRLs and skip this " + crlDPointURL);
				} else {
					LOG.log(Level.FINE, "This is direct CRL. We can check certificate.");
				}
				
				// check CRL valid dates -----
				Date nextUpdate = crl.getNextUpdate();
				Date thisUpdate = crl.getThisUpdate();
				boolean validRevoDates = currentDate.after(thisUpdate) && currentDate.before(nextUpdate);
				if (!validRevoDates) {
					throw new CertificateVerificationException("CRL " + crlDPointURL + " is out of date. Next update " + nextUpdate + ", thisUpdate " + thisUpdate);
				}
				
				// verify crl signature ------
				Iterator<X509Certificate> certIterator = certsForCRLSignatureValidation.iterator();
				List<X509Certificate> potencialCRLCerts = new ArrayList<X509Certificate>(5);
				while (certIterator.hasNext()) { // ����� ����� ������ ����������� ����������, ��� ��������� ���������� ����������� ��� ���� ������������
					X509Certificate certIterated = certIterator.next();
					if (certIterated.getSubjectDN().equals(crl.getIssuerDN())) {
						potencialCRLCerts.add(certIterated);
					}
				}
				if (potencialCRLCerts.size() == 0) {
					throw new CertificateVerificationException("CRL " + crlDPointURL + " not verified. Principal of CRL not found in store. May be you don't have CRL's issuer cert in store.");
				} else {
					boolean crlSignatureValidated = false;
					X509Certificate crlsTrueCert = null;
					for (X509Certificate crlCert : potencialCRLCerts) {
						try {
							crl.verify(crlCert.getPublicKey());
							crlSignatureValidated = true;
							crlsTrueCert = crlCert;
							if (LOG.isLoggable(Level.FINE)) {
								LOG.fine(crl.getIssuerDN().getName() + " verified with " + crlCert.getSubjectDN().getName());
							}
							break;
						} catch (java.security.SignatureException signatureException) {
							LOG.fine(signatureException.getMessage() + " for " + crlCert.getSubjectDN().getName());
						} catch (Exception e) {
							if (LOG.isLoggable(Level.FINE)) {
								LOG.fine(e.getClass().getName() + " problem with CRL: " + e.getMessage() + " for " + crlCert.getSubjectDN().getName());
							}
						}
					}
					if (!crlSignatureValidated) {
						throw new CertificateVerificationException(crlDPointURL + " signature invalid. May be you don't have CRL's issuer cert in store.");
					} else {
						if (LOG.isLoggable(Level.FINE)) {
							LOG.fine("CRL " + crlDPointURL + " signature validated with " + crlsTrueCert.getSubjectDN().getName() + " Serial: " + crlsTrueCert.getSerialNumber());
						}
					}
				}
				//----------------------------
				// � ��� ������ �������� �� ������������ �����������
				if (crl.isRevoked(cert)) {
					throw new CertificateVerificationException("The certificate is revoked by CRL: " + crlDPointURL);
				} else {
					break; // ���������� ���� �� CRL DP - �� ��� ��������� ���������� �� ������������.
				}
			}
			if (!crldpURLaccepted) {
				throw new java.net.ConnectException("No one accessible CRLDP url");
			}
			return new LocalCertPathValidatorResult();
		} catch (Exception ex) {
			if (ex instanceof CertificateVerificationException) {
				throw (CertificateVerificationException) ex;
			} else {
				throw new CertificateVerificationException("Can not verify certificate " + cert.getSubjectX500Principal() + " in CRL. "+ ex.getClass().getName() + " say: "  + ex.getMessage());
			}
		}
	}
	
	
	
	

}