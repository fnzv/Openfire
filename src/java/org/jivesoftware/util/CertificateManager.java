/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2005-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package org.jivesoftware.util;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DEROutputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.PKCS10CertificationRequest;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import org.jivesoftware.openfire.keystore.CertificateStoreConfig;
import org.jivesoftware.openfire.keystore.CertificateStoreConfigException;
import org.jivesoftware.util.cert.CertificateIdentityMapping;
import org.jivesoftware.util.cert.CNCertificateIdentityMapping;
import org.jivesoftware.util.cert.SANCertificateIdentityMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that provides similar functionality to the keytool tool. Generated certificates
 * conform to the XMPP spec where domains are kept in the subject alternative names extension.
 *
 * @author Gaston Dombiak
 */
public class CertificateManager {

	private static final Logger Log = LoggerFactory.getLogger( CertificateManager.class );

    private static Pattern valuesPattern = Pattern.compile("(?i)(=)([^,]*)");


    /**
     * The maximum length of lines in certification requests
     */
    private static final int CERT_REQ_LINE_LENGTH = 76;

    private static List<CertificateEventListener> listeners = new CopyOnWriteArrayList<>();

    private static List<CertificateIdentityMapping> serverCertMapping = new ArrayList<>();
    
    private static List<CertificateIdentityMapping> clientCertMapping = new ArrayList<>();
    
    static {

        String serverCertIdentityMapList = JiveGlobals.getProperty("provider.serverCertIdentityMap.classList");
        if (serverCertIdentityMapList != null) {
        	StringTokenizer st = new StringTokenizer(serverCertIdentityMapList, " ,\t\n\r\f");
            while (st.hasMoreTokens()) {
                String s_provider = st.nextToken();
                try {
                    Class c_provider = ClassUtils.forName(s_provider);
                    CertificateIdentityMapping provider =
                            (CertificateIdentityMapping)(c_provider.newInstance());
                    Log.debug("CertificateManager: Loaded server identity mapping " + s_provider);
                    serverCertMapping.add(provider);
                }
                catch (Exception e) {
                    Log.error("CertificateManager: Error loading CertificateIdentityMapping: " + s_provider + "\n" + e);
                }
            }
        }
        
        if (serverCertMapping.isEmpty()) {
        	Log.debug("CertificateManager: No server CertificateIdentityMapping's found. Loading default mappings");
        	serverCertMapping.add(new SANCertificateIdentityMapping());
        	serverCertMapping.add(new CNCertificateIdentityMapping());   	
        }
                
        String clientCertMapList = JiveGlobals.getProperty("provider.clientCertIdentityMap.classList");
        if (clientCertMapList != null) {
        	StringTokenizer st = new StringTokenizer(clientCertMapList, " ,\t\n\r\f");
            while (st.hasMoreTokens()) {
                String s_provider = st.nextToken();
                try {
                    Class c_provider = ClassUtils.forName(s_provider);
                    CertificateIdentityMapping provider =
                            (CertificateIdentityMapping)(c_provider.newInstance());
                    Log.debug("CertificateManager: Loaded client identity mapping " + s_provider);
                    clientCertMapping.add(provider);
                }
                catch (Exception e) {
                    Log.error("CertificateManager: Error loading CertificateIdentityMapping: " + s_provider + "\n" + e);
                }
            }
        }
        
        if (clientCertMapping.isEmpty()) {
        	Log.debug("CertificateManager: No client CertificateIdentityMapping's found. Loading default mappings");
        	clientCertMapping.add(new CNCertificateIdentityMapping());
        }
    }

    /**
     * @Deprecated Use {@link CertificateStoreConfig#delete(String)} instead.
     */
    @Deprecated
    public static void deleteCertificate(CertificateStoreConfig storeConfig, String alias) throws GeneralSecurityException, IOException, CertificateStoreConfigException
    {
        final KeyStore store = storeConfig.getStore();
        if (!store.containsAlias( alias ) )
        {
            Log.info( "Unable to delete certificate for alias '"+alias+"' from store, as the store does not contain a certificate for that alias." );
            return;
        }

        storeConfig.getStore().deleteEntry( alias );
        storeConfig.persist();

        // Notify listeners that a new certificate has been removed.
        for (CertificateEventListener listener : listeners) {
            try {
                listener.certificateDeleted(store, alias);
            }
            catch (Exception e) {
                Log.warn( "An exception occurred while notifying CertificateEventListener " + listener, e );
            }
        }
    }

