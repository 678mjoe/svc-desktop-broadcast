package page.mjoe.svcbroadcast;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;

/** Durable private CA; leaf rotation never changes the sender's trust anchor. */
final class TlsIdentity {
    private static final char[] PASSWORD = new char[0]; // Files, including the keystore, have owner-only access.
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final SecureRandom RANDOM = new SecureRandom();
    final UUID serverId;
    final X509Certificate ca;
    final X509Certificate leaf;
    final SslContext context;

    private TlsIdentity(X509Certificate ca, X509Certificate leaf, PrivateKey key) throws Exception {
        this.ca=ca;
        this.leaf=leaf;
        serverId=UUID.nameUUIDFromBytes(ca.getEncoded());
        context=SslContextBuilder.forServer(key,leaf,ca).sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3","TLSv1.2").build();
    }
    String caPem() throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(ca.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
    static synchronized TlsIdentity load(Path directory, String host, Clock clock) throws Exception {
        validateHost(host);
        PrivateFiles.directory(directory);
        Path file=directory.resolve("identity.p12");
        KeyStore store=KeyStore.getInstance("PKCS12");
        boolean fresh=!Files.exists(file,LinkOption.NOFOLLOW_LINKS);
        if (fresh) store.load(null,PASSWORD);
        else store.load(new ByteArrayInputStream(PrivateFiles.read(file,131072)),PASSWORD);
        Instant now=clock.instant();
        if (fresh) {
            // A missing identity must not silently replace a previously paired server.
            if (Files.exists(directory.resolve("identity-created"),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Server identity is missing");
            KeyPair root=keyPair();
            X509Certificate ca=certificate(root.getPublic(),new X500Name("CN=SVC Broadcast Private CA"),
                    root.getPrivate(),null,now,now.plus(Duration.ofDays(3650)),null);
            store.setKeyEntry("ca",root.getPrivate(),PASSWORD,new java.security.cert.Certificate[]{ca});
        }
        X509Certificate ca=(X509Certificate)store.getCertificate("ca");
        PrivateKey caKey=(PrivateKey)store.getKey("ca",PASSWORD);
        if (ca==null || caKey==null || ca.getBasicConstraints()<0) throw new IOException("Invalid server identity");
        ca.checkValidity(Date.from(now));
        ca.verify(ca.getPublicKey());
        // Verify private/public correspondence even when no renewal is needed.
        Signature proof=Signature.getInstance("SHA256withECDSA");
        proof.initSign(caKey); proof.update(new byte[]{1,2,3}); byte[] signed=proof.sign();
        proof.initVerify(ca.getPublicKey()); proof.update(new byte[]{1,2,3});
        if (!proof.verify(signed)) throw new IOException("Invalid server identity");
        X509Certificate leaf=(X509Certificate)store.getCertificate("server");
        PrivateKey leafKey=(PrivateKey)store.getKey("server",PASSWORD);
        if (!fresh && (leaf==null || leafKey==null)) throw new IOException("Invalid service identity");
        boolean renew=fresh || leaf.getNotAfter().toInstant().isBefore(now.plus(Duration.ofDays(30))) || !matches(leaf,host);
        if (!fresh) {
            leaf.verify(ca.getPublicKey());
            if (leaf.getNotBefore().toInstant().isAfter(now)) throw new IOException("Service identity not yet valid");
        }
        if (renew) {
            Instant end=now.plus(Duration.ofDays(90));
            if (ca.getNotAfter().toInstant().isBefore(end)) throw new IOException("CA rotation requires pairing again");
            KeyPair pair=keyPair();
            leaf=certificate(pair.getPublic(),new X500Name("CN=SVC Broadcast"),caKey,ca,now,end,host);
            leafKey=pair.getPrivate();
            store.setKeyEntry("server",leafKey,PASSWORD,new java.security.cert.Certificate[]{leaf,ca});
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            store.store(out,PASSWORD);
            PrivateFiles.write(file,out.toByteArray());
        }
        if (fresh || !Files.exists(directory.resolve("identity-created"),LinkOption.NOFOLLOW_LINKS))
            PrivateFiles.write(directory.resolve("identity-created"),new byte[]{1});
        leaf.checkValidity(Date.from(now));
        proof.initSign(leafKey);proof.update(new byte[]{4,5,6});signed=proof.sign();
        proof.initVerify(leaf.getPublicKey());proof.update(new byte[]{4,5,6});
        if (!proof.verify(signed)) throw new IOException("Invalid service private key");
        return new TlsIdentity(ca,leaf,leafKey);
    }
    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"),RANDOM);
        return generator.generateKeyPair();
    }
    private static X509Certificate certificate(PublicKey publicKey, X500Name name, PrivateKey signer,
            X509Certificate issuer, Instant now, Instant end, String host) throws Exception {
        X500Name issuerName=issuer==null ? name : new X500Name(issuer.getSubjectX500Principal().getName());
        var builder=new JcaX509v3CertificateBuilder(issuerName,new BigInteger(159,RANDOM).add(BigInteger.ONE),
                Date.from(now.minusSeconds(300)),Date.from(end),name,publicKey);
        var util=new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(host==null));
        builder.addExtension(Extension.keyUsage,true,new KeyUsage(host==null ? KeyUsage.keyCertSign|KeyUsage.cRLSign : KeyUsage.digitalSignature));
        builder.addExtension(Extension.subjectKeyIdentifier,false,util.createSubjectKeyIdentifier(publicKey));
        builder.addExtension(Extension.authorityKeyIdentifier,false,issuer==null ? util.createAuthorityKeyIdentifier(publicKey) : util.createAuthorityKeyIdentifier(issuer));
        if (host!=null) {
            builder.addExtension(Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(isIp(host) ? GeneralName.iPAddress : GeneralName.dNSName,host)));
        }
        var signed=builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(PROVIDER).build(signer));
        return new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(signed);
    }
    private static boolean isIp(String host) { return host.contains(":") || host.matches("[0-9.]+"); }
    static void validateHost(String host) {
        if (host==null || host.isBlank() || host.length()>253 || !host.matches("[a-zA-Z0-9.:-]+")) throw new IllegalArgumentException("Invalid advertised host");
        if (isIp(host)) try { InetAddress.getByName(host); } catch (Exception e) { throw new IllegalArgumentException("Invalid IP address"); }
        else if (!host.matches("[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?")) throw new IllegalArgumentException("Invalid DNS name");
    }
    private static boolean matches(X509Certificate cert,String host) throws Exception {
        for (var san:cert.getSubjectAlternativeNames())
            if ((int)san.get(0)==(isIp(host)?7:2) && (isIp(host) ? InetAddress.getByName((String)san.get(1)).equals(InetAddress.getByName(host)) : host.equalsIgnoreCase((String)san.get(1)))) return true;
        return false;
    }
}
