package com.ruoyi.boost;

import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import static com.ruoyi.boost.BoostDb.*;

/** Tencent FaceID API adapter. No client-supplied success flag can verify an identity. */
@Service
public class BoostIdentityGateway {
    static final String HOST="faceid.tencentcloudapi.com",CONTENT_TYPE="application/json; charset=utf-8";
    @Value("${boost.identity.secret-id:}") private String secretId="";
    @Value("${boost.identity.secret-key:}") private String secretKey="";
    @Value("${boost.identity.rule-id:}") private String ruleId="";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper();
    public boolean ready(){return !secretId.isBlank()&&!secretKey.isBlank()&&!ruleId.isBlank();}
    private static String hex(byte[] value){return java.util.HexFormat.of().formatHex(value);}
    private static String sha(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static byte[] mac(byte[] key,String value)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(value.getBytes(StandardCharsets.UTF_8));}
    static String authorization(String id,String key,String host,String service,String payload,long timestamp)throws Exception{
        String date=Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toLocalDate().toString(),scope=date+"/"+service+"/tc3_request";
        String canonical="POST\n/\n\ncontent-type:"+CONTENT_TYPE+"\nhost:"+host+"\n\ncontent-type;host\n"+sha(payload);
        String toSign="TC3-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+sha(canonical);
        byte[] signing=mac(mac(mac(("TC3"+key).getBytes(StandardCharsets.UTF_8),date),service),"tc3_request");
        return "TC3-HMAC-SHA256 Credential="+id+"/"+scope+", SignedHeaders=content-type;host, Signature="+hex(mac(signing,toSign));
    }
    @SuppressWarnings("unchecked") protected Map<String,Object> call(String action,Map<String,Object> payload){
        if(!ready())throw new BoostException(503,"实名认证服务尚未开通，请稍后再试");
        try{
            String body=json.writeValueAsString(payload);long timestamp=Instant.now().getEpochSecond();
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://"+HOST+"/")).timeout(Duration.ofSeconds(15))
                .header("Content-Type",CONTENT_TYPE).header("Authorization",authorization(secretId,secretKey,HOST,"faceid",body,timestamp))
                .header("X-TC-Action",action).header("X-TC-Version","2018-03-01").header("X-TC-Timestamp",String.valueOf(timestamp)).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200||response.body().length()>1024*1024)throw new BoostException(503,"实名认证服务暂时不可用，请稍后重试");
            Object root=json.readValue(response.body(),Map.class).get("Response");
            if(!(root instanceof Map))throw new BoostException(503,"实名认证返回内容异常，请稍后重试");
            Map<String,Object> result=(Map<String,Object>)root;
            if(result.get("Error")!=null)throw new BoostException(503,"实名认证服务未完成本次请求，请稍后重试");
            return result;
        }catch(BoostException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BoostException(503,"实名认证请求中断，请重试");}
        catch(Exception e){throw new BoostException(503,"实名认证服务连接失败，请稍后重试");}
    }
    public Map<String,Object> start(String sessionId,Map<String,Object> input){
        Map<String,Object> payload=map("RuleId",ruleId,"Extra",sessionId);
        String name=BoostInput.text(input,"realName",0,32),card=BoostInput.text(input,"idCard",0,18).toUpperCase(Locale.ROOT);
        if(!name.isBlank())payload.put("Name",name);
        if(!card.isBlank()){BoostException.require(card.matches("[1-9][0-9]{16}[0-9X]"),"身份证号码格式不正确");payload.put("IdCard",card);}
        Map<String,Object> result=call("DetectAuth",payload);
        BoostException.require(!str(result,"BizToken").isBlank()&&str(result,"BizToken").length()<=128,"核验凭证异常");
        String url=str(result,"Url");
        if(!url.isBlank()){
            URI target=URI.create(url);BoostException.require("https".equals(target.getScheme())&&Arrays.asList("faceid.qq.com","open.weixin.qq.com").contains(target.getHost()),"核验链接来源异常");
        }
        return result;
    }
    public Map<String,Object> result(String token){
        Map<String,Object> response=call("GetDetectInfoEnhanced",map("RuleId",ruleId,"BizToken",token,"InfoType","1"));
        if(Boolean.TRUE.equals(response.get("IsVerifyIntention"))){String mode=str(response,"IntentionVerifyType");String info="2".equals(mode)?"15":Arrays.asList("0","1").contains(mode)?"16":"";if(!info.isEmpty())return call("GetDetectInfoEnhanced",map("RuleId",ruleId,"BizToken",token,"InfoType",info));}
        return response;
    }
    public static boolean verified(Map<String,Object> response,String sessionId){
        // Missing or mistyped intention metadata cannot prove a complete provider check.
        if(!(response.get("IsVerifyIntention") instanceof Boolean))return false;
        Object raw=response.get("Text");if(!(raw instanceof Map))return false;
        @SuppressWarnings("unchecked") Map<String,Object> text=(Map<String,Object>)raw;
        if(!(text.get("ErrCode") instanceof Number)||num(text,"ErrCode")!=0||!(text.get("LiveStatus") instanceof Number)||num(text,"LiveStatus")!=0||!(text.get("Comparestatus") instanceof Number)||num(text,"Comparestatus")!=0)return false;
        if(!"权威库".equals(str(text,"CompareLibType"))||!sessionId.equals(str(text,"Extra"))||!str(text,"IdCard").matches("[1-9][0-9]{16}[0-9X]")||str(text,"Name").isBlank())return false;
        if(Boolean.TRUE.equals(response.get("IsVerifyIntention"))){
            String type=str(response,"IntentionVerifyType"),section="0".equals(type)?"IntentionQuestionResult":"1".equals(type)?"IntentionActionResult":"2".equals(type)?"IntentionVerifyData":"";
            Object intention=response.get(section);if(!(intention instanceof Map))return false;
            @SuppressWarnings("unchecked") Map<String,Object> details=(Map<String,Object>)intention;
            if("0".equals(type)){if(!"0".equals(details.get("FinalResultCode")))return false;}
            else{String field="2".equals(type)?"ErrorCode":"FinalResultDetailCode";if(!(details.get(field) instanceof Number)||num(details,field)!=0)return false;}
        }return true;
    }
}
