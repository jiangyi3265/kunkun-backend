package com.ruoyi.boost;

import java.util.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service public class BoostContent {
 private final BoostDb db;private final BoostRewardsMedia media;private final ObjectMapper json=new ObjectMapper();
 public BoostContent(BoostDb d,BoostRewardsMedia m){db=d;media=m;}
 private void operator(long admin){db.one("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",admin);}
 private void audit(long admin,String action,long id){db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,?,?,?)",admin,"content:"+action,id,"保存运营内容");}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BoostException(400,"内容格式无效");}}
 private Map<String,Object> decode(String value){try{return json.readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException("Saved content is invalid",e);}}
 public Map<String,Object> site(){
  List<Map<String,Object>> rows=db.list("SELECT * FROM boost_site_content WHERE id=1");if(!rows.isEmpty()){Map<String,Object> result=decode(str(rows.get(0),"payload"));result.put("revision",num(rows.get(0),"revision"));return result;}
  return map("revision",0,"brand","困困","tagline","地铁逃生，玩家道具集市","searchPlaceholder","搜装备、密钥与游戏道具","recommendedTitle","人气推荐","discoverTitle","发现好道具","discoverSubtitle","更多选择，刚好适合你","profileBannerTitle","每一件装备，找到新主人","profileBannerSubtitle","和平精英 · 玩家自主交易","profileBannerImage","","banners",List.of(map("image","/static/player-equipment-banner.jpg","target","market/index","label","逛道具市场")));
 }
 public static String target(Map<String,Object> item){String target=text(item,"target",1,100);BoostException.require(target.matches("(market/index|hall/index|content/activities|rewards/mall|rewards/activity(?:\\?id=[1-9][0-9]*)?|content/activity\\?id=[1-9][0-9]*)"),"请选择支持的站内页面路径");return target;}
 private String publicImage(String url){return "/static/player-equipment-banner.jpg".equals(url)?url:media.validate(url);}
 @Transactional public Object saveSite(long admin,Map<String,Object> body){
  operator(admin);db.one("SELECT config_key FROM boost_config WHERE config_key=? FOR UPDATE","schema:"+BoostMigrations.VERSION);long expected=number(body,"revision",0,Long.MAX_VALUE);Map<String,Object> result=new LinkedHashMap<>();
  for(String key:List.of("brand","tagline","searchPlaceholder","recommendedTitle","discoverTitle","discoverSubtitle","profileBannerTitle","profileBannerSubtitle"))result.put(key,text(body,key,1,"brand".equals(key)?12:60));
  result.put("profileBannerImage",publicImage(text(body,"profileBannerImage",0,200)));
  BoostException.require(body.get("banners") instanceof List,"请配置首页大图");List<?> input=(List<?>)body.get("banners");BoostException.require(!input.isEmpty()&&input.size()<=8,"首页需要 1 至 8 张大图");List<Object> banners=new ArrayList<>();
  for(Object entry:input){BoostException.require(entry instanceof Map,"大图格式无效");Map<String,Object>b=(Map<String,Object>)entry;String image=publicImage(text(b,"image",1,200));banners.add(map("image",image,"target",target(b),"label",text(b,"label",1,60)));}result.put("banners",banners);
  List<Map<String,Object>> rows=db.list("SELECT revision FROM boost_site_content WHERE id=1 FOR UPDATE");long current=rows.isEmpty()?0:num(rows.get(0),"revision");if(expected!=current)throw new BoostException(409,"配置已被其他管理员更新，请刷新后再修改");
  if(rows.isEmpty())db.run("INSERT INTO boost_site_content(id,revision,payload) VALUES (1,1,?)",encode(result));else db.run("UPDATE boost_site_content SET revision=revision+1,payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",encode(result));audit(admin,"site",1);return site();
 }
 private String images(Map<String,Object> b){Object raw=b.get("detailImages");if(raw==null)return "[]";BoostException.require(raw instanceof List&&((List<?>)raw).size()<=9,"最多上传 9 张详情图片");List<String> images=new ArrayList<>();for(Object v:(List<?>)raw){BoostException.require(v instanceof String&&!((String)v).isBlank(),"图片格式无效");images.add(media.validate((String)v));}return encode(images);}
 private Timestamp time(Map<String,Object> b,String field){try{return Timestamp.valueOf(LocalDateTime.parse(text(b,field,16,30)));}catch(Exception e){throw new BoostException(400,"请填写有效的活动起止时间");}}
 private void phase(Map<String,Object> row){try{String encoded=str(row,"detailImages");row.put("detailImages",encoded.isBlank()?List.of():json.readValue(encoded,List.class));}catch(Exception e){throw new IllegalStateException("Saved activity images invalid",e);}LocalDateTime now=LocalDateTime.now();row.put("phase",LocalDateTime.parse(str(row,"endsAt")).isBefore(now)?"ENDED":LocalDateTime.parse(str(row,"startsAt")).isAfter(now)?"UPCOMING":"ONGOING");}
 public Object activities(Map<String,String> query,boolean admin){
  String where=admin?"WHERE 1=1":"WHERE status='PUBLISHED'";if(!admin){String scope=query.getOrDefault("scope","current");BoostException.require(List.of("current","history").contains(scope),"活动列表类型无效");where+=" AND ends_at"+("history".equals(scope)?"<":" >=")+"CURRENT_TIMESTAMP";}
  Map<String,Object> result=BoostPages.query(db,query,"SELECT *","FROM boost_content_activity "+where,"published_at DESC,id DESC");for(Map<String,Object> row:(List<Map<String,Object>>)result.get("items")){phase(row);row.remove("requestHash");row.remove("createKey");}return result;
 }
 public Map<String,Object> activity(long id,boolean admin){Map<String,Object> row=db.one("SELECT * FROM boost_content_activity WHERE id=?"+(admin?"":" AND status='PUBLISHED'"),id);row.remove("requestHash");row.remove("createKey");phase(row);Long reward=row.get("rewardActivityId")==null?null:num(row,"rewardActivityId");row.put("rewardAvailable",reward!=null&&db.count("SELECT COUNT(*) FROM boost_reward_activity WHERE id=? AND status='ACTIVE' AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP",reward)>0&&"ONGOING".equals(row.get("phase")));return row;}
 @Transactional public Object saveActivity(long admin,Long id,Map<String,Object> b){
  operator(admin);String requestKey=null,hash=null;
  if(id==null){requestKey=text(b,"requestKey",8,64);hash=BoostIdempotency.digest(b);List<Map<String,Object>> prior=db.list("SELECT * FROM boost_content_activity WHERE create_key=?",requestKey);if(!prior.isEmpty()){BoostIdempotency.same(prior.get(0),hash);return activity(num(prior.get(0),"id"),true);}}
  String title=text(b,"title",1,60),summary=text(b,"summary",0,200),cover=media.validate(text(b,"cover",0,200)),body=text(b,"body",0,5000),images=images(b),status=choice(b,"status","DRAFT","PUBLISHED","OFFLINE");Timestamp start=time(b,"startsAt"),end=time(b,"endsAt");BoostException.require(end.after(start),"结束时间必须晚于开始时间");BoostException.require(!"PUBLISHED".equals(status)||(!cover.isEmpty()&&(!body.isEmpty()||!images.equals("[]"))),"发布活动需要封面和正文或详情图");Long reward=b.get("rewardActivityId")==null?null:number(b,"rewardActivityId",1,Long.MAX_VALUE);if(reward!=null)db.one("SELECT id FROM boost_reward_activity WHERE id=?",reward);
  if(id==null)id=db.insert("INSERT INTO boost_content_activity(title,summary,cover,body,detail_images,starts_at,ends_at,status,reward_activity_id,create_key,request_hash,published_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",title,summary,cover,body,images,start,end,status,reward,requestKey,hash,"PUBLISHED".equals(status)?Timestamp.valueOf(LocalDateTime.now()):null);
  else{Map<String,Object> old=db.one("SELECT * FROM boost_content_activity WHERE id=? FOR UPDATE",id);if(number(b,"revision",0,Long.MAX_VALUE)!=num(old,"revision"))throw new BoostException(409,"活动已被其他管理员更新，请重新打开");db.run("UPDATE boost_content_activity SET title=?,summary=?,cover=?,body=?,detail_images=?,starts_at=?,ends_at=?,status=?,reward_activity_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,published_at=COALESCE(published_at,?) WHERE id=?",title,summary,cover,body,images,start,end,status,reward,"PUBLISHED".equals(status)?Timestamp.valueOf(LocalDateTime.now()):null,id);}audit(admin,"activity",id);return activity(id,true);
 }
}
