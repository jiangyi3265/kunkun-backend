package com.ruoyi.boost;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service
public class BoostMarket {
    private final BoostDb db; private final BoostAuth auth;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    private boolean playerMode(){return player!=null&&player.enabled();}
    private void decorate(Map<String,Object> row){row.put("availableQuantity",BoostItemStock.unit(row)?BoostItemStock.available(row):num(row,"quantity"));if(player!=null&&BoostPlayerMarket.item(row)){Map<String,Object> p=player.profile(num(row,"ownerId"));row.put("shopName",p.get("shopName"));row.put("shopStatus",p.get("shopStatus"));row.put("realnameStatus",p.get("realnameStatus"));}}
    public static final String SELECT="SELECT l.*,m.nickname,m.avatar,m.certification FROM boost_listing l JOIN boost_member m ON m.id=l.owner_id ";
    public static final String VISIBLE_TO_MEMBER="m.status='ACTIVE' AND (l.status='ACTIVE' OR l.owner_id=?)";
    public static final String PLAYER_VISIBLE_FILTER=" AND l.business_type='PLAYER_ITEM' AND l.game='和平精英'";
    public BoostMarket(BoostDb db,BoostAuth auth){this.db=db;this.auth=auth;}
    public static String quoteKey(Map<String,Object> listing){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:Arrays.asList("id","ownerId","kind","title","game","zone","platform","currentRank","targetRank","durationHours","priceCents","description","images","businessType","itemCategory","quantity","itemDetails","deliveryTerms","pricingMode","detailImages")){
                String value=str(listing,field);
                digest.update((value.length()+":"+value).getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format("%02x",b));return result.toString();
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> listings(Map<String,String> q,Long owner) {
        StringBuilder where=new StringBuilder(" WHERE m.status='ACTIVE' "); List<Object> args=new ArrayList<>();
        if(playerMode()){
            String game=q.getOrDefault("game",BoostPlayerMarket.GAME),category=q.getOrDefault("itemCategory","");
            BoostException.require(Arrays.asList(BoostPlayerMarket.GAME,"全部","ALL","").contains(game),"仅支持和平精英道具交易");
            where.append(" AND l.business_type=? AND l.game=?");args.add(BoostPlayerMarket.TYPE);args.add(BoostPlayerMarket.GAME);
            if(!category.isEmpty()&&!"全部".equals(category)){BoostException.require(Arrays.asList(BoostPlayerMarket.CATEGORIES).contains(category),"道具分类无效");where.append(" AND l.item_category=?");args.add(category);}
        }
        if(owner!=null){where.append(" AND l.owner_id=?");args.add(owner);}else where.append(" AND l.status='ACTIVE'");
        for(String key:Arrays.asList("kind","game","status")) {
            String val=q.get(key); if(val!=null&&!val.isEmpty()&&!"全部".equals(val)&&!"ALL".equals(val)) {
                if("status".equals(key)&&owner==null)continue;
                where.append(" AND l.").append(key).append("=?");args.add(val);
            }
        }
        String keyword=q.get("keyword");if(keyword!=null&&!keyword.trim().isEmpty()){where.append(" AND (l.title LIKE ? OR l.description LIKE ?)");args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
        if(q.containsKey("seller")){try{where.append(" AND l.owner_id=?");args.add(Long.parseLong(q.get("seller")));}catch(Exception e){throw new BoostException(400,"店铺编号无效");}}
        String sort=q.getOrDefault("sort","newest"),order="l.id DESC";
        if("priceAsc".equals(sort))order="l.price_cents ASC,l.id DESC";
        if("priceDesc".equals(sort))order="l.price_cents DESC,l.id DESC";
        if("sales".equals(sort))order="l.sales DESC,l.id DESC";
        long total=db.count("SELECT COUNT(*) FROM boost_listing l JOIN boost_member m ON m.id=l.owner_id"+where,args.toArray());
        int page=page(q); args.add(20);args.add((page-1)*20);
        List<Map<String,Object>> items=db.list(SELECT+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray());
        for(Map<String,Object> item:items){item.put("quoteKey",quoteKey(item));decorate(item);}
        return map("items",items,"total",total,"page",page);
    }
    @Transactional
    public Map<String,Object> detail(long id,Long member) {
        // Authenticated detail views also persist history; recheck before those writes.
        if(member!=null)db.activeMember(member);
        Map<String,Object> row=db.one(SELECT+" WHERE l.id=? AND m.status='ACTIVE'",id);
        if(playerMode()&&!BoostPlayerMarket.item(row)&&!(member!=null&&member==num(row,"ownerId")))throw new BoostException(404,"历史代练发布已停止展示");
        BoostException.require("ACTIVE".equals(str(row,"status")) || (member!=null&&member==num(row,"ownerId")),"此服务已下架或正在审核");
        db.run("UPDATE boost_listing SET views=views+1 WHERE id=?",id);
        if(member!=null){
            db.run("DELETE FROM boost_history WHERE member_id=? AND listing_id=?",member,id);
            db.run("INSERT INTO boost_history(member_id,listing_id) VALUES (?,?)",member,id);
        }
        row.put("favorited",member!=null&&db.count("SELECT COUNT(*) FROM boost_favorite WHERE member_id=? AND listing_id=?",member,id)>0);
        row.put("followed",member!=null&&db.count("SELECT COUNT(*) FROM boost_follow WHERE member_id=? AND seller_id=?",member,num(row,"ownerId"))>0);
        row.put("reviews",db.list("SELECT r.*,m.nickname,m.avatar FROM boost_review r JOIN boost_member m ON m.id=r.member_id WHERE listing_id=? ORDER BY id DESC LIMIT 10",id));
        row.put("reviewTotal",db.count("SELECT COUNT(*) FROM boost_review WHERE listing_id=?",id));
        row.put("quoteKey",quoteKey(row));
        decorate(row);
        return row;
    }
    public Map<String,Object> reviews(long id,Long member,Map<String,String> q){
        Map<String,Object> listing=db.one(SELECT+" WHERE l.id=? AND m.status='ACTIVE'",id);
        if(playerMode()&&!BoostPlayerMarket.item(listing)&&!(member!=null&&member==num(listing,"ownerId")))throw new BoostException(404,"历史代练发布已停止展示");
        BoostException.require("ACTIVE".equals(str(listing,"status"))||(member!=null&&member==num(listing,"ownerId")),"此服务已下架或正在审核");
        int p=page(q);return map("items",db.list("SELECT r.*,m.nickname,m.avatar FROM boost_review r JOIN boost_member m ON m.id=r.member_id WHERE listing_id=? ORDER BY id DESC LIMIT 10 OFFSET ?",id,(p-1)*10),"total",db.count("SELECT COUNT(*) FROM boost_review WHERE listing_id=?",id),"page",p);
    }
    @Transactional
    public long publish(long member,Map<String,Object> b,Long id) {
        db.activeMember(member);
        if(playerMode())return publishItem(member,b,id);
        String requestKey=null,requestHash=null;
        if(id==null){
            requestKey=text(b,"requestKey",8,64);requestHash=BoostIdempotency.digest(b);
            List<Map<String,Object>> repeated=db.list("SELECT id,request_hash FROM boost_listing WHERE owner_id=? AND request_key=?",member,requestKey);
            if(!repeated.isEmpty()){BoostIdempotency.same(repeated.get(0),requestHash);return num(repeated.get(0),"id");}
        }
        String kind=choice(b,"kind","SERVICE","REQUEST");if("SERVICE".equals(kind))auth.worker(member);
        String title=text(b,"title",5,60),game=choice(b,"game","王者荣耀","和平精英","原神"),zone=choice(b,"zone","微信区","QQ区","官方服","渠道服");
        String platform=choice(b,"platform","安卓","iOS","全平台"),from=text(b,"currentRank",0,30),to=text(b,"targetRank",1,30),desc=text(b,"description",5,1000);
        long duration=number(b,"durationHours",1,720),price=number(b,"priceCents",100,10000000);String imgs=db.uploadedImages(b,member,"LISTING");
        if(id==null)return db.insert("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,request_key,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",member,kind,title,game,zone,platform,from,to,duration,price,desc,imgs,requestKey,requestHash);
        Map<String,Object> old=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);
        BoostException.require(num(old,"ownerId")==member,"只能编辑自己的发布");
        BoostException.require(!Arrays.asList("MATCHED","RESERVED").contains(str(old,"status")),"已匹配的需求不能编辑");
        BoostException.require(kind.equals(str(old,"kind")),"发布类型不能修改");
        db.run("UPDATE boost_listing SET title=?,game=?,zone=?,platform=?,current_rank=?,target_rank=?,duration_hours=?,price_cents=?,description=?,images=?,status='PENDING',review_note='' WHERE id=?",title,game,zone,platform,from,to,duration,price,desc,imgs,id);return id;
    }
    private long publishItem(long member,Map<String,Object> b,Long id){
        BoostException.require(BoostPlayerMarket.TYPE.equals(str(b,"businessType")),"发布版本已更新，请刷新后重新填写道具信息");
        player.requirePublisher(member,choice(b,"kind","SERVICE","REQUEST"));
        String key=null,hash=null;
        if(id==null){key=text(b,"requestKey",8,64);hash=BoostIdempotency.digest(b);List<Map<String,Object>> repeated=db.list("SELECT id,request_hash FROM boost_listing WHERE owner_id=? AND request_key=?",member,key);if(!repeated.isEmpty()){BoostIdempotency.same(repeated.get(0),hash);return num(repeated.get(0),"id");}}
        String kind=choice(b,"kind","SERVICE","REQUEST"),game=choice(b,"game",BoostPlayerMarket.GAME),category=choice(b,"itemCategory",BoostPlayerMarket.CATEGORIES);
        String mode=b.containsKey("pricingMode")?choice(b,"pricingMode","UNIT","LOT"):"LOT";boolean unit="UNIT".equals(mode);
        String title=text(b,"title",5,60),desc=text(b,"description",unit?0:5,1000),details=text(b,"itemDetails",2,500),terms=text(b,"deliveryTerms",unit?0:5,500),zone=choice(b,"zone","微信区","QQ区","全平台"),platform=choice(b,"platform","安卓","iOS","全平台");
        if(unit)BoostException.require(("全平台".equals(zone)&&"全平台".equals(platform))||(!"全平台".equals(zone)&&!"全平台".equals(platform)),"请选择完整的游戏区服");
        String detailImages=db.uploadedImages(map("images",b.get("detailImages")),member,"LISTING");
        BoostPlayerMarket.rejectAccountSale(title+" "+desc+" "+details+" "+terms);
        long quantity=number(b,"quantity",1,9999),price=number(b,"priceCents",100,10000000);String imgs=db.uploadedImages(b,member,"LISTING");
        if("SERVICE".equals(kind))BoostException.require(!"[]".equals(imgs),"出售道具请上传实物游戏截图");
        if(id==null)return db.insert("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,request_key,request_hash,business_type,item_category,quantity,item_details,delivery_terms,pricing_mode,detail_images) VALUES (?,?,?,?,?,?,'','',0,?,?,?,?,?,?,?,?,?,?,?,?)",member,kind,title,game,zone,platform,price,desc,imgs,key,hash,BoostPlayerMarket.TYPE,category,quantity,details,terms,mode,detailImages);
        Map<String,Object> old=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);BoostException.require(num(old,"ownerId")==member,"只能编辑自己的发布");BoostException.require(BoostPlayerMarket.item(old),"历史代练发布不能转换为道具交易");BoostException.require(!Arrays.asList("RESERVED","SOLD","MATCHED").contains(str(old,"status")),"已预订或出售的道具不能编辑");BoostException.require(kind.equals(str(old,"kind")),"发布类型不能修改");
        BoostException.require(!BoostItemStock.unit(old)||unit,"按件商品不能改回旧版整批计价，请更新客户端");
        BoostException.require(num(old,"reservedQuantity")==0,"还有未付款预订，取消或完成付款后再编辑");
        BoostException.require(num(old,"soldQuantity")==0||mode.equals(str(old,"pricingMode")),"已售商品不能更换计价方式");
        BoostException.require(!unit||quantity>num(old,"soldQuantity"),"总数量必须大于已售数量");
        db.run("UPDATE boost_listing SET title=?,zone=?,platform=?,price_cents=?,description=?,images=?,item_category=?,quantity=?,item_details=?,delivery_terms=?,pricing_mode=?,detail_images=?,status='PENDING',review_note='' WHERE id=?",title,zone,platform,price,desc,imgs,category,quantity,details,terms,mode,detailImages,id);return id;
    }
    @Transactional
    public void listingAction(long member,long id,String action) {
        db.activeMember(member);
        Map<String,Object> old=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);
        BoostException.require(num(old,"ownerId")==member,"只能管理自己的发布");
        String status=str(old,"status");
        if("offline".equals(action)){BoostException.require(Arrays.asList("ACTIVE","PENDING").contains(status),"当前状态不能下架");db.run("UPDATE boost_listing SET status='OFFLINE' WHERE id=?",id);}
        else if("resubmit".equals(action)){BoostException.require(Arrays.asList("OFFLINE","REJECTED").contains(status),"当前状态不能重新提交");if(playerMode()){BoostException.require(BoostPlayerMarket.item(old),"历史发布不能重新上架");player.requirePublisher(member,str(old,"kind"));}else if("SERVICE".equals(str(old,"kind")))auth.worker(member);db.run("UPDATE boost_listing SET status='PENDING',review_note='' WHERE id=?",id);}
        else throw new BoostException(400,"无效操作");
    }
    @Transactional
    public boolean favorite(long member,long id,boolean desired) {
        db.activeMember(member);
        db.one("SELECT l.id FROM boost_listing l JOIN boost_member m ON m.id=l.owner_id WHERE l.id=? AND "+VISIBLE_TO_MEMBER+(playerMode()&&desired?PLAYER_VISIBLE_FILTER:""),id,member);
        // Lock before any consistent read so repeated desired-state requests see the previous commit.
        if(!desired){db.run("DELETE FROM boost_favorite WHERE member_id=? AND listing_id=?",member,id);return false;}
        if(db.count("SELECT COUNT(*) FROM boost_favorite WHERE member_id=? AND listing_id=?",member,id)>0)return true;
        db.run("INSERT INTO boost_favorite(member_id,listing_id) VALUES (?,?)",member,id);return true;
    }
    public Map<String,Object> saved(long member,String type) {return saved(member,type,Collections.emptyMap());}
    public Map<String,Object> saved(long member,String type,Map<String,String> q) {
        BoostException.require(Arrays.asList("favorites","history").contains(type),"列表类型无效");
        String table="history".equals(type)?"boost_history":"boost_favorite";
        Map<String,Object> result=BoostPages.query(db,q,SELECT.substring(0,SELECT.indexOf(" FROM ")),SELECT.substring(SELECT.indexOf(" FROM ")+1)+" JOIN "+table+" f ON f.listing_id=l.id WHERE f.member_id=? AND "+VISIBLE_TO_MEMBER+(playerMode()?PLAYER_VISIBLE_FILTER:""),"f.created_at DESC,l.id DESC",member,member);for(Map<String,Object> item:(List<Map<String,Object>>)result.get("items"))decorate(item);return result;
    }
    @Transactional
    public boolean follow(long member,long seller,boolean desired) {
        BoostException.require(member!=seller,"不能关注自己");
        if(!desired){db.activeMember(member);db.run("DELETE FROM boost_follow WHERE member_id=? AND seller_id=?",member,seller);return false;}
        // Reciprocal follows must not hold opposite actor locks while waiting for seller FK locks.
        db.one("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE",Math.min(member,seller));
        db.one("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE",Math.max(member,seller));
        db.activeMember(member);
        db.one("SELECT id FROM boost_member WHERE id=? AND status='ACTIVE'",seller);
        if(db.count("SELECT COUNT(*) FROM boost_follow WHERE member_id=? AND seller_id=?",member,seller)>0)return true;
        db.run("INSERT INTO boost_follow(member_id,seller_id) VALUES (?,?)",member,seller);return true;
    }
    public Map<String,Object> shop(long seller,Long member) {
        Map<String,Object> row=db.one("SELECT id,nickname,avatar,skill,certification,created_at FROM boost_member WHERE id=? AND status='ACTIVE'",seller);
        if(player!=null){Map<String,Object> p=player.profile(seller);for(String k:Arrays.asList("shopName","shopIntro","shopStatus","realnameStatus","membershipActive","level","bio","ipProvince","growthPoints","growthProgress","nextLevelPoints","growthRulesReady","userNo"))row.put(k,p.get(k));if(playerMode()){row.remove("skill");row.remove("certification");}}
        row.put("followers",db.count("SELECT COUNT(*) FROM boost_follow WHERE seller_id=?",seller));
        row.put("completed",db.count("SELECT COUNT(*) FROM boost_order WHERE worker_id=? AND status='COMPLETED'"+(playerMode()?" AND business_type='PLAYER_ITEM'":""),seller));
        row.put("followed",member!=null&&db.count("SELECT COUNT(*) FROM boost_follow WHERE member_id=? AND seller_id=?",member,seller)>0);return row;
    }
    public Map<String,Object> searchPlayer(String input,Long viewer){
        long id=BoostPlayerMarket.memberIdFromUserNo(input);
        if(db.count("SELECT COUNT(*) FROM boost_member WHERE id=? AND status='ACTIVE'",id)==0)return map("items",List.of(),"total",0);
        return map("items",List.of(shop(id,viewer)),"total",1);
    }
    public Map<String,Object> follows(long id,Map<String,String> q){Map<String,Object> result=BoostPages.query(db,q,"SELECT m.id,m.nickname,m.avatar,m.skill,m.certification","FROM boost_follow f JOIN boost_member m ON m.id=f.seller_id WHERE f.member_id=? AND m.status='ACTIVE'","m.id DESC",id);if(playerMode())for(Map<String,Object> item:(List<Map<String,Object>>)result.get("items")){Map<String,Object> p=player.profile(num(item,"id"));for(String k:Arrays.asList("shopName","shopIntro","shopStatus","realnameStatus"))item.put(k,p.get(k));item.remove("skill");item.remove("certification");}return result;}
}
