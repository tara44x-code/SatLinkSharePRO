package com.satlink.sharepro;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.FileInputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * هوية التشفير لهذا الجهاز: مفتاح وشهادة محفوظة في Android Keystore (لا يمكن استخراج المفتاح).
 * الاتصال يتشفر بـ TLS، والتحقق من الجهاز الآخر يكون بالرمز ذو الأربع أرقام أول مرة ثم بالبصمة.
 */
public final class TlsIdentity {
    private static final String ALIAS = "satlink_tls_identity";
    private static SSLContext serverContext;
    private static byte[] certDer;

    private TlsIdentity() {}

    public static synchronized boolean available(Context context) {
        try { load(context); return true; } catch (Exception e) { return false; }
    }

    public static synchronized byte[] certificate(Context context) throws Exception {
        load(context);
        return certDer;
    }

    /** يحوّل اتصالاً عادياً (بعد قراءة رأس البروتوكول) إلى اتصال مشفر كخادم. */
    public static SSLSocket wrapServer(Context context, Socket plain) throws Exception {
        load(context);
        SSLSocketFactory f = serverContext.getSocketFactory();
        SSLSocket s = (SSLSocket) f.createSocket(plain, plain.getInetAddress().getHostAddress(), plain.getPort(), true);
        s.setUseClientMode(false);
        s.startHandshake();
        return s;
    }

    /** اتصال مشفر كعميل. البصمة المتوقعة (إن وُجدت) تُفحص بعد المصافحة. */
    public static SSLSocket wrapClient(Socket plain, String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new RecordingTrustManager()}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
        s.setUseClientMode(true);
        s.startHandshake();
        return s;
    }

    public static byte[] peerCertificate(SSLSocket s) throws Exception {
        Certificate[] chain = s.getSession().getPeerCertificates();
        return chain[0].getEncoded();
    }

    /**
     * شهادة المستلم ذاتية التوقيع، فلا يمكن فحصها بسلسلة شهادات. الحماية من الانتحال تأتي من
     * رمز التحقق (يعتمد على الشهادة + رقم عشوائي من المرسل) ثم من تثبيت البصمة للأجهزة الموثوقة.
     */
    private static final class RecordingTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("client certificates are not used");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("no certificate");
            chain[0].checkValidity();
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static void load(Context context) throws Exception {
        if (serverContext != null) return;
        KeyStore ks;
        String testStore = System.getProperty("satlink.keystore");
        if (testStore != null) {
            // اختبارات على الحاسبة فقط (خارج أندرويد).
            ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(testStore)) { ks.load(in, "satlink".toCharArray()); }
            String alias = ks.aliases().nextElement();
            certDer = ks.getCertificate(alias).getEncoded();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "satlink".toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            serverContext = ctx;
            return;
        }
        if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("Keystore غير مدعوم");
        ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 20);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=SatLink " + TrustStore.deviceId(context)))
                    .setCertificateSerialNumber(new BigInteger(64, new java.security.SecureRandom()))
                    .setCertificateNotBefore(new java.util.Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(end.getTime())
                    .build());
            g.generateKeyPair();
        }
        final X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        final java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey(ALIAS, null);
        certDer = cert.getEncoded();
        // مدير مفاتيح بسيط يقدّم مفتاح Keystore مباشرة (أكثر توافقاً من KeyManagerFactory مع AndroidKeyStore).
        javax.net.ssl.X509ExtendedKeyManager km = new javax.net.ssl.X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, java.security.Principal[] i) { return null; }
            @Override public String chooseClientAlias(String[] t, java.security.Principal[] i, Socket s) { return null; }
            @Override public String[] getServerAliases(String t, java.security.Principal[] i) { return new String[]{ALIAS}; }
            @Override public String chooseServerAlias(String t, java.security.Principal[] i, Socket s) { return ALIAS; }
            @Override public String chooseEngineServerAlias(String t, java.security.Principal[] i, javax.net.ssl.SSLEngine e) { return ALIAS; }
            @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
            @Override public java.security.PrivateKey getPrivateKey(String a) { return key; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new javax.net.ssl.KeyManager[]{km}, null, null);
        serverContext = ctx;
    }
}