    /**
     * Decide whether or not to trust the given supplied certificate chain, returning the
     * End Entity Certificate in this case where it can, and null otherwise.
     * A self-signed certificate will, for example, return null.
     * For certain failures, we SHOULD generate an exception - revocations and the like,
     * but we currently do not.
     *
     * @param chain an array of X509Certificate where the first one is the endEntityCertificate.
     * @param certStore a keystore containing untrusted certificates (including ICAs, etc).
     * @param trustStore a keystore containing Trust Anchors (most-trusted CA certificates).
     * @return trusted end-entity certificate, or null.
     */
    public static X509Certificate getEndEntityCertificate(Certificate chain[],
            KeyStore certStore, KeyStore trustStore) {
        if (chain.length == 0) {
            return null;
        }
        X509Certificate first = (X509Certificate) chain[0];
        try {
            first.checkValidity();
        } catch(CertificateException e) {
            Log.warn("EE Certificate not valid: " + e.getMessage());
            return null;
        }
        if (chain.length == 1
                && first.getSubjectX500Principal().equals(first.getIssuerX500Principal())) {
            // Chain is single cert, and self-signed.
            try {
                if (trustStore.getCertificateAlias(first) != null) {
                    // Interesting case: trusted self-signed cert.
                    return first;
                }
            } catch (KeyStoreException e) {
                Log.warn("Keystore error while looking for self-signed cert; assuming untrusted.");
            }
            return null;
        }
        final List<Certificate> all_certs = new ArrayList<>();
        try {
            // First, load up certStore contents into a CertStore.
            // It's a mystery why these objects are different.
            for (Enumeration<String> aliases = certStore.aliases(); aliases
                    .hasMoreElements();) {
                String alias = aliases.nextElement();
                if (certStore.isCertificateEntry(alias)) {
                    X509Certificate cert = (X509Certificate) certStore
                            .getCertificate(alias);
                    all_certs.add(cert);
                }
            }
            // Now add the trusted certs.
            for (Enumeration<String> aliases = trustStore.aliases(); aliases
                    .hasMoreElements();) {
                String alias = aliases.nextElement();
                if (trustStore.isCertificateEntry(alias)) {
                    X509Certificate cert = (X509Certificate) trustStore
                            .getCertificate(alias);
                    all_certs.add(cert);
                }
            }
            // Finally, add all the certs in the chain:
            for (int i = 0; i < chain.length; ++i) {
                all_certs.add(chain[i]);
            }
            CertStore cs = CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(all_certs));
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(first);
            // / selector.setSubject(first.getSubjectX500Principal());
            PKIXBuilderParameters params = new PKIXBuilderParameters(
                    trustStore, selector);
            params.addCertStore(cs);
            params.setDate(new Date());
            params.setRevocationEnabled(false);
            /* Code here is the right way to do things. */
            CertPathBuilder pathBuilder = CertPathBuilder
                    .getInstance(CertPathBuilder.getDefaultType());
            CertPath cp = pathBuilder.build(params).getCertPath();
            /**
             * This section is an alternative to using CertPathBuilder which is
             * not as complete (or safe), but will emit much better errors. If
             * things break, swap around the code.
             *
             **** COMMENTED OUT. ****
            ArrayList<X509Certificate> ls = new ArrayList<X509Certificate>();
            for (int i = 0; i < chain.length; ++i) {
                ls.add((X509Certificate) chain[i]);
            }
            for (X509Certificate last = ls.get(ls.size() - 1); !last
                    .getIssuerX500Principal().equals(last.getSubjectX500Principal()); last = ls
                    .get(ls.size() - 1)) {
                X509CertSelector sel = new X509CertSelector();
                sel.setSubject(last.getIssuerX500Principal());
                ls.add((X509Certificate) cs.getCertificates(sel).toArray()[0]);
            }
            CertPath cp = CertificateFactory.getInstance("X.509").generateCertPath(ls);
             ****** END ALTERNATIVE. ****
             */
            // Not entirely sure if I need to do this with CertPathBuilder.
            // Can't hurt.
            CertPathValidator pathValidator = CertPathValidator
                    .getInstance("PKIX");
            pathValidator.validate(cp, params);
            return (X509Certificate) cp.getCertificates().get(0);
        } catch (CertPathBuilderException e) {
            Log.warn("Path builder: " + e.getMessage());
        } catch (CertPathValidatorException e) {
            Log.warn("Path validator: " + e.getMessage());
        } catch (Exception e) {
            Log.warn("Unkown exception while validating certificate chain: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the identities of the remote client as defined in the specified certificate. The
     * identities are mapped by the classes in the "provider.clientCertIdentityMap.classList" property. 
     * By default, the subjectDN of the certificate is used.
     *
     * @param x509Certificate the certificate the holds the identities of the remote server.
     * @return the identities of the remote client as defined in the specified certificate.
     */
    public static List<String> getClientIdentities(X509Certificate x509Certificate) {
    	
    	List<String> names = new ArrayList<>();
    	for (CertificateIdentityMapping mapping : clientCertMapping) {
    		List<String> identities = mapping.mapIdentity(x509Certificate);
    		Log.debug("CertificateManager: " + mapping.name() + " returned " + identities.toString());
    		if (!identities.isEmpty()) {
    			names.addAll(identities);
    			break;
    		}
    	}

        return names;
    }
    
    /**
     * Returns the identities of the remote server as defined in the specified certificate. The
     * identities are mapped by the classes in the "provider.serverCertIdentityMap.classList" property.
     * By default, the identities are defined in the subjectDN of the certificate and it can also be 
     * defined in the subjectAltName extensions of type "xmpp". When the extension is being used then the
     * identities defined in the extension are going to be returned. Otherwise, the value stored in
     * the subjectDN is returned.
     *
     * @param x509Certificate the certificate the holds the identities of the remote server.
     * @return the identities of the remote server as defined in the specified certificate.
     */
    public static List<String> getServerIdentities(X509Certificate x509Certificate) {
    	
    	List<String> names = new ArrayList<>();
    	for (CertificateIdentityMapping mapping : serverCertMapping) {
    		List<String> identities = mapping.mapIdentity(x509Certificate);
    		Log.debug("CertificateManager: " + mapping.name() + " returned " + identities.toString());
    		if (!identities.isEmpty()) {
    			names.addAll(identities);
    			break;
    		}
    	}

        return names;
    }

    /**
     * Returns true if an RSA certificate was found in the specified keystore for the specified domain.
     *
     * @param storeConfig the store to use for searching the certificate.
     * @param domain domain of the server signed by the certificate.
     * @return true if an RSA certificate was found in the specified keystore for the specified domain.
     * @throws KeyStoreException
     */
    public static boolean isRSACertificate(CertificateStoreConfig storeConfig, String domain) throws KeyStoreException {
        return isCertificate(storeConfig, domain, "RSA");
    }

    /**
     * Returns true if an DSA certificate was found in the specified keystore for the specified domain.
     *
     * @param storeConfig the store to use for searching the certificate.
     * @param domain domain of the server signed by the certificate.
     * @return true if an DSA certificate was found in the specified keystore for the specified domain.
     * @throws KeyStoreException
     */
    public static boolean isDSACertificate(CertificateStoreConfig storeConfig, String domain) throws KeyStoreException {
        return isCertificate( storeConfig, domain, "DSA" );
    }

    /**
     * Returns true if the specified certificate is using the DSA algorithm. The DSA algorithm is not
     * good for encryption but only for authentication. On the other hand, the RSA algorithm is good
     * for encryption and authentication.
     *
     * @param certificate the certificate to analyze.
     * @return true if the specified certificate is using the DSA algorithm.
     * @throws KeyStoreException
     */
    public static boolean isDSACertificate(X509Certificate certificate) throws KeyStoreException {
        return certificate.getPublicKey().getAlgorithm().equals( "DSA" );
    }

    /**
     * Returns true if a certificate with the specified configuration was found in a certificate store.
     *
     * @param storeConfig the store to use for searching the certificate.
     * @param domain the domain present in the subjectAltName or "*" if anything is accepted.
     * @param algorithm the DSA or RSA algorithm used by the certificate.
     * @return true if a certificate with the specified configuration was found in the key store.
     * @throws KeyStoreException
     */
    private static boolean isCertificate(CertificateStoreConfig storeConfig, String domain, String algorithm) throws KeyStoreException {
    	for (Enumeration<String> aliases = storeConfig.getStore().aliases(); aliases.hasMoreElements();) {
            X509Certificate certificate = (X509Certificate) storeConfig.getStore().getCertificate(aliases.nextElement());

            if ( !certificate.getPublicKey().getAlgorithm().equalsIgnoreCase( algorithm ) ) {
                continue;
            }

            if ("*".equals(domain)) {
                // Any domain certified by the certificate is accepted
                return true;
            }
            else {
                // Only accept certified domains that match the specified domain
                // TODO check that domain=foo.bar does not match identitiy "a.longerfoo.bar"
                for (String identity : getServerIdentities( certificate ) ) {
                    if (identity.endsWith(domain) ) {
                        return true;
                    }
                }
            }
        }

    	return false;
    }

    /**
     * Returns true if the specified certificate is a self-signed certificate.
     *
     * @param keyStore key store that holds the certificate to verify.
     * @param alias alias of the certificate in the key store.
     * @return true if the specified certificate is a self-signed certificate.
     * @throws KeyStoreException if an error happens while usign the keystore
     */
    public static boolean isSelfSignedCertificate(KeyStore keyStore, String alias) throws KeyStoreException {
        // Get certificate chain
        java.security.cert.Certificate[] certificateChain = keyStore.getCertificateChain( alias );
        // Verify that the chain is empty or was signed by himself
        return certificateChain == null || certificateChain.length == 1;
    }

    /**
     * Returns true if the specified certificate is a self-signed certificate. If the certificate
     * was not found in the store then a KeyStoreException is returned.
     *
     * @param keyStore key store that holds the certificate to verify.
     * @param certificate the certificate in the key store.
     * @return true if the specified certificate is a self-signed certificate.
     * @throws KeyStoreException if an error happens while usign the keystore
     */
    public static boolean isSelfSignedCertificate(KeyStore keyStore, X509Certificate certificate) throws KeyStoreException {
        String alias = keyStore.getCertificateAlias( certificate );
        if (alias == null) {
            throw new KeyStoreException("Certificate not found in store: " + certificate);
        }
        return isSelfSignedCertificate(keyStore, alias);
    }

    /**
     * Returns true if the specified certificate is ready to be signed by a Certificate Authority. Self-signed
     * certificates need to get their issuer information entered to be able to generate a Certificate
     * Signing Request (CSR).
     *
     * @param keyStore key store that holds the certificate to verify.
     * @param alias alias of the certificate in the key store.
     * @return true if the specified certificate is ready to be signed by a Certificate Authority.
     * @throws KeyStoreException if an error happens while usign the keystore
     */
    public static boolean isSigningRequestPending(KeyStore keyStore, String alias) throws KeyStoreException {
        // Verify that this is a self-signed certificate
        if (!isSelfSignedCertificate(keyStore, alias)) {
            return false;
        }
        // Verify that the issuer information has been entered
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);
        Matcher matcher = valuesPattern.matcher( certificate.getIssuerDN().toString() );
        return matcher.find() && matcher.find();
    }

    /**
     * Creates and returns the content of a new singing request for the specified certificate. Signing
     * requests are required by Certificate Authorities as part of their signing process. The signing request
     * contains information about the certificate issuer, subject DN, subject alternative names and public key.
     * Private keys are not included. After the Certificate Authority verified and signed the certificate a new
     * certificate is going to be returned. Use {@link #installReply(java.security.KeyStore, java.security.KeyStore, String, String, java.io.InputStream)}
     * to import the CA reply.
     *
     * @param cert the certificate to create a signing request.
     * @param privKey the private key of the certificate.
     * @return the content of a new singing request for the specified certificate.
     */
    public static String createSigningRequest(X509Certificate cert, PrivateKey privKey) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, SignatureException, IOException
    {
        StringBuilder sb = new StringBuilder();

        String subject = cert.getSubjectDN().getName();
        X509Name xname = new X509Name(subject);

        PublicKey pubKey = cert.getPublicKey();

        String signatureAlgorithm = "DSA".equals(pubKey.getAlgorithm()) ? "SHA1withDSA" : "SHA1WITHRSAENCRYPTION";

        PKCS10CertificationRequest csr =
                new PKCS10CertificationRequest(signatureAlgorithm, xname, pubKey, null, privKey);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DEROutputStream deros = new DEROutputStream(baos);
        deros.writeObject(csr.toASN1Primitive());
        String sTmp = new String(org.bouncycastle.util.encoders.Base64.encode(baos.toByteArray()));

        // Header
        sb.append("-----BEGIN NEW CERTIFICATE REQUEST-----\n");

        // Add signing request content (base 64 encoded)
        for (int iCnt = 0; iCnt < sTmp.length(); iCnt += CERT_REQ_LINE_LENGTH) {
            int iLineLength;

            if ((iCnt + CERT_REQ_LINE_LENGTH) > sTmp.length()) {
                iLineLength = sTmp.length() - iCnt;
            } else {
                iLineLength = CERT_REQ_LINE_LENGTH;
            }

            sb.append(sTmp.substring(iCnt, iCnt + iLineLength)).append("\n");
        }

        // Footer
        sb.append("-----END NEW CERTIFICATE REQUEST-----\n");
        return sb.toString();
    }

    /**
     * Installs the Certificate Authority reply returned as part of the signing request. The certificate
     * being signed will get its certificate chain updated with the imported certificate(s). An exception
     * will be thrown if the replied certificate does not match a local certificate or if the signing
     * authority is not known by the server (i.e. keystore and truststore files)
     *
     * The identity of the entity that has signed the reply is verified against the provided trust store.
     *
     * The
     *
     * @param keyStore    key store where the certificate is stored.
     * @param trustStore  key store where ca certificates are stored.
     * @param keyPassword password of the keystore.
     * @param alias the alias of the existing certificate being signed.
     * @param inputStream the stream containing the CA reply.
     * @return true if the CA reply was successfully processed.
     * @throws Exception
     */
    public static boolean installReply(KeyStore keyStore, KeyStore trustStore, String keyPassword, String alias, InputStream inputStream) throws Exception {

        // Check that there is a certificate for the specified alias
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate( alias );
        if (certificate == null) {
            Log.warn("Certificate not found for alias: " + alias);
            return false;
        }
        // Retrieve the private key of the stored certificate
        PrivateKey privKey = (PrivateKey) keyStore.getKey(alias, keyPassword.toCharArray());
        // Load certificates found in the PEM input stream
        Collection<X509Certificate> certs = parseCertificates( inputStream );
        if (certs.isEmpty()) {
            throw new Exception("Reply has no certificates");
        }
        List<X509Certificate> newCerts;
        if (certs.size() == 1) {
            // Reply has only one certificate
            newCerts = establishCertChain(keyStore, trustStore, null, certs.iterator().next());
        } else {
            // Reply has a chain of certificates
            newCerts = validateReply(keyStore, trustStore, alias, null, certs);
        }
        if (newCerts == null)
        {
            return false;
        }
        keyStore.setKeyEntry(alias, privKey, keyPassword.toCharArray(), newCerts.toArray(new X509Certificate[newCerts.size()]));

        // Notify listeners that a new certificate has been created
        for (CertificateEventListener listener : listeners) {
            try {
                listener.certificateSigned( keyStore, alias, newCerts );
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }

        return true;
    }


    /**
     * Imports a new signed certificate and its private key into the keystore. The certificate input
     * stream may contain the signed certificate as well as its CA chain.
     *
     * @param keyStore    key store where the certificate will be stored.
     * @param trustStore  key store where ca certificates are stored.
     * @param keyPassword password of the keystore.
     * @param alias the alias of the the new signed certificate.
     * @param pkInputStream the stream containing the private key.
     * @param passPhrase is the password phrased used when creating the private key.
     * @param inputStream the stream containing the signed certificate.
     * @return true if the certificate was successfully imported.
     * @throws Exception if no certificates were found in the inputStream.
     */
    public static boolean installCert(KeyStore keyStore, KeyStore trustStore, String keyPassword, String alias,
                                      InputStream pkInputStream, final String passPhrase, InputStream inputStream) throws Exception {
        // Check that there is a certificate for the specified alias
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);
        if (certificate != null) {
            Log.warn("Certificate already exists for alias: " + alias);
            return false;
        }

        PrivateKey privKey = parsePrivateKey( pkInputStream, passPhrase );
        Collection<X509Certificate> certs = parseCertificates( inputStream );
        if (certs.isEmpty()) {
            throw new Exception("No certificates were found");
        }
        List<X509Certificate> newCerts;
        if (certs.size() == 1)
        {
            // Reply has only one certificate
            newCerts = establishCertChain(keyStore, trustStore, certificate, certs.iterator().next() );
        }
        else
        {
            // Reply has a chain of certificates
            newCerts = validateReply(keyStore, trustStore, alias, certificate, certs);
        }

        if (newCerts == null)
        {
            return false;
        }
        keyStore.setKeyEntry( alias, privKey, keyPassword.toCharArray(), newCerts.toArray( new X509Certificate[ newCerts.size() ] ) );

        // Notify listeners that a new certificate has been created (and signed)
        for (CertificateEventListener listener : listeners) {
            try {
                listener.certificateCreated( keyStore, alias, newCerts.get( 0 ) );
                if (newCerts.size() > 1) {
                    listener.certificateSigned(keyStore, alias, newCerts);
                }
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }

        return true;
    }

    /**
     * @deprecated Use {@link #parsePrivateKey(String, String)} instead.
     */
    @Deprecated
    public static PrivateKey parsePrivateKey( InputStream pemRepresentation, String passPhrase ) throws IOException
    {
        // see http://stackoverflow.com/questions/309424/read-convert-an-inputstream-to-a-string
        final java.util.Scanner s = new java.util.Scanner( pemRepresentation ).useDelimiter("\\A");
        return parsePrivateKey( s.hasNext() ? s.next() : "", passPhrase );
    }

    /**
     * Parses a PrivateKey instance from a PEM representation.
     *
     * When the provided key is encrypted, the provided pass phrase is applied.
     *
     * @param pemRepresentation a PEM representation of a private key (cannot be null or empty)
     * @param passPhrase optional pass phrase (must be present if the private key is encrypted).
     * @return a PrivateKey instance (never null)
     */
    public static PrivateKey parsePrivateKey( String pemRepresentation, String passPhrase ) throws IOException
    {
        if ( pemRepresentation == null || pemRepresentation.trim().isEmpty() ) {
            throw new IllegalArgumentException( "Argument 'pemRepresentation' cannot be null or an empty String.");
        }
        try ( Reader reader = new StringReader( pemRepresentation.trim() ))
        {
            final Object object = new PEMParser( reader ).readObject();
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider( "BC" );

            final KeyPair kp;

            if ( object instanceof PEMEncryptedKeyPair )
            {
                // Encrypted key - we will use provided password
                final PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().build( passPhrase.toCharArray() );
                kp = converter.getKeyPair( ( (PEMEncryptedKeyPair) object ).decryptKeyPair( decProv ) );
            }
            else
            {
                // Unencrypted key - no password needed
                kp = converter.getKeyPair( (PEMKeyPair) object );
            }
            return kp.getPrivate();
        }
    }

    /**
     * @deprecated Use {@link #parseCertificates(String)} instead.
     */
    @Deprecated
    public static Collection<X509Certificate> parseCertificates( InputStream pemRepresentation ) throws IOException, CertificateException
    {
        // see http://stackoverflow.com/questions/309424/read-convert-an-inputstream-to-a-string
        final java.util.Scanner s = new java.util.Scanner( pemRepresentation ).useDelimiter("\\A");
        return parseCertificates( s.hasNext() ? s.next() : "" );
    }

    /**
     * Parses a certificate chain from a PEM representation.
     *
     * @param pemRepresentation a PEM representation of a certificate or certificate chain (cannot be null or empty)
     * @return A collection of certificates (possibly empty, but never null).
     */
    public static Collection<X509Certificate> parseCertificates( String pemRepresentation ) throws IOException, CertificateException
    {
        if ( pemRepresentation == null || pemRepresentation.trim().isEmpty() ) {
            throw new IllegalArgumentException( "Argument 'pemRepresentation' cannot be null or an empty String.");
        }

        final Collection<X509Certificate> certificates;
        try ( InputStream inputStream = new ByteArrayInputStream( pemRepresentation.trim().getBytes() ) )
        {
            final CertificateFactory certificateFactory = CertificateFactory.getInstance( "X509" );
            return (Collection<X509Certificate>) certificateFactory.generateCertificates( inputStream );
        }
    }

    /**
     * Registers a listener to receive events.
     *
     * @param listener the listener.
     */
    public static void addListener(CertificateEventListener listener) {
        if (listener == null) {
            throw new NullPointerException();
        }
        listeners.add( listener );
    }

    /**
     * Unregisters a listener to receive events.
     *
     * @param listener the listener.
     */
    public static void removeListener(CertificateEventListener listener) {
        listeners.remove( listener );
    }

    private static List<X509Certificate> establishCertChain(KeyStore keyStore, KeyStore trustStore,
                                                                  X509Certificate certificate,
                                                                  X509Certificate certReply)
            throws Exception {
        if (certificate != null) {
            PublicKey publickey = certificate.getPublicKey();
            PublicKey publickey1 = certReply.getPublicKey();
            if (!publickey.equals(publickey1)) {
                throw new Exception("Public keys in reply and keystore don't match");
            }
            if (certReply.equals(certificate)) {
                throw new Exception("Certificate reply and certificate in keystore are identical");
            }
        }
        Map<Principal, List<X509Certificate>> knownCerts = new Hashtable<>();

        // TODO Figure out why we add keystore issuers. This implies that we always trust the issuer of our identitity (which probably is right, but shouldn't be required)
        if (keyStore.size() > 0) {
            knownCerts.putAll(getCertsByIssuer(keyStore));
        }
        if (trustStore.size() > 0) {
            knownCerts.putAll(getCertsByIssuer(trustStore));
        }
        LinkedList<X509Certificate> answer = new LinkedList<>();
        if (buildChain(certReply, answer, knownCerts)) {
            return answer;
        } else {
            throw new Exception("Failed to establish chain from reply");
        }
    }


    /**
     * Builds the certificate chain of the specified certificate based on the known list of certificates
     * that were issued by their respective Principals. Returns true if the entire chain of all certificates
     * was successfully built.
     *
     * @param certificate certificate to build its chain.
     * @param answer      the certificate chain for the corresponding certificate.
     * @param knownCerts  list of known certificates grouped by their issues (i.e. Principals).
     * @return true if the entire chain of all certificates was successfully built.
     */
    private static boolean buildChain(X509Certificate certificate, LinkedList<X509Certificate> answer,
                                      Map<Principal, List<X509Certificate>> knownCerts) {
        Principal subject = certificate.getSubjectDN();
        Principal issuer = certificate.getIssuerDN();
        // Check if the certificate is a root certificate (i.e. was issued by the same Principal that
        // is present in the subject)
        if (subject.equals(issuer)) {
            answer.addFirst(certificate);
            return true;
        }
        // Get the list of known certificates of the certificate's issuer
        List<X509Certificate> issuerCerts = knownCerts.get(issuer);
        if (issuerCerts == null || issuerCerts.isEmpty()) {
            // No certificates were found so building of chain failed
            return false;
        }
        for (X509Certificate issuerCert : issuerCerts) {
            PublicKey publickey = issuerCert.getPublicKey();
            try {
                // Verify the certificate with the specified public key
                certificate.verify(publickey);
                // Certificate was verified successfully so build chain of issuer's certificate
                if (!buildChain(issuerCert, answer, knownCerts)) {
                    return false;
                }
            }
            catch (Exception exception) {
                // Failed to verify certificate
                return false;
            }
        }
        answer.addFirst( certificate );
        return true;
    }

    /**
     * Returns a Map where the key holds the certificate issuers and values the certificates of each issuer.
     *
     * @param ks the keystore to get its certs per issuer.
     * @return a map with the certificates per issuer.
     * @throws Exception
     */
    private static Map<Principal, List<X509Certificate>> getCertsByIssuer(KeyStore ks)
            throws Exception {
        Map<Principal, List<X509Certificate>> answer = new HashMap<>();
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            if (cert != null) {
                Principal subjectDN = cert.getSubjectDN();
                List<X509Certificate> vec = answer.get(subjectDN);
                if (vec == null) {
                    vec = new ArrayList<>();
                    vec.add(cert);
                }
                else {
                    if (!vec.contains(cert)) {
                        vec.add(cert);
                    }
                }
                answer.put(subjectDN, vec);
            }
        }
        return answer;
    }

    /**
     * Orders certificates, starting from the entity to be validated and progressing back toward the CA root.
     *
     * This implementation matches "issuers" to "subjects" of certificates in such a way that "issuer" value of a
     * certificate matches the "subject" value of the next certificate.
     *
     * When certificates are provided that do not belong to the same chain, a CertificateException is thrown.
     *
     * @param certificates an unordered collection of certificates (cannot be null).
     * @return An ordered list of certificates (possibly empty, but never null).
     */
    public static List<X509Certificate> order( Collection<X509Certificate> certificates ) throws CertificateException
    {
        final LinkedList<X509Certificate> orderedResult = new LinkedList<>();

        if ( certificates.isEmpty() ) {
            return orderedResult;
        }

        if (certificates.size() == 1) {
            orderedResult.addAll( certificates );
            return orderedResult;
        }

        final Map<Principal, X509Certificate> byIssuer = new HashMap<>();
        final Map<Principal, X509Certificate> bySubject = new HashMap<>();

        for ( final X509Certificate certificate : certificates ) {
            final Principal issuer = certificate.getIssuerDN();
            final Principal subject = certificate.getSubjectDN();

            if ( byIssuer.put( issuer, certificate ) != null ) {
                throw new CertificateException( "The provided input should not contain multiple certificates with identical issuerDN values." );
            }
            if ( bySubject.put( subject, certificate ) != null ) {
                throw new CertificateException( "The provided input should not contain multiple certificates with identical subjectDN values." );
            }
        }

        // The first certificate will have a 'subject' value that's not an 'issuer' of any other chain.
        X509Certificate first = null;
        for ( Map.Entry<Principal, X509Certificate> entry : bySubject.entrySet() ) {
            final Principal subject = entry.getKey();
            final X509Certificate certificate = entry.getValue();

            if ( ! byIssuer.containsKey( subject ) ) {
                if (first == null) {
                    first = certificate;
                } else {
                    throw new CertificateException( "The provided input should not contain more than one certificates that has a subjectDN value that's not equal to the issuerDN value of another certificate." );
                }
            }
        }

        if (first == null) {
            throw new CertificateException( "The provided input should contain a certificates that has a subjectDN value that's not equal to the issuerDN value of any other certificate." );
        }

        orderedResult.add( first );

        // With the first certificate in hand, every following certificate should have a subject that's equal to the previous issuer value.
        X509Certificate next = bySubject.get( first.getIssuerDN() );
        while (next != null) {
            orderedResult.add( next );
            next = bySubject.get( next.getIssuerDN() );
        }

        // final check
        if (orderedResult.size() != certificates.size()) {
            throw new CertificateException( "Unable to recreate a certificate chain from the provided input." );
        }

        return orderedResult;
    }

    /**
     * Validates chain in certification reply, and returns the ordered
     * elements of the chain (with user certificate first, and root
     * certificate last in the array).
     *
     * @param alias the alias name
     * @param userCert the user certificate of the alias
     * @param certs the chain provided in the reply
     */
    private static List<X509Certificate> validateReply(KeyStore keyStore, KeyStore trustStore, String alias,
                                                             X509Certificate userCert, Collection<X509Certificate> certs)
            throws Exception {
        List<X509Certificate> replyCerts = new ArrayList<>(certs);
        // order the certs in the reply (bottom-up).
        int i;
        X509Certificate tmpCert;
        if (userCert != null) {
            PublicKey userPubKey = userCert.getPublicKey();
            for (i = 0; i < replyCerts.size(); i++) {
                if (userPubKey.equals(replyCerts.get(i).getPublicKey())) {
                    break;
                }
            }
            if (i == replyCerts.size()) {
                throw new Exception(
                        "Certificate reply does not contain public key for <alias>: " + alias);
            }

            tmpCert = replyCerts.get(0);
            replyCerts.set(0, replyCerts.get(i));
            replyCerts.set(i, tmpCert);
        }

        Principal issuer = replyCerts.get(0).getIssuerDN();

        for (i = 1; i < replyCerts.size() - 1; i++) {
            // find a cert in the reply whose "subject" is the same as the
            // given "issuer"
            int j;
            for (j = i; j < replyCerts.size(); j++) {
                Principal subject = replyCerts.get(j).getSubjectDN();
                if (subject.equals(issuer)) {
                    tmpCert = replyCerts.get(i);
                    replyCerts.set(i, replyCerts.get(j));
                    replyCerts.set(j, tmpCert);
                    issuer = replyCerts.get(i).getIssuerDN();
                    break;
                }
            }
            if (j == replyCerts.size()) {
                throw new Exception("Incomplete certificate chain in reply");
            }
        }

        // now verify each cert in the ordered chain
        for (i = 0; i < replyCerts.size() - 1; i++) {
            PublicKey pubKey = replyCerts.get(i + 1).getPublicKey();
            try {
                replyCerts.get(i).verify(pubKey);
            }
            catch (Exception e) {
                throw new Exception(
                        "Certificate chain in reply does not verify: " + e.getMessage());
            }
        }

        // do we trust the (root) cert at the top?
        X509Certificate topCert = replyCerts.get(replyCerts.size() - 1);
        boolean foundInKeyStore = keyStore.getCertificateAlias(topCert) != null;
        boolean foundInCAStore =  trustStore.getCertificateAlias(topCert) != null;
        if (!foundInKeyStore && !foundInCAStore) {
            boolean verified = false;
            X509Certificate rootCert = null;
            for (Enumeration<String> aliases = trustStore.aliases(); aliases.hasMoreElements();) {
                String name = aliases.nextElement();
                rootCert = (X509Certificate) trustStore.getCertificate(name);
                if (rootCert != null) {
                    try {
                        topCert.verify(rootCert.getPublicKey());
                        verified = true;
                        break;
                    }
                    catch (Exception e) {
                        // Ignore
                    }
                }
            }
            if (!verified) {
                return null;
            }
            else {
                // Check if the cert is a self-signed cert
                if (!topCert.getSubjectDN().equals(topCert.getIssuerDN())) {
                    // append the (self-signed) root CA cert to the chain
                    replyCerts.add(rootCert);
                }
            }
        }

        return replyCerts;
    }

    /**
     * Creates an X509 version3 certificate.
     *
     * @param kp           KeyPair that keeps the public and private keys for the new certificate.
     * @param months       time to live
     * @param issuerDN     Issuer string e.g "O=Grid,OU=OGSA,CN=ACME"
     * @param subjectDN    Subject string e.g "O=Grid,OU=OGSA,CN=John Doe"
     * @param domain       Domain of the server.
     * @param signAlgoritm Signature algorithm. This can be either a name or an OID.
     * @return X509 V3 Certificate
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public static synchronized X509Certificate createX509V3Certificate(KeyPair kp, int months, String issuerDN,
                                                                        String subjectDN, String domain,
                                                                        String signAlgoritm)
            throws GeneralSecurityException, IOException {
        PublicKey pubKey = kp.getPublic();
        PrivateKey privKey = kp.getPrivate();

        byte[] serno = new byte[8];
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed((new Date().getTime()));
        random.nextBytes(serno);
        BigInteger serial = (new java.math.BigInteger(serno)).abs();

        X509V3CertificateGenerator certGenerator = new X509V3CertificateGenerator();
        certGenerator.reset();

        certGenerator.setSerialNumber(serial);
        certGenerator.setIssuerDN(new X509Name(issuerDN));
        certGenerator.setNotBefore(new Date(System.currentTimeMillis()));
        certGenerator.setNotAfter(
                new Date(System.currentTimeMillis() + months * (1000L * 60 * 60 * 24 * 30)));
        certGenerator.setSubjectDN(new X509Name(subjectDN));
        certGenerator.setPublicKey(pubKey);
        certGenerator.setSignatureAlgorithm(signAlgoritm);

        // Generate the subject alternative name
        boolean critical = subjectDN == null || "".equals(subjectDN.trim());
        ASN1Sequence othernameSequence = new DERSequence(new ASN1Encodable[]{
                new DERObjectIdentifier("1.3.6.1.5.5.7.8.5"), new DERTaggedObject(true, 0, new DERUTF8String(domain))});
        GeneralName othernameGN = new GeneralName(GeneralName.otherName, othernameSequence);
        GeneralNames subjectAltNames = new GeneralNames(new GeneralName[]{othernameGN});
        // Add subject alternative name extension
        certGenerator.addExtension(X509Extensions.SubjectAlternativeName, critical, subjectAltNames);

        X509Certificate cert =
                certGenerator.generateX509Certificate(privKey, "BC", new SecureRandom());
        cert.checkValidity(new Date());
        cert.verify(pubKey);

        return cert;
    }
}
