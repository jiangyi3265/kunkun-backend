package com.ruoyi.boost;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.ruoyi.boost.BoostDb.*;

/** Tencent Cloud 2021-01-11. A successful HTTP response alone does not mean the SMS was accepted. */
@Service public class BoostSmsGateway {
 @Value("${boost.sms.secret-id:}") private String secretId="";
 @Value("${boost.sms.secret-key:}") private String secretKey="";
 @Value("${boost.sms.app-id:}") private String appId="";
 @Value("${boost.sms.sign-name:}") private String signName="";
 @Value("${boost.sms.template-id:}") private String templateId="";
 @Value("${boost.sms.region:ap-guangzhou}") private String region="ap-guangzhou";
 private final ObjectMapper json=new ObjectMapper();
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 public boolean ready(){return !secretId.isBlank()&&!secretKey.isBlank()&&!appId.isBlank()&&!signName.isBlank()&&!templateId.isBlank();}
 @SuppressWarnings("unchecked") public void send(String phone,String code,String requestId){
  if(!ready())throw new BoostException(503,"短信服务尚未配置，暂不能验证手机号");
  try{
   String host="sms.tencentcloudapi.com",body=json.writeValueAsString(map("PhoneNumberSet",List.of("+86"+phone),"SmsSdkAppId",appId,"SignName",signName,"TemplateId",templateId,"TemplateParamSet",List.of(code),"SessionContext",requestId));long timestamp=Instant.now().getEpochSecond();
   HttpRequest request=HttpRequest.newBuilder(URI.create("https://"+host+"/")).timeout(Duration.ofSeconds(12))
    .header("Content-Type",BoostIdentityGateway.CONTENT_TYPE).header("Authorization",BoostIdentityGateway.authorization(secretId,secretKey,host,"sms",body,timestamp))
    .header("X-TC-Action","SendSms").header("X-TC-Version","2021-01-11").header("X-TC-Region",region).header("X-TC-Timestamp",String.valueOf(timestamp))
    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
   HttpResponse<String> result=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
   if(result.statusCode()!=200||result.body().length()>65536)throw new IllegalStateException();
   Object response=json.readValue(result.body(),Map.class).get("Response");
   if(!(response instanceof Map)||!accepted((Map<String,Object>)response,phone))throw new IllegalStateException();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new BoostException(503,"短信请求中断，请稍后重试");}
  catch(Exception e){throw new BoostException(503,"短信暂未发送成功，请稍后重试");}
 }
 static boolean accepted(Map<String,Object> response,String phone){
  if(response.containsKey("Error")||!(response.get("SendStatusSet") instanceof List))return false;
  List<?> statuses=(List<?>)response.get("SendStatusSet");if(statuses.size()!=1||!(statuses.get(0) instanceof Map))return false;
  Map<?,?> result=(Map<?,?>)statuses.get(0);return "Ok".equals(result.get("Code"))&&("+86"+phone).equals(result.get("PhoneNumber"))&&result.get("SerialNo") instanceof String&&!((String)result.get("SerialNo")).isBlank();
 }
}
