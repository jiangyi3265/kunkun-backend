package com.ruoyi.boost;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import static com.ruoyi.boost.BoostDb.*;

/** WeChat API v3: signed requests, verified responses, verified/decrypted callbacks.
 * The platform public key ID is explicitly pinned; key rotation is an operator action. */
@Component
public class BoostWechatGateway {
    public void ensureConfigured(){configured();try{BoostException.require(((java.security.interfaces.RSAKey)privateKey()).getModulus().bitLength()>=2048&&((java.security.interfaces.RSAKey)publicKey()).getModulus().bitLength()>=2048,"微信支付密钥长度不足");}catch(BoostException e){throw e;}catch(Exception e){throw new BoostException(503,"商户或微信平台密钥文件不可读取，请核对正式配置");}}
    @Value("${boost.wechat.app-id:}") public String appId;
    @Value("${boost.wechat.app-secret:}") private String appSecret;
    @Value("${boost.wechat.merchant-id:}") public String merchantId;
    @Value("${boost.wechat.merchant-serial:}") private String merchantSerial;
    @Value("${boost.wechat.private-key:}") private String privateKeyPath;
    @Value("${boost.wechat.platform-key:}") private String platformKeyPath;
    @Value("${boost.wechat.platform-key-id:}") private String platformKeyId;
    @Value("${boost.wechat.api-v3-key:}") private String apiKey;
    @Value("${boost.wechat.notify-base:}") public String notifyBase;
    @Value("${boost.wechat.transfer-scene-id:}") public String transferScene;
    @Value("${boost.wechat.transfer-reports:[]}") private String transferReports;
    private final RestTemplate http=new RestTemplateBuilder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json=new ObjectMapper();
    private void configured(){BoostException.require(!appId.isEmpty()&&!merchantId.isEmpty()&&!merchantSerial.isEmpty()&&!privateKeyPath.isEmpty()&&!platformKeyPath.isEmpty()&&!platformKeyId.isEmpty()&&apiKey.length()==32&&notifyBase.startsWith("https://"),"微信支付参数尚未配置完整");}
    private byte[] pem(String path)throws Exception{return Base64.getDecoder().decode(new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.US_ASCII).replaceAll("-----[^-]+-----","").replaceAll("\\s", ""));}
    private PrivateKey privateKey()throws Exception{return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(privateKeyPath)));}
    private PublicKey publicKey()throws Exception{return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(platformKeyPath)));}
    public String sign(String text){try{Signature s=Signature.getInstance("SHA256withRSA");s.initSign(privateKey());s.update(text.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(s.sign());}catch(Exception e){throw new BoostException(503,"商户签名密钥不可用，请联系平台");}}
    public void verify(HttpHeaders headers,String raw) {
        configured();String time=headers.getFirst("Wechatpay-Timestamp"),nonce=headers.getFirst("Wechatpay-Nonce"),sig=headers.getFirst("Wechatpay-Signature"),serial=headers.getFirst("Wechatpay-Serial");
        try{
            BoostException.require(platformKeyId.equals(serial)&&time!=null&&nonce!=null&&sig!=null,"微信签名信息不完整");
            BoostException.require(Math.abs(Instant.now().getEpochSecond()-Long.parseLong(time))<=300,"微信通知已过期");
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey());verifier.update((time+"\n"+nonce+"\n"+raw+"\n").getBytes(StandardCharsets.UTF_8));
            BoostException.require(verifier.verify(Base64.getDecoder().decode(sig)),"微信签名校验失败");
        }catch(BoostException e){throw e;}catch(Exception e){throw new BoostException(400,"微信签名校验失败");}
    }
    public Map<String,Object> call(String path,HttpMethod method,Map<String,Object> payload){
        configured();try{
            String body=payload==null?"":json.writeValueAsString(payload),time=String.valueOf(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");
            String signature=sign(method.name()+"\n"+path+"\n"+time+"\n"+nonce+"\n"+body+"\n");
            HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));h.set("Wechatpay-Serial",platformKeyId);
            h.set("Authorization","WECHATPAY2-SHA256-RSA2048 mchid=\""+merchantId+"\",nonce_str=\""+nonce+"\",timestamp=\""+time+"\",serial_no=\""+merchantSerial+"\",signature=\""+signature+"\"");
            ResponseEntity<String> r=http.exchange("https://api.mch.weixin.qq.com"+path,method,new HttpEntity<>(body,h),String.class);
            String raw=r.getBody()==null?"":r.getBody();verify(r.getHeaders(),raw);return raw.isEmpty()?new LinkedHashMap<>():json.readValue(raw,Map.class);
        }catch(org.springframework.web.client.HttpStatusCodeException e){
            try{verify(e.getResponseHeaders(),e.getResponseBodyAsString());Map<String,Object> error=json.readValue(e.getResponseBodyAsString(),Map.class);String code=str(error,"code");if(!code.matches("[A-Z_]{1,60}"))code="PROVIDER_ERROR";throw new BoostProviderException(e.getStatusCode().value(),code,true);}
            catch(BoostProviderException verified){throw verified;}catch(Exception untrusted){throw new BoostProviderException(e.getStatusCode().value(),"UNVERIFIED_RESPONSE",false);}
        }catch(BoostException e){throw e;}catch(Exception e){throw new BoostException(503,"微信未能确认请求结果，请保留原订单稍后重试");}
    }
    public String openid(String code){
        BoostException.require(!appId.isEmpty()&&!appSecret.isEmpty(),"微信小程序登录参数尚未配置");
        try{
            String url=UriComponentsBuilder.fromUriString("https://api.weixin.qq.com/sns/jscode2session").queryParam("appid",appId).queryParam("secret",appSecret).queryParam("js_code",code).queryParam("grant_type","authorization_code").build().encode().toUriString();
            Map<String,Object> r=json.readValue(http.getForObject(url,String.class),Map.class);String id=str(r,"openid");BoostException.require(!id.isEmpty(),"微信登录已失效，请重试");return id;
        }catch(BoostException e){throw e;}catch(Exception e){throw new BoostException(503,"微信登录服务暂时不可用");}
    }
    public Map<String,Object> callback(HttpHeaders h,String raw){
        verify(h,raw);try{
            Map<String,Object> envelope=json.readValue(raw,Map.class),r=(Map<String,Object>)envelope.get("resource");
            BoostException.require("AEAD_AES_256_GCM".equals(str(r,"algorithm")),"不支持的通知算法");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,str(r,"nonce").getBytes(StandardCharsets.UTF_8)));c.updateAAD(str(r,"associated_data").getBytes(StandardCharsets.UTF_8));
            Map<String,Object> data=json.readValue(c.doFinal(Base64.getDecoder().decode(str(r,"ciphertext"))),Map.class);data.put("event_type",envelope.get("event_type"));return data;
        }catch(BoostException e){throw e;}catch(Exception e){throw new BoostException(400,"微信通知解密失败");}
    }
    public String encryptName(String name){try{Cipher c=Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");c.init(Cipher.ENCRYPT_MODE,publicKey());return Base64.getEncoder().encodeToString(c.doFinal(name.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new BoostException(503,"微信收款信息加密失败");}}
    public Object transferReports(){try{return json.readValue(transferReports,List.class);}catch(Exception e){throw new BoostException(503,"商家转账场景报备配置无效");}}
}
