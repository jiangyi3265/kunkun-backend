package com.ruoyi.boost;

import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

/** Opaque sessions are bound to their authenticated owner; only an authoritative server lookup verifies. */
@Service
public class BoostIdentity {
    private final BoostDb db;private final BoostIdentityGateway gateway;
    @Value("${boost.identity.subject-hash-key:}") private String hashKey="";
    public BoostIdentity(BoostDb db,BoostIdentityGateway gateway){this.db=db;this.gateway=gateway;}
    public boolean ready(){return gateway.ready()&&hashKey.length()>=32;}
    private void requireReady(){if(!ready())throw new BoostException(503,"实名认证服务尚未开通，完成真实核验后才可发布");}
    private String subject(String card){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return java.util.HexFormat.of().formatHex(m.doFinal(card.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Identity hashing unavailable");}}
    @Transactional public Map<String,Object> start(long member,Map<String,Object> input){
        db.activeMember(member);requireReady();BoostException.require(Boolean.TRUE.equals(input.get("accepted")),"请先同意实名认证信息处理说明");
        if(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=? AND realname_status='VERIFIED'",member)>0)throw new BoostException(409,"当前账号已完成实名认证");
        List<Map<String,Object>> pending=db.list("SELECT * FROM boost_identity_session WHERE member_id=? AND status='PENDING' AND expires_at>? ORDER BY created_at DESC",member,Timestamp.from(Instant.now()));
        if(!pending.isEmpty())return session(pending.get(0));
        String id=UUID.randomUUID().toString();Map<String,Object> created=gateway.start(id,input);
        db.run("INSERT INTO boost_identity_session(id,member_id,provider,provider_token,auth_url,expires_at) VALUES (?,?,'TENCENT_FACEID',?,?,?)",id,member,str(created,"BizToken"),str(created,"Url"),Timestamp.from(Instant.now().plusSeconds(7200)));
        return session(db.one("SELECT * FROM boost_identity_session WHERE id=?",id));
    }
    private Map<String,Object> session(Map<String,Object> row){return map("verificationId",str(row,"id"),"bizToken",str(row,"providerToken"),"authUrl",str(row,"authUrl"),"expiresAt",row.get("expiresAt"),"status",str(row,"status"));}
    @Transactional public Map<String,Object> finish(long member,Map<String,Object> input){
        db.activeMember(member);requireReady();String id=text(input,"verificationId",8,64);
        List<Map<String,Object>> rows=db.list("SELECT * FROM boost_identity_session WHERE id=? AND member_id=? FOR UPDATE",id,member);
        if(rows.isEmpty())throw new BoostException(403,"核验流程不属于当前账号");Map<String,Object> row=rows.get(0);
        if("VERIFIED".equals(str(row,"status")))return map("status","VERIFIED");
        BoostException.require("PENDING".equals(str(row,"status"))&&LocalDateTime.parse(str(row,"expiresAt")).isAfter(LocalDateTime.now()),"核验流程已结束或过期，请重新开始");
        Map<String,Object> result=gateway.result(str(row,"providerToken"));
        if(!BoostIdentityGateway.verified(result,id))return map("status","PENDING","message","尚未取得有效的实名核验通过结果，请完成核验后重试");
        @SuppressWarnings("unchecked") Map<String,Object> info=(Map<String,Object>)result.get("Text");String subject=subject(str(info,"IdCard"));
        if(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=?",member)==0)db.run("INSERT INTO boost_player_profile(member_id) VALUES (?)",member);
        Map<String,Object> profile=db.one("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",member);
        BoostException.require(str(profile,"identitySubjectHash").isBlank()||subject.equals(str(profile,"identitySubjectHash")),"当前账号不能更换实名身份，请联系平台");
        db.run("UPDATE boost_identity_session SET status='VERIFIED',provider_request_id=?,verified_at=CURRENT_TIMESTAMP WHERE id=?",str(result,"RequestId"),id);
        db.run("UPDATE boost_player_profile SET realname_status='VERIFIED',realname_provider='TENCENT_FACEID',identity_subject_hash=?,realname_verified_at=CURRENT_TIMESTAMP WHERE member_id=?",subject,member);
        db.notify(member,"实名认证已通过","你已完成真实实名核验，可发布求购；出售道具还需通过店铺认证。",null);return map("status","VERIFIED");
    }
}
