package com.ruoyi.boost;

import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

/** Codes never leave through API responses or logs; member and admin purposes are not interchangeable. */
@Service public class BoostPhone {
 private final BoostDb db;private final BoostSmsGateway gateway;private final BoostRateLimits limits;
 private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();private final SecureRandom random=new SecureRandom();
 public BoostPhone(BoostDb d,BoostSmsGateway g,BoostRateLimits l){db=d;gateway=g;limits=l;}
 public boolean ready(){return gateway.ready();}
 public static String mask(String phone){return phone!=null&&phone.matches("1[3-9][0-9]{9}")?phone.substring(0,3)+"****"+phone.substring(7):"";}
 public Map<String,Object> info(long member){List<Map<String,Object>> rows=db.list("SELECT phone,verified_at FROM boost_member_phone WHERE member_id=?",member);return rows.isEmpty()?map("phoneVerified",false,"phoneMasked",""):map("phoneVerified",true,"phoneMasked",mask(str(rows.get(0),"phone")),"phoneVerifiedAt",rows.get(0).get("verifiedAt"));}
 public String verifiedPhone(long member){List<Map<String,Object>> rows=db.list("SELECT phone FROM boost_member_phone WHERE member_id=? FOR UPDATE",member);if(rows.isEmpty())throw new BoostException(403,"请先验证手机号码");return str(rows.get(0),"phone");}
 private void memberMayBind(long member){
  BoostException.require(db.count("SELECT COUNT(*) FROM boost_member_phone WHERE member_id=?",member)==0,"手机号已验证，如需更改请联系平台");
  BoostException.require(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=? AND (shop_status<>'NONE' OR shop_submitted_at IS NOT NULL)",member)==0,"店铺材料已提交，手机号由后台核验后更改");
 }
 @Transactional(noRollbackFor=BoostException.class) public Map<String,Object> send(long member,Map<String,Object> body,String purpose,String ip){
  if(!ready())throw new BoostException(503,"短信服务尚未配置，暂不能验证手机号");
  String phone=text(body,"phone",11,11);BoostException.require(phone.matches("1[3-9][0-9]{9}"),"请输入正确的中国大陆手机号码");
  limits.hit("sms:ip:"+ip,10,3600);limits.hit("sms:phone-minute:"+phone,1,60);limits.hit("sms:phone-day:"+phone,5,86400);limits.hit("sms:member:"+member,5,3600);
  db.activeMember(member);if("MEMBER".equals(purpose))memberMayBind(member);
  String id=UUID.randomUUID().toString(),code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
  db.run("UPDATE boost_phone_challenge SET status='EXPIRED' WHERE member_id=? AND purpose=? AND status IN ('SENT','VERIFIED')",member,purpose);
  db.run("INSERT INTO boost_phone_challenge(id,member_id,purpose,phone,code_hash,expires_at) VALUES (?,?,?,?,?,?)",id,member,purpose,phone,encoder.encode(code),Timestamp.from(Instant.now().plusSeconds(300)));
  try{gateway.send(phone,code,id);}catch(BoostException e){db.run("UPDATE boost_phone_challenge SET status='FAILED' WHERE id=?",id);throw e;}
  db.run("UPDATE boost_phone_challenge SET status='SENT' WHERE id=?",id);
  return map("challengeId",id,"phoneMasked",mask(phone),"expiresIn",300,"retryAfter",60);
 }
 // Wrong attempts commit independently of successful submissions, so retries cannot reset the five-attempt limit.
 @Transactional(noRollbackFor=BoostException.class) public Map<String,Object> verify(long member,Map<String,Object> body,String purpose){
  db.activeMember(member);String id=text(body,"challengeId",36,36),code=text(body,"code",6,6);BoostException.require(code.matches("[0-9]{6}"),"验证码应为 6 位数字");
  List<Map<String,Object>> rows=db.list("SELECT * FROM boost_phone_challenge WHERE id=? AND member_id=? AND purpose=? FOR UPDATE",id,member,purpose);
  if(rows.isEmpty())throw new BoostException(400,"验证码无效或不属于当前操作");Map<String,Object> row=rows.get(0);
  if(!"MEMBER".equals(purpose)&&"VERIFIED".equals(str(row,"status"))&&LocalDateTime.parse(str(row,"expiresAt")).isAfter(LocalDateTime.now())&&encoder.matches(code,str(row,"codeHash")))return map("phoneVerified",true,"phoneMasked",mask(str(row,"phone")),"challengeId",id);
  BoostException.require("SENT".equals(str(row,"status"))&&LocalDateTime.parse(str(row,"expiresAt")).isAfter(LocalDateTime.now())&&num(row,"attempts")<5,"验证码已失效，请重新获取");
  db.run("UPDATE boost_phone_challenge SET attempts=attempts+1 WHERE id=?",id);
  if(!encoder.matches(code,str(row,"codeHash")))throw new BoostException(400,"验证码不正确");
  if("MEMBER".equals(purpose)){memberMayBind(member);bind(member,str(row,"phone"));}
  db.run("UPDATE boost_phone_challenge SET status=? WHERE id=?","MEMBER".equals(purpose)?"USED":"VERIFIED",id);
  return map("phoneVerified",true,"phoneMasked",mask(str(row,"phone")),"challengeId",id);
 }
 private void bind(long member,String phone){
  BoostException.require(db.count("SELECT COUNT(*) FROM boost_member_phone WHERE phone=? AND member_id<>?",phone,member)==0,"该手机号已绑定其他账号，请联系平台核查");
  try{
   if(db.count("SELECT COUNT(*) FROM boost_member_phone WHERE member_id=?",member)==0)db.run("INSERT INTO boost_member_phone(member_id,phone) VALUES (?,?)",member,phone);
   else db.run("UPDATE boost_member_phone SET phone=?,verified_at=CURRENT_TIMESTAMP WHERE member_id=?",phone,member);
  }catch(org.springframework.dao.DuplicateKeyException e){throw new BoostException(409,"该手机号已绑定其他账号，请联系平台核查");}
 }
 /** Called inside the audited shop-edit transaction. */
 public void consumeAdmin(long member,long operator,String ticket){
  List<Map<String,Object>> rows=db.list("SELECT * FROM boost_phone_challenge WHERE id=? AND member_id=? AND purpose=? FOR UPDATE",ticket,member,"ADMIN_"+operator);
  BoostException.require(!rows.isEmpty(),"请先核验新的手机号");Map<String,Object> row=rows.get(0);
  BoostException.require("VERIFIED".equals(str(row,"status"))&&LocalDateTime.parse(str(row,"expiresAt")).isAfter(LocalDateTime.now()),"手机号核验已过期，请重新验证");
  bind(member,str(row,"phone"));db.run("UPDATE boost_phone_challenge SET status='USED' WHERE id=?",ticket);
 }
}
