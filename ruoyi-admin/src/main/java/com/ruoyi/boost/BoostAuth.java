package com.ruoyi.boost;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service
public class BoostAuth {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    private final BoostDb db;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostRateLimits rateLimits;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostCampaign campaign;
    @org.springframework.beans.factory.annotation.Value("${boost.simulated-payment:false}") private boolean demoCoupons;
    @org.springframework.beans.factory.annotation.Value("${boost.live:false}") private boolean live;
    @org.springframework.beans.factory.annotation.Value("${boost.policy.version:development}") private String policyVersion="development";
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final Map<String,long[]> attempts=new ConcurrentHashMap<>();
    public BoostAuth(BoostDb db){this.db=db;}
    private String hash(String s) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString(); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public String encode(String password){ return encoder.encode(password); }
    public long member(HttpServletRequest r) {
        String token=r.getHeader("X-Boost-Token");
        if(token==null || token.length()!=64) throw new BoostException(401,"请先登录");
        List<Map<String,Object>> rows=db.list("SELECT m.id FROM boost_session s JOIN boost_member m ON m.id=s.member_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND m.status='ACTIVE'",hash(token));
        if(rows.isEmpty()) throw new BoostException(401,"登录已失效，请重新登录");
        return num(rows.get(0),"id");
    }
    public Long optional(HttpServletRequest r) { try{return member(r);}catch(BoostException e){return null;} }
    @Transactional
    public Map<String,Object> register(Map<String,Object> b) {
        String user=text(b,"username",3,32),password=text(b,"password",8,64),nick=text(b,"nickname",2,24);
        BoostException.require(user.matches("[A-Za-z0-9_]{3,32}"),"账号仅支持字母、数字、下划线");
        BoostException.require(db.count("SELECT COUNT(*) FROM boost_member WHERE username=?",user)==0,"账号已被注册");
        BoostException.require(password.getBytes(StandardCharsets.UTF_8).length<=72,"密码的 UTF-8 长度不能超过 72 字节");
        if(live)BoostException.require(policyVersion.equals(str(b,"acceptedPolicy")),"请阅读并同意当前版本协议");
        long id=db.insert("INSERT INTO boost_member(username,password_hash,nickname,accepted_policy,accepted_at) VALUES (?,?,?,?,?)",user,encode(password),nick,policyVersion.equals(str(b,"acceptedPolicy"))?policyVersion:null,policyVersion.equals(str(b,"acceptedPolicy"))?Timestamp.from(Instant.now()):null);
        BoostPlayerMarket.userNo(id); // Capacity failure rolls back registration before welcome messages or rewards.
        if(demoCoupons)db.run("INSERT INTO boost_coupon(member_id,title,discount_cents,minimum_cents,expires_at) VALUES (?,?,?,?,?)",id,"新人专享券",1000,10000,Timestamp.from(Instant.now().plusSeconds(30*86400)));
        db.notify(id,"欢迎来到困困",demoCoupons?"测试新人优惠券已放入账户，满100元可减10元。":"完善资料后开始使用；新人优惠以已验证身份和当前活动额度为准。",null);
        return session(id);
    }
    @Transactional
    public Map<String,Object> login(Map<String,Object> b,String ip) {
        String name=text(b,"username",3,32),password=text(b,"password",1,64);
        BoostException.require(name.matches("[A-Za-z0-9_]{3,32}"),"账号仅支持字母、数字、下划线");
        String key=ip+":"+name.toLowerCase(Locale.ROOT);
        if(rateLimits!=null)rateLimits.hit("login:"+key,5,900);
        long now=System.currentTimeMillis();
        if(attempts.size()>10000) attempts.entrySet().removeIf(e->now-e.getValue()[1]>900000);
        long[] attempt=attempts.compute(key,(k,v)->v==null||now-v[1]>900000?new long[]{0,now}:v);
        synchronized(attempt) {
            if(attempt[0]>=5) throw new BoostException(429,"尝试次数过多，请 15 分钟后重试");
            // Password verification and session creation share the lock used by password changes.
            List<Map<String,Object>> rows=db.list("SELECT * FROM boost_member WHERE username=? FOR UPDATE",name);
            if(rows.isEmpty()||!encoder.matches(password,str(rows.get(0),"passwordHash"))){attempt[0]++;throw new BoostException(400,"账号或密码错误");}
            Map<String,Object> m=rows.get(0);
            BoostException.require("ACTIVE".equals(str(m,"status")),"账号已停用，请联系客服");
            if(live)BoostException.require(policyVersion.equals(str(b,"acceptedPolicy")),"请阅读并同意当前版本协议");
            if(policyVersion.equals(str(b,"acceptedPolicy")))db.run("UPDATE boost_member SET accepted_policy=?,accepted_at=CURRENT_TIMESTAMP WHERE id=?",policyVersion,num(m,"id"));
            attempts.remove(key);if(rateLimits!=null)rateLimits.clear("login:"+key);return session(num(m,"id"));
        }
    }
    @Transactional
    public Map<String,Object> session(long id) {
        BoostException.require("ACTIVE".equals(str(db.one("SELECT status FROM boost_member WHERE id=? FOR UPDATE",id),"status")),"账号已停用，请联系客服");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        StringBuilder token=new StringBuilder();for(byte b:bytes)token.append(String.format("%02x",b));
        db.run("DELETE FROM boost_session WHERE expires_at<CURRENT_TIMESTAMP");
        db.run("INSERT INTO boost_session(token_hash,member_id,expires_at) VALUES (?,?,?)",hash(token.toString()),id,Timestamp.from(Instant.now().plusSeconds(7*86400)));
        return map("token",token.toString(),"user",me(id));
    }
    public void logout(HttpServletRequest r){String token=r.getHeader("X-Boost-Token");if(token!=null)db.run("DELETE FROM boost_session WHERE token_hash=?",hash(token));}
    @Transactional public void changePassword(long id,Map<String,Object> b){
        db.activeMember(id);
        String old=text(b,"oldPassword",1,64),next=text(b,"newPassword",8,64);
        Map<String,Object> member=db.one("SELECT password_hash FROM boost_member WHERE id=? FOR UPDATE",id);
        BoostException.require(encoder.matches(old,str(member,"passwordHash")),"当前密码不正确");
        BoostException.require(!old.equals(next),"新密码不能与当前密码相同");
        BoostException.require(next.getBytes(StandardCharsets.UTF_8).length<=72,"密码的 UTF-8 长度不能超过 72 字节");
        db.run("UPDATE boost_member SET password_hash=? WHERE id=?",encode(next),id);
        db.run("DELETE FROM boost_session WHERE member_id=?",id);
    }
    public Map<String,Object> me(long id) {
        Map<String,Object> m=db.one("SELECT id,username,nickname,avatar,certification,skill,review_note,balance_cents,notify_enabled,created_at FROM boost_member WHERE id=?",id);
        String playerFilter=player!=null&&player.enabled()?BoostMarket.PLAYER_VISIBLE_FILTER:"";
        m.put("favorites",db.count("SELECT COUNT(*) FROM boost_favorite f JOIN boost_listing l ON l.id=f.listing_id JOIN boost_member m ON m.id=l.owner_id WHERE f.member_id=? AND "+BoostMarket.VISIBLE_TO_MEMBER+playerFilter,id,id));
        m.put("history",db.count("SELECT COUNT(*) FROM boost_history f JOIN boost_listing l ON l.id=f.listing_id JOIN boost_member m ON m.id=l.owner_id WHERE f.member_id=? AND "+BoostMarket.VISIBLE_TO_MEMBER+playerFilter,id,id));
        m.put("follows",db.count("SELECT COUNT(*) FROM boost_follow f JOIN boost_member s ON s.id=f.seller_id WHERE f.member_id=? AND s.status='ACTIVE'",id));
        m.put("coupons",db.count("SELECT COUNT(*) FROM boost_coupon WHERE member_id=? AND used_order_id IS NULL AND expires_at>CURRENT_TIMESTAMP",id));
        m.put("unread",db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",id)+db.count("SELECT COUNT(*) FROM boost_message m JOIN boost_conversation c ON c.id=m.conversation_id WHERE (c.member_a=? OR c.member_b=?) AND m.sender_id<>? AND m.is_read=FALSE",id,id,id));
        m.put("userNo",BoostPlayerMarket.userNo(id));if(player!=null&&player.enabled())m.put("playerProfile",player.profile(id));
        return m;
    }
    @Transactional public void profile(long id,Map<String,Object> b) {
        db.activeMember(id);
        String nick=text(b,"nickname",2,24);
        String avatar=text(b,"avatar",0,500);
        BoostException.require(avatar.matches("hero|soldier|anime|crown|/boost-media/[a-f0-9]{32}\\.(jpg|png|webp)"),"头像格式不正确");
        if(avatar.startsWith("/boost-media/"))db.uploadedImage(avatar,id,"AVATAR");
        db.run("UPDATE boost_member SET nickname=?,avatar=?,notify_enabled=? WHERE id=?",nick,avatar,Boolean.TRUE.equals(b.get("notifyEnabled")),id);
        if(player!=null&&player.enabled()&&b.containsKey("bio"))player.updateBio(id,b);
    }
    @Transactional
    public void apply(long id,Map<String,Object> b) {
        if(player!=null&&player.enabled())throw new BoostException(410,"旧打手认证已关闭，请使用店铺认证");
        db.activeMember(id);
        Map<String,Object> m=db.one("SELECT certification FROM boost_member WHERE id=? FOR UPDATE",id);
        BoostException.require(!Arrays.asList("APPROVED","PENDING").contains(str(m,"certification")),"认证已通过或正在审核");
        String skill=text(b,"skill",4,100), proof=db.uploadedImages(b,id,"CERTIFICATION");
        BoostException.require(!"[]".equals(proof),"请上传段位或能力证明");
        db.run("UPDATE boost_member SET certification='PENDING',skill=?,proof=?,review_note='' WHERE id=?",skill,proof,id);
    }
    public void worker(long id) {
        BoostException.require(db.count("SELECT COUNT(*) FROM boost_member WHERE id=? AND certification='APPROVED' AND status='ACTIVE'",id)==1,"请先完成打手认证");
    }
    @Transactional
    public Map<String,Object> wechat(String openid,Long member) {
        BoostException.require(openid!=null && openid.length()>=10,"微信登录凭证无效");
        List<Map<String,Object>> existing=db.list("SELECT id,status FROM boost_member WHERE openid=?",openid);
        if(member!=null){
            BoostException.require(existing.isEmpty()||num(existing.get(0),"id")==member,"该微信已绑定其他账号");
            Map<String,Object> current=db.one("SELECT openid,status FROM boost_member WHERE id=? FOR UPDATE",member);
            BoostException.require("ACTIVE".equals(str(current,"status")),"账号已停用");
            BoostException.require(str(current,"openid").isEmpty()||openid.equals(str(current,"openid")),"当前账号已绑定其他微信，不能更换收付款身份");
            db.run("UPDATE boost_member SET openid=? WHERE id=?",openid,member);if(campaign!=null)campaign.claim(member,openid);return me(member);
        }
        if(!existing.isEmpty()){
            long id=num(existing.get(0),"id");
            // Binding already locks member before the campaign mutex. Existing login must
            // use the same order, and validate the current state rather than an earlier snapshot.
            Map<String,Object> current=db.one("SELECT status FROM boost_member WHERE id=? FOR UPDATE",id);
            BoostException.require("ACTIVE".equals(str(current,"status")),"账号已停用");
            if(campaign!=null)campaign.claim(id,openid);return session(id);
        }
        long id=db.insert("INSERT INTO boost_member(username,password_hash,nickname,openid) VALUES (?,?,?,?)","wx_"+hash(openid).substring(0,24),encode(UUID.randomUUID().toString()),"微信用户",openid);
        BoostPlayerMarket.userNo(id); // Capacity failure rolls back registration before welcome messages or rewards.
        db.notify(id,"欢迎来到困困","完善昵称，开始你的上分之旅。",null);if(campaign!=null)campaign.claim(id,openid);return session(id);
    }
    @Transactional public Map<String,Object> wechatWithPolicy(String openid,Map<String,Object> body){
        if(live)BoostException.require(policyVersion.equals(str(body,"acceptedPolicy")),"请阅读并同意当前版本协议");
        Map<String,Object> result=wechat(openid,null);
        if(policyVersion.equals(str(body,"acceptedPolicy")))db.run("UPDATE boost_member SET accepted_policy=?,accepted_at=CURRENT_TIMESTAMP WHERE id=?",policyVersion,num((Map<String,Object>)result.get("user"),"id"));
        return result;
    }
}
