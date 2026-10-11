package com.ruoyi.boost;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

/** Operator-granted benefits never imply payment, identity or shop certification. */
@Service public class BoostBenefits {
 private final BoostDb db;
 public BoostBenefits(BoostDb db){this.db=db;}
 public String thresholds(){return str(db.one("SELECT config_value FROM boost_config WHERE config_key='player:growth-thresholds'"),"configValue");}
 public Object rules(){return map("thresholds",thresholds(),"mode","MANUAL","membershipPurchaseEnabled",false);}
 @Transactional public Object rules(long admin,Map<String,Object>b){
  String value=text(b,"thresholds",1,1000),expected=text(b,"expectedThresholds",0,1000),note=text(b,"note",2,200);
  String[] parts=value.split(",",-1);BoostException.require(parts.length>=2&&parts.length<=50,"等级阈值需要 2～50 项，从 0 开始递增");
  long previous=-1;List<String> normalized=new ArrayList<>();
  for(String part:parts){long n=number(map("threshold",part.trim()),"threshold",0,1000000000);BoostException.require(n>previous&&(previous!=-1||n==0),"等级阈值必须从 0 开始严格递增");previous=n;normalized.add(Long.toString(n));}
  String current=str(db.one("SELECT config_value FROM boost_config WHERE config_key='player:growth-thresholds' FOR UPDATE"),"configValue");
  if(!current.equals(expected))throw new BoostException(409,"规则已更新，请刷新后重试");
  db.run("UPDATE boost_config SET config_value=? WHERE config_key='player:growth-thresholds'",String.join(",",normalized));
  db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'benefits:rules',0,?)",admin,note+"；阈值="+String.join(",",normalized));return rules();
 }
 @Transactional public Object update(long admin,long member,Map<String,Object>b){
  db.activeMember(member);
  String action=choice(b,"action","membership","growth"),key=text(b,"requestKey",16,64),note=text(b,"note",2,200);
  BoostException.require(key.matches("[A-Za-z0-9_-]+"),"无效的请求编号");
  long expected=number(b,"expectedRevision",0,Long.MAX_VALUE-1),delta=0;String type="",expiry="";Timestamp expires=null;
  if(action.equals("membership")){
   type=choice(b,"membershipType","FREE","MEMBER");
   if(type.equals("MEMBER")){
    expiry=text(b,"expiresAt",19,19);
    try{expires=Timestamp.valueOf(LocalDateTime.parse(expiry));}catch(Exception e){throw new BoostException(400,"请输入有效的会员到期时间（UTC）");}
   }
  }else{delta=number(b,"delta",-1000000000,1000000000);BoostException.require(delta!=0,"成长值调整不能为零");}
  String hash;
  try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((admin+"|"+action+"|"+expected+"|"+type+"|"+expiry+"|"+delta+"|"+note).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  List<Map<String,Object>> prior=db.list("SELECT * FROM boost_benefit_action WHERE member_id=? AND request_key=?",member,key);
  if(!prior.isEmpty()){if(!hash.equals(str(prior.get(0),"requestHash")))throw new BoostException(409,"相同请求编号的内容不同");return map("revision",num(prior.get(0),"revision"));}
  if(expires!=null)BoostException.require(expires.toLocalDateTime().isAfter(LocalDateTime.now())&&expires.toLocalDateTime().isBefore(LocalDateTime.now().plusYears(10)),"到期时间应在当前时间之后的十年以内");
  if(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=?",member)==0)db.run("INSERT INTO boost_player_profile(member_id) VALUES (?)",member);
  Map<String,Object> p=db.one("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",member);
  if(num(p,"benefitsRevision")!=expected)throw new BoostException(409,"玩家资料已更新，请刷新后重试");
  long revision=expected+1;
  if(action.equals("membership"))db.run("UPDATE boost_player_profile SET membership_type=?,membership_expires_at=?,benefits_revision=? WHERE member_id=?",type,expires,revision,member);
  else{
   BoostException.require(!thresholds().isBlank(),"请先配置成长等级阈值");long points=num(p,"growthPoints")+delta;
   BoostException.require(points>=0&&points<=1000000000,"调整后的成长值超出允许范围");
   db.run("UPDATE boost_player_profile SET growth_points=?,benefits_revision=? WHERE member_id=?",points,revision,member);
   db.run("INSERT INTO boost_growth_event(member_id,event_key,points,reason,rule_version) VALUES (?,?,?,?,?)",member,"manual:"+member+":"+key,delta,note,"manual-v1");
  }
  db.run("INSERT INTO boost_benefit_action(member_id,request_key,request_hash,admin_id,revision) VALUES (?,?,?,?,?)",member,key,hash,admin,revision);
  db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,?,?,?)",admin,"benefits:"+action,member,note+"；"+(action.equals("membership")?type+" "+expiry:"成长值 "+delta));
  db.notify(member,"会员与成长资料更新",note,null);return map("revision",revision);
 }
}
