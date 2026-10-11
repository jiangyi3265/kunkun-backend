package com.ruoyi.boost;

import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

/** Player shops and publication eligibility are separate from historical skill certification. */
@Service
public class BoostPlayerMarket {
    public static final String TYPE="PLAYER_ITEM",GAME="和平精英";
    public static final String[] CATEGORIES={"密钥","传世武器","头盔","护甲","背包","枪械","活动道具","日常道具"};
    private final BoostDb db;
    @Value("${boost.player-trading-enabled:true}") private boolean enabled=true;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostIdentity identity;
    @Value("${boost.growth-level-thresholds:}") private String thresholds="";
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostBenefits benefits;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPhone phones;
    private String growthThresholds(){String configured=benefits==null?"":benefits.thresholds();return configured.isBlank()?thresholds:configured;}
    public BoostPlayerMarket(BoostDb db){this.db=db;}
    public boolean enabled(){return enabled;}
    public static void rejectAccountSale(String text){BoostException.require(!java.util.regex.Pattern.compile("([账帐]号.{0,12}(出售|售卖|买卖|出租|转让)|(出售|售卖|买卖|出租|转让|卖).{0,12}[账帐]号|账号密码|帐号密码|改绑)").matcher(text).find(),"平台只交易游戏道具，禁止游戏账号交易");}
    public static boolean item(Map<String,Object> record){return TYPE.equals(str(record,"businessType"));}
    public Map<String,Object> config(){return map("businessMode",TYPE,"games",Collections.singletonList(GAME),"itemCategories",Arrays.asList(CATEGORIES),"accountSalesAllowed",false,"shopCertificationRequired",true,"requestShopCertificationRequired",false,"realnameRequired",true,"realnameReady",identity!=null&&identity.ready(),"smsReady",phones!=null&&phones.ready(),"growthRulesReady",!growthThresholds().isBlank(),"membershipPurchaseEnabled",false);}
    // Public numbers are derived from the immutable primary key; never truncate or recycle.
    private static final long USER_NO_OFFSET=10000, MAX_NUMBERED_MEMBER_ID=9999;
    public static String userNo(long id){
        BoostException.require(id>0,"用户编号格式不正确");
        if(id>MAX_NUMBERED_MEMBER_ID)throw new BoostException(503,"1开头的五位用户编号已用完，请联系平台扩容");
        return Long.toString(USER_NO_OFFSET+id);
    }
    public static boolean isUserNoInput(String value){return value!=null&&value.trim().matches("[0-9]{5}|(?i:KK)[0-9]{6,19}");}
    public static long memberIdFromUserNo(String input){
        String value=input==null?"":input.trim().toUpperCase(Locale.ROOT);
        if(value.matches("1[0-9]{4}")){
            long id=Long.parseLong(value)-USER_NO_OFFSET;
            BoostException.require(id>0,"请输入五位用户编号，例如 10001");
            return id;
        }
        // Accept canonical legacy links during rollout; all responses use the new number.
        try{
            BoostException.require(value.matches("KK[0-9]{6,19}"),"请输入五位用户编号，例如 10001");
            long id=Long.parseLong(value.substring(2));
            BoostException.require(id>0&&id<=MAX_NUMBERED_MEMBER_ID&&value.equals("KK"+String.format(Locale.ROOT,"%06d",id)),"用户编号格式不正确");
            return id;
        }catch(NumberFormatException e){throw new BoostException(400,"用户编号格式不正确");}
    }
    public Map<String,Object> profile(long id){
        String thresholds=growthThresholds();
        Map<String,Object> member=db.one("SELECT id,nickname FROM boost_member WHERE id=?",id);
        List<Map<String,Object>> rows=db.list("SELECT * FROM boost_player_profile WHERE member_id=?",id);
        Map<String,Object> result=rows.isEmpty()?map("memberId",id,"shopName",str(member,"nickname"),"shopIntro","","gameUid","","shopStatus","NONE","shopReviewNote","","realnameStatus","NONE","realnameProvider","","membershipType","FREE","membershipExpiresAt",null,"growthPoints",0L):new LinkedHashMap<>(rows.get(0));
        result.remove("identitySubjectHash");result.remove("shopProof");result.remove("gameUid");
        result.put("shopSubmitted",!"NONE".equals(str(result,"shopStatus"))||result.get("shopSubmittedAt")!=null);
        result.put("userNo",userNo(id));if(phones!=null)result.putAll(phones.info(id));
        if(str(result,"shopName").isBlank())result.put("shopName",str(member,"nickname"));
        boolean memberActive="MEMBER".equals(str(result,"membershipType"))&&!str(result,"membershipExpiresAt").isBlank()&&LocalDateTime.parse(str(result,"membershipExpiresAt")).isAfter(LocalDateTime.now());
        result.put("membershipActive",memberActive);result.put("level",1);result.put("nextLevelPoints",null);result.put("growthProgress",null);
        if(!thresholds.isBlank()){
            String[] values=thresholds.split(",");long prior=-1,points=num(result,"growthPoints");int level=0;
            for(String v:values){long threshold;try{threshold=Long.parseLong(v.trim());}catch(Exception e){throw new IllegalStateException("Growth thresholds must be integers");}
                if(threshold<=prior||(level==0&&threshold!=0))throw new IllegalStateException("Growth thresholds must start at zero and increase");
                if(points>=threshold){level++;result.put("level",level);}else if(result.get("nextLevelPoints")==null){result.put("nextLevelPoints",threshold);result.put("growthProgress",Math.min(100,100*(points-prior)/(threshold-prior)));}
                prior=threshold;
            }
        }
        if(!thresholds.isBlank()&&result.get("nextLevelPoints")==null)result.put("growthProgress",100);
        result.putIfAbsent("bio","");result.putIfAbsent("ipProvince","未知");
        result.put("growthRulesReady",!thresholds.isBlank());return result;
    }
    @Transactional public void updateBio(long id,Map<String,Object> body){
        db.activeMember(id);ensureProfile(id);String bio=text(body,"bio",0,160);
        db.run("UPDATE boost_player_profile SET bio=? WHERE member_id=?",bio,id);
    }
    @Transactional public void observeProvince(long id,String province){
        // A private/internal/unknown request must not replace the last known public province.
        if(province==null||"未知".equals(province))return;
        db.activeMember(id);ensureProfile(id);
        db.run("UPDATE boost_player_profile SET ip_province=?,ip_observed_at=CURRENT_TIMESTAMP WHERE member_id=?",province,id);
    }
    private void ensureProfile(long id){if(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=?",id)==0)db.run("INSERT INTO boost_player_profile(member_id) VALUES (?)",id);}
    @Transactional public void applyShop(long id,Map<String,Object> body){
        db.activeMember(id);ensureProfile(id);
        Map<String,Object> previous=db.one("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",id);
        BoostException.require("NONE".equals(str(previous,"shopStatus"))&&previous.get("shopSubmittedAt")==null,"店铺材料只能提交一次，如需更改请联系平台后台");
        requirePhone(id);
        BoostException.require(body.get("images") instanceof List&&((List<?>)body.get("images")).size()<=3,"店铺材料最多 3 张");
        String name=text(body,"shopName",2,40),intro=text(body,"shopIntro",5,500),proof=db.uploadedImages(body,id,"SHOP_CERTIFICATION");
        BoostException.require(!"[]".equals(proof),"请上传店铺与游戏资料证明，勿上传身份证或账号密码");
        db.run("UPDATE boost_player_profile SET shop_name=?,shop_intro=?,shop_proof=?,shop_status='PENDING',shop_review_note='',shop_submitted_at=CURRENT_TIMESTAMP,shop_revision=shop_revision+1 WHERE member_id=?",name,intro,proof,id);
        snapshot(id,null,"用户首次提交店铺材料");
    }
    private void requirePhone(long id){if(db.list("SELECT member_id FROM boost_member_phone WHERE member_id=? FOR UPDATE",id).isEmpty())throw new BoostException(403,"店铺手机号尚未验证，请验证手机号后再提交");}
    /** Caller holds member lock before profile/listing/order locks. Buying requests need only identity proof. */
    public void requirePublisher(long id){requirePublisher(id,"SERVICE");}
    public void requirePublisher(long id,String kind){
        db.activeMember(id);
        List<Map<String,Object>> profiles=db.list("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",id);
        if(profiles.isEmpty())throw new BoostException(403,"请先完成真实实名认证");
        Map<String,Object> profile=profiles.get(0);
        if(!"VERIFIED".equals(str(profile,"realnameStatus"))||!"TENCENT_FACEID".equals(str(profile,"realnameProvider"))||str(profile,"identitySubjectHash").length()!=64||
           db.list("SELECT id FROM boost_identity_session WHERE member_id=? AND provider='TENCENT_FACEID' AND status='VERIFIED' AND verified_at IS NOT NULL FOR UPDATE",id).isEmpty())
            throw new BoostException(403,"请先完成真实实名认证");
        if(!"REQUEST".equals(kind)){if(!"APPROVED".equals(str(profile,"shopStatus")))throw new BoostException(403,"出售道具需先通过店铺认证");requirePhone(id);}
    }
    // The caller already holds participant member locks. A locking read sees commits
    // made while cancellation waited for those locks, even under REPEATABLE READ.
    public boolean eligible(long id){return !db.list("SELECT p.member_id FROM boost_player_profile p JOIN boost_member m ON m.id=p.member_id JOIN boost_member_phone ph ON ph.member_id=p.member_id JOIN boost_identity_session s ON s.member_id=p.member_id WHERE p.member_id=? AND m.status='ACTIVE' AND p.shop_status='APPROVED' AND p.realname_status='VERIFIED' AND p.realname_provider='TENCENT_FACEID' AND LENGTH(p.identity_subject_hash)=64 AND s.provider='TENCENT_FACEID' AND s.status='VERIFIED' AND s.verified_at IS NOT NULL LIMIT 1 FOR UPDATE",id).isEmpty();}
    @Transactional public void moderateShop(long operator,long member,Map<String,Object> body){
        db.activeMember(member);String action=choice(body,"action","approve","reject"),note=text(body,"note",2,500);
        Map<String,Object> p=db.one("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",member);
        if(number(body,"expectedRevision",0,Long.MAX_VALUE)!=num(p,"shopRevision"))throw new BoostException(409,"审核材料已更新，请刷新后重新核查");
        BoostException.require("PENDING".equals(str(p,"shopStatus")),"此店铺认证申请已处理");
        if("approve".equals(action))requirePhone(member);
        preserveLegacySnapshot(member,p);
        db.run("UPDATE boost_player_profile SET shop_status=?,shop_review_note=?,shop_revision=shop_revision+1 WHERE member_id=?","approve".equals(action)?"APPROVED":"REJECTED",note,member);
        snapshot(member,operator,note);
        db.notify(member,"店铺认证审核结果",note,null);db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'shop:review',?,?)",operator,member,note);
    }
    public Map<String,Object> growth(long member,Map<String,String> query){return BoostPages.query(db,query,"SELECT points,reason,rule_version,created_at","FROM boost_growth_event WHERE member_id=?","id DESC",member);}
    public Map<String,Object> growthRules(){return map("thresholds",growthThresholds(),"mode","MANUAL","membershipPurchaseEnabled",false);}
    private void snapshot(long member,Long admin,String note){
        Map<String,Object> p=db.one("SELECT * FROM boost_player_profile WHERE member_id=?",member);
        List<Map<String,Object>> phone=db.list("SELECT phone,verified_at FROM boost_member_phone WHERE member_id=?",member);
        db.run("INSERT INTO boost_shop_revision(member_id,revision,shop_name,shop_intro,shop_proof,phone,phone_verified_at,shop_status,admin_id,note) VALUES (?,?,?,?,?,?,?,?,?,?)",member,num(p,"shopRevision"),str(p,"shopName"),str(p,"shopIntro"),str(p,"shopProof"),phone.isEmpty()?null:str(phone.get(0),"phone"),phone.isEmpty()?null:phone.get(0).get("verifiedAt"),str(p,"shopStatus"),admin,note);
    }
    private void preserveLegacySnapshot(long member,Map<String,Object> p){if(num(p,"shopRevision")==0&&db.count("SELECT COUNT(*) FROM boost_shop_revision WHERE member_id=? AND revision=0",member)==0)snapshot(member,null,"旧版店铺资料留档");}
    @Transactional public Map<String,Object> editShop(long admin,long member,Map<String,Object> body){
        db.activeMember(member);Map<String,Object> p=db.one("SELECT * FROM boost_player_profile WHERE member_id=? FOR UPDATE",member);
        BoostException.require(!"NONE".equals(str(p,"shopStatus"))||p.get("shopSubmittedAt")!=null,"用户尚未提交店铺材料");
        if(number(body,"expectedRevision",0,Long.MAX_VALUE)!=num(p,"shopRevision"))throw new BoostException(409,"资料已被其他管理员更新，请刷新后重试");
        BoostException.require(body.get("images") instanceof List&&((List<?>)body.get("images")).size()<=3,"店铺材料最多 3 张");
        String name=text(body,"shopName",2,40),intro=text(body,"shopIntro",5,500),note=text(body,"note",2,500),proof=db.uploadedImages(body,member,"SHOP_CERTIFICATION");
        BoostException.require(!"[]".equals(proof),"店铺材料不能为空");preserveLegacySnapshot(member,p);
        String ticket=text(body,"phoneTicket",0,36);if(!ticket.isBlank()){BoostException.require(phones!=null,"手机号服务不可用");phones.consumeAdmin(member,admin,ticket);}
        requirePhone(member);
        db.run("UPDATE boost_player_profile SET shop_name=?,shop_intro=?,shop_proof=?,shop_status='PENDING',shop_review_note=?,shop_revision=shop_revision+1 WHERE member_id=?",name,intro,proof,note,member);
        db.run("UPDATE boost_listing SET status='OFFLINE',review_note='店铺资料变更，重新审核后可提交上架' WHERE owner_id=? AND kind='SERVICE' AND business_type='PLAYER_ITEM' AND status='ACTIVE'",member);
        snapshot(member,admin,note);db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'shop:edit',?,?)",admin,member,note);
        db.notify(member,"店铺资料已由后台更新","资料将重新审核。修改原因："+note,null);return profile(member);
    }
    public Map<String,Object> shopHistory(long member,Map<String,String> q){return BoostPages.query(db,q,"SELECT *","FROM boost_shop_revision WHERE member_id=?","revision DESC",member);}
}
