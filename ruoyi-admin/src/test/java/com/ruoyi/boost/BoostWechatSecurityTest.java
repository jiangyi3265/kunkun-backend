package com.ruoyi.boost;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

class BoostWechatSecurityTest {
 @TempDir Path temp;
 BoostWechatGateway gateway;KeyPair pair;final String apiKey="0123456789abcdef0123456789abcdef";ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup()throws Exception{
  KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);pair=gen.generateKeyPair();
  Path pub=temp.resolve("platform.pem"),priv=temp.resolve("merchant.pem");Files.write(pub,pem("PUBLIC KEY",pair.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII));Files.write(priv,pem("PRIVATE KEY",pair.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
  gateway=new BoostWechatGateway();gateway.appId="wx_test";gateway.merchantId="mch_test";gateway.notifyBase="https://merchant.example.test";
  ReflectionTestUtils.setField(gateway,"merchantSerial","merchant_serial");ReflectionTestUtils.setField(gateway,"platformKeyId","PUB_KEY_ID_TEST");ReflectionTestUtils.setField(gateway,"privateKeyPath",priv.toString());ReflectionTestUtils.setField(gateway,"platformKeyPath",pub.toString());ReflectionTestUtils.setField(gateway,"apiKey",apiKey);
 }
 String pem(String type,byte[] bytes){return "-----BEGIN "+type+"-----\n"+Base64.getEncoder().encodeToString(bytes)+"\n-----END "+type+"-----";}
 HttpHeaders headers(String raw,long seconds)throws Exception{String t=String.valueOf(seconds),n="nonce_for_test";Signature s=Signature.getInstance("SHA256withRSA");s.initSign(pair.getPrivate());s.update((t+"\n"+n+"\n"+raw+"\n").getBytes(StandardCharsets.UTF_8));HttpHeaders h=new HttpHeaders();h.set("Wechatpay-Timestamp",t);h.set("Wechatpay-Nonce",n);h.set("Wechatpay-Serial","PUB_KEY_ID_TEST");h.set("Wechatpay-Signature",Base64.getEncoder().encodeToString(s.sign()));return h;}
 String envelope(String key)throws Exception{String nonce="123456789012",aad="transaction";Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));String content=json.writeValueAsString(map("appid","wx_test","out_trade_no","DL_TEST","amount",map("total",12000,"currency","CNY")));String cipher=Base64.getEncoder().encodeToString(c.doFinal(content.getBytes(StandardCharsets.UTF_8)));return json.writeValueAsString(map("event_type","TRANSACTION.SUCCESS","resource",map("algorithm","AEAD_AES_256_GCM","nonce",nonce,"associated_data",aad,"ciphertext",cipher)));}
 @Test void validSignatureAndGcmResourceDecode()throws Exception{String raw=envelope(apiKey);Map<String,Object>r=gateway.callback(headers(raw,Instant.now().getEpochSecond()),raw);assertEquals("DL_TEST",r.get("out_trade_no"));assertEquals("TRANSACTION.SUCCESS",r.get("event_type"));}
 @Test void tamperingWithSignedBodyIsRejected()throws Exception{String raw=envelope(apiKey);HttpHeaders h=headers(raw,Instant.now().getEpochSecond());assertThrows(BoostException.class,()->gateway.callback(h,raw+" "));}
 @Test void expiredSignatureIsRejected()throws Exception{String raw=envelope(apiKey);HttpHeaders h=headers(raw,Instant.now().getEpochSecond()-601);assertThrows(BoostException.class,()->gateway.callback(h,raw));}
 @Test void UnknownPlatformKeyIsRejected()throws Exception{String raw=envelope(apiKey);HttpHeaders h=headers(raw,Instant.now().getEpochSecond());h.set("Wechatpay-Serial","ANOTHER_KEY");assertThrows(BoostException.class,()->gateway.callback(h,raw));}
 @Test void ValidSignatureCannotBypassAuthenticatedDecryption()throws Exception{String raw=envelope("fedcba9876543210fedcba9876543210");HttpHeaders h=headers(raw,Instant.now().getEpochSecond());assertThrows(BoostException.class,()->gateway.callback(h,raw));}
 @Test void merchantSignatureAndEncryptedNameRoundTrip()throws Exception{String body="wx_test\n123\nnonce\nprepay_id=test\n";Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(pair.getPublic());verifier.update(body.getBytes(StandardCharsets.UTF_8));assertTrue(verifier.verify(Base64.getDecoder().decode(gateway.sign(body))));Cipher c=Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");c.init(Cipher.DECRYPT_MODE,pair.getPrivate());assertEquals("测试姓名",new String(c.doFinal(Base64.getDecoder().decode(gateway.encryptName("测试姓名"))),StandardCharsets.UTF_8));}
}
