package com.ruoyi.boost;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service
public class BoostTrade {
    private final BoostDb db; private final BoostAuth auth;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    @Value("${boost.simulated-payment:false}") private boolean simulated;
    @Value("${boost.payments-enabled:true}") private boolean paymentsEnabled=true;
    public BoostTrade(BoostDb db,BoostAuth auth){this.db=db;this.auth=auth;}
    public boolean simulated(){return simulated;}
    public boolean paymentsEnabled(){return paymentsEnabled;}
    public void requirePayments(){if(!paymentsEnabled)throw new BoostException(503,"支付及资金操作暂未开放");}
    public static final String SELECT="SELECT o.*,b.nickname AS buyer_name,w.nickname AS worker_name,w.avatar AS worker_avatar,COALESCE(o.snapshot_images,l.images) AS images FROM boost_order o JOIN boost_member b ON b.id=o.buyer_id JOIN boost_member w ON w.id=o.worker_id JOIN boost_listing l ON l.id=o.listing_id ";
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> create(long member,Map<String,Object> b,boolean accept) {
        long listing=number(b,"listingId",1,Long.MAX_VALUE);String key=text(b,"requestKey",8,64);
        long owner=num(db.one("SELECT owner_id FROM boost_listing WHERE id=?",listing),"ownerId");
        lockMembers(member,owner);db.activeMember(member);
        Map<String,Object> l=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",listing);
        boolean item=BoostPlayerMarket.item(l),unit=item&&BoostItemStock.unit(l);
        if(player!=null&&player.enabled())BoostException.require(item,"历史代练发布已停止交易");
        if(item&&accept)throw new BoostException(400,"求购请联系买家协商，再从卖家发布的道具下单");
        BoostException.require(num(l,"ownerId")==owner,"发布者已变化，请刷新后重试");
        long buyer=accept?num(l,"ownerId"):member,worker=accept?member:num(l,"ownerId");
        List<Map<String,Object>> repeated=db.list("SELECT * FROM boost_order WHERE buyer_id=? AND request_key=? FOR UPDATE",buyer,key);
        if(!repeated.isEmpty()){BoostException.require(num(repeated.get(0),"listingId")==listing&&num(repeated.get(0),"workerId")==worker,"请求编号已使用");if(BoostItemStock.unit(repeated.get(0))&&!BoostIdempotency.digest(b).equals(str(repeated.get(0),"purchaseHash")))throw new BoostException(409,"此请求已用于另一组购买信息，请核对原订单");return repeated.get(0);}
        BoostException.require("ACTIVE".equals(str(l,"status")),"发布已下架、待审核或已被接单");
        BoostException.require(buyer!=worker,"不能购买或承接自己的发布");
        BoostException.require((accept?"REQUEST":"SERVICE").equals(str(l,"kind")),"发布类型不匹配");
        // Compare the displayed terms while holding the same lock used to snapshot the order.
        if(b.containsKey("quoteKey")&&!BoostMarket.quoteKey(l).equals(text(b,"quoteKey",1,64)))
            throw new BoostException(409,"服务内容或价格已更新，请重新确认后下单");
        if(item){BoostException.require(player!=null,"道具交易配置不可用");player.requirePublisher(worker);}else requireWorker(worker);
        BoostException.require("ACTIVE".equals(str(db.one("SELECT status FROM boost_member WHERE id=? FOR UPDATE",buyer),"status")),"需求用户已停用，当前不可交易");
        long quantity=unit?number(b,"quantity",1,9999):num(l,"quantity");
        if(unit)BoostException.require(quantity<=BoostItemStock.available(l),"可购买数量不足，请刷新库存");
        long original=num(l,"priceCents")*(unit?quantity:1),discount=0;Long coupon=null;
        BoostException.require(original>=1&&original<=10000000,"单笔订单总金额不能超过 100000 元，请减少数量");
        if(!accept&&b.get("couponId")!=null&&!"".equals(String.valueOf(b.get("couponId")))){
            coupon=number(b,"couponId",1,Long.MAX_VALUE);
            Map<String,Object> c=db.one("SELECT * FROM boost_coupon WHERE id=? AND member_id=? AND used_order_id IS NULL AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",coupon,buyer);
            BoostException.require(original>=num(c,"minimumCents"),"未满足优惠券使用门槛");discount=Math.min(num(c,"discountCents"),original-1);
        }
        if(b.containsKey("expectedAmountCents")&&number(b,"expectedAmountCents",1,10000000)!=original-discount)
            throw new BoostException(409,"实付款已变化，请重新确认优惠券后下单");
        String note=accept?str(l,"description"):text(b,"note",0,1000);
        long id=db.insert("INSERT INTO boost_order(order_no,request_key,buyer_id,worker_id,listing_id,title,game,zone,platform,current_rank,target_rank,duration_hours,note,original_cents,discount_cents,amount_cents,coupon_id,snapshot_images,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                BoostOrderNumbers.next(db,java.time.Clock.systemUTC()),key,buyer,worker,listing,str(l,"title"),str(l,"game"),str(l,"zone"),str(l,"platform"),str(l,"currentRank"),str(l,"targetRank"),num(l,"durationHours"),note,original,discount,original-discount,coupon,str(l,"images"),java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(1800)));
        if(coupon!=null)db.run("UPDATE boost_coupon SET used_order_id=? WHERE id=?",id,coupon);
        if(item){db.run("UPDATE boost_order SET business_type=?,item_category=?,quantity=?,item_details=?,delivery_terms=?,pricing_mode=?,unit_price_cents=?,detail_images=?,purchase_hash=? WHERE id=?",BoostPlayerMarket.TYPE,str(l,"itemCategory"),quantity,str(l,"itemDetails"),str(l,"deliveryTerms"),unit?"UNIT":"LOT",unit?num(l,"priceCents"):0,str(l,"detailImages"),unit?BoostIdempotency.digest(b):"",id);if(unit)BoostItemStock.reserve(db,l,quantity);else db.run("UPDATE boost_listing SET status='RESERVED' WHERE id=?",listing);}
        if(accept){db.run("UPDATE boost_listing SET status='RESERVED' WHERE id=?",listing);db.notify(buyer,"需求有打手接单",paymentsEnabled?"请确认订单并支付，付款后打手开始服务。":"支付暂未开放，当前订单待付款，可取消订单。",id);}
        return detail(id,member);
    }
    public Map<String,Object> orders(long member,Map<String,String> q) {
        StringBuilder where=new StringBuilder(" WHERE ");List<Object> args=new ArrayList<>();
        where.append("worker".equals(q.get("role"))?"o.worker_id=?":"o.buyer_id=?");args.add(member);
        if("AFTERSALE".equals(q.get("status")))where.append(" AND o.status IN ('AFTERSALE','REFUNDING','REFUND_EXCEPTION','REFUNDED')");
        else if(q.containsKey("status")&&!"ALL".equals(q.get("status"))&&!q.get("status").isEmpty()){where.append(" AND o.status=?");args.add(q.get("status"));}
        String keyword=q.getOrDefault("keyword","").trim();BoostException.require(keyword.length()<=80,"搜索内容最多 80 个字符");
        if(!keyword.isEmpty()){where.append(" AND (o.order_no LIKE ? OR o.title LIKE ?)");args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        long total=db.count("SELECT COUNT(*) FROM boost_order o"+where,args.toArray());int page=page(q);args.add((page-1)*20);
        return map("items",db.list(SELECT+where+" ORDER BY o.id DESC LIMIT 20 OFFSET ?",args.toArray()),"total",total,"page",page);
    }
    public Map<String,Object> detail(long id,long member) {
        Map<String,Object> o=db.one(SELECT+" WHERE o.id=?",id);participant(o,member);
        o.put("updates",BoostPlayerMarket.item(o)?Collections.emptyList():db.list("SELECT * FROM boost_progress WHERE order_id=? ORDER BY id DESC",id));
        if(BoostPlayerMarket.item(o)){List<Map<String,Object>> delivery=db.list("SELECT id,note,images,created_at FROM boost_item_delivery WHERE order_id=?",id);o.put("delivery",delivery.isEmpty()?null:delivery.get(0));o.put("sellerName",o.get("workerName"));}
        List<Map<String,Object>> cases=db.list("SELECT * FROM boost_aftersale WHERE order_id=?",id);o.put("aftersale",cases.isEmpty()?null:cases.get(0));
        o.put("reviewed",db.count("SELECT COUNT(*) FROM boost_review WHERE order_id=?",id)>0);return o;
    }
    public void participant(Map<String,Object> o,long member){if(num(o,"buyerId")!=member&&num(o,"workerId")!=member)throw new BoostException(403,"无权查看此订单");}
    private void lockMembers(long first,long second){
        db.one("SELECT id FROM boost_member WHERE id=? FOR UPDATE",Math.min(first,second));
        if(first!=second)db.one("SELECT id FROM boost_member WHERE id=? FOR UPDATE",Math.max(first,second));
    }
    private void requireWorker(long worker){
        Map<String,Object> member=db.one("SELECT status,certification FROM boost_member WHERE id=? FOR UPDATE",worker);
        BoostException.require("ACTIVE".equals(str(member,"status"))&&"APPROVED".equals(str(member,"certification")),"请先完成打手认证");
    }
    /** All order writers, including system reconciliation, share members -> order -> listing locks.
     * Inactive participants may still have their unpaid reservations closed by the system. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<String,Object> lockOrder(long id){
        Map<String,Object> initial=db.one("SELECT buyer_id,worker_id FROM boost_order WHERE id=?",id);
        long buyer=num(initial,"buyerId"),worker=num(initial,"workerId");lockMembers(buyer,worker);
        Map<String,Object> order=db.one("SELECT * FROM boost_order WHERE id=? FOR UPDATE",id);
        BoostException.require(buyer==num(order,"buyerId")&&worker==num(order,"workerId"),"订单参与者已变化，请刷新后重试");
        return order;
    }
    private Map<String,Object> lock(long id){return lockOrder(id);}
    @Transactional
    public void simulatedPay(long member,long id) {
        requirePayments();
        BoostException.require(simulated,"测试支付未启用，请使用微信支付");Map<String,Object> o=lock(id);
        BoostException.require(num(o,"buyerId")==member,"只能支付自己的订单");
        if(!"WAIT_PAY".equals(str(o,"status"))){BoostException.require("SIMULATED".equals(str(o,"paymentMode")),"订单不可支付");return;}
        ensurePayableParticipants(o);paid(o,"SIMULATED","SIM-"+str(o,"orderNo"));
    }
    public void ensurePayableParticipants(Map<String,Object> o){
        Map<String,Object> worker=db.one("SELECT status,certification FROM boost_member WHERE id=? FOR UPDATE",num(o,"workerId"));
        if(BoostPlayerMarket.item(o)){BoostException.require(player!=null,"道具交易配置不可用");player.requirePublisher(num(o,"workerId"));}else BoostException.require("ACTIVE".equals(str(worker,"status"))&&"APPROVED".equals(str(worker,"certification")),"打手当前无法接单，请取消未付款订单或联系平台");
        BoostException.require("ACTIVE".equals(str(db.one("SELECT status FROM boost_member WHERE id=? FOR UPDATE",num(o,"buyerId")),"status")),"用户账号当前不可交易");
    }
    @Transactional
    public void paymentConfirmed(String orderNo,long amount,String transaction) {
        requirePayments();
        Map<String,Object> o=lock(num(db.one("SELECT id FROM boost_order WHERE order_no=?",orderNo),"id"));
        BoostException.require(num(o,"amountCents")==amount,"支付金额不一致");
        if(!"WAIT_PAY".equals(str(o,"status"))){BoostException.require(transaction.equals(str(o,"transactionId")),"订单状态与支付通知冲突");return;}
        paid(o,"WECHAT",transaction);
    }
    private void paid(Map<String,Object> o,String mode,String transaction) {
        long id=num(o,"id");
        boolean item=BoostPlayerMarket.item(o);
        db.run("UPDATE boost_order SET status=?,payment_mode=?,transaction_id=?,paid_at=CURRENT_TIMESTAMP WHERE id=?",item?"WAIT_DELIVERY":"WAIT_START",mode,transaction,id);
        if(item){if(BoostItemStock.unit(o))BoostItemStock.paid(db,o);else db.run("UPDATE boost_listing SET status='SOLD' WHERE id=? AND status='RESERVED'",num(o,"listingId"));}else db.run("UPDATE boost_listing SET status=CASE WHEN kind='REQUEST' THEN 'MATCHED' ELSE status END WHERE id=?",num(o,"listingId"));
        db.notify(num(o,"workerId"),"订单已付款",item?"请按订单约定交付道具并提交凭证。":"请确认需求并开始服务。",id);db.notify(num(o,"buyerId"),"付款成功",item?"等待卖家交付道具，可在订单内联系对方。":"等待打手开始服务，可在订单内联系对方。",id);
    }
    @Transactional
    public void action(long member,long id,String action) {
        if(!"cancel".equals(action))requirePayments();
        Map<String,Object> o=lock(id);participant(o,member);String status=str(o,"status");
        if("cancel".equals(action)) {
            BoostException.require("WAIT_PAY".equals(status),"仅未付款订单可取消，已付款请申请售后");
            BoostException.require(!"WECHAT".equals(str(o,"paymentMode")),"请先由微信确认关闭支付单");
            // Kind cannot be changed by editing. Only REQUEST cancellation releases a listing reservation.
            boolean request="REQUEST".equals(str(db.one("SELECT kind FROM boost_listing WHERE id=?",num(o,"listingId")),"kind"))||BoostPlayerMarket.item(o);
            // Member locks were acquired before the order, matching checkout and suspension.
            db.run("UPDATE boost_order SET status='CANCELLED' WHERE id=?",id);
            db.run("UPDATE boost_coupon SET used_order_id=NULL WHERE used_order_id=?",id);
            // Releasing an unpaid reservation must not republish a suspended account's request.
            if(request){if(BoostPlayerMarket.item(o)){boolean eligible=player!=null&&player.eligible(num(o,"workerId"));if(BoostItemStock.unit(o))BoostItemStock.release(db,o,eligible);else db.run("UPDATE boost_listing SET status=? WHERE id=? AND status='RESERVED'",eligible?"ACTIVE":"OFFLINE",num(o,"listingId"));}else db.run("UPDATE boost_listing SET status=CASE WHEN EXISTS (SELECT 1 FROM boost_member m WHERE m.id=boost_listing.owner_id AND m.status='ACTIVE') THEN 'ACTIVE' ELSE 'OFFLINE' END WHERE id=? AND status='RESERVED'",num(o,"listingId"));}
            db.notify(num(o,"buyerId"),"订单已取消","未付款订单已取消，已占用的优惠券已退回。",id);
        }else if("start".equals(action)) {
            BoostException.require(!BoostPlayerMarket.item(o),"道具订单请直接提交交付凭证，无代练开始或进度操作");
            BoostException.require(num(o,"workerId")==member&&"WAIT_START".equals(status),"仅接单打手可开始已付款订单");requireWorker(member);
            db.run("UPDATE boost_order SET status='IN_PROGRESS' WHERE id=?",id);
            db.run("INSERT INTO boost_progress(order_id,member_id,percent,note,images) VALUES (?,?,0,'打手开始服务','[]')",id,member);
            db.notify(num(o,"buyerId"),"服务已开始","打手已开始服务，后续进度将在订单内同步。",id);
        }else if("confirm".equals(action)) {
            BoostException.require(num(o,"buyerId")==member,"只有下单用户可以验收");if("COMPLETED".equals(status))return;
            BoostException.require((BoostPlayerMarket.item(o)?"WAIT_CONFIRM":"REVIEW").equals(status),"卖家提交交付后才能确认收货");
            long worker=num(o,"workerId"),income=num(o,"originalCents");
            Map<String,Object> w=db.one("SELECT * FROM boost_member WHERE id=? FOR UPDATE",worker);
            db.run("UPDATE boost_member SET balance_cents=balance_cents+? WHERE id=?",income,worker);
            db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents,order_id) VALUES (?,?,'SETTLEMENT',?,?,?,?)",worker,"settle:"+id,"订单结算 · "+str(o,"game"),income,num(w,"balanceCents")+income,id);
            db.run("UPDATE boost_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?",id);
            db.run("UPDATE boost_listing SET sales=sales+1 WHERE id=?",num(o,"listingId"));
            db.notify(worker,"订单已验收","结算收入已记入钱包。",id);
        }else throw new BoostException(400,"无效操作");
    }
    @Transactional
    public void progress(long member,long id,Map<String,Object> b) {
        requirePayments();
        Map<String,Object> o=lock(id);BoostException.require(num(o,"workerId")==member,"只能更新自己的服务订单");
        BoostException.require(!BoostPlayerMarket.item(o),"道具订单没有代练进度，请提交交付凭证");
        long percent=number(b,"percent",0,100);String note=text(b,"note",2,1000),imgs=images(b);
        // The order lock serializes writes. A lost response may be retried after delivery or acceptance.
        List<Map<String,Object>> latest=db.list("SELECT percent,note,images FROM boost_progress WHERE order_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE",id);
        if(!latest.isEmpty()&&num(latest.get(0),"percent")==percent&&note.equals(str(latest.get(0),"note"))&&imgs.equals(str(latest.get(0),"images")))return;
        BoostException.require("IN_PROGRESS".equals(str(o,"status")),"只能更新自己正在服务的订单");
        BoostException.require(percent>=num(o,"progress"),"进度不能低于已经提交的进度");
        db.uploadedImages(b,member,"DELIVERY");
        BoostException.require(percent<100||!"[]".equals(imgs),"提交验收时请附上成果截图");
        db.run("INSERT INTO boost_progress(order_id,member_id,percent,note,images) VALUES (?,?,?,?,?)",id,member,percent,note,imgs);
        db.run("UPDATE boost_order SET progress=?,status=? WHERE id=?",percent,percent==100?"REVIEW":"IN_PROGRESS",id);
        db.notify(num(o,"buyerId"),percent==100?"服务已完成，待验收":"订单进度更新",note,id);
    }
    @Transactional public void delivery(long member,long id,Map<String,Object> body){
        requirePayments();Map<String,Object> o=lock(id);db.activeMember(member);BoostException.require(BoostPlayerMarket.item(o)&&num(o,"workerId")==member,"只能交付自己的道具订单");
        String key=text(body,"requestKey",8,64),hash=BoostIdempotency.digest(body);
        List<Map<String,Object>> existing=db.list("SELECT * FROM boost_item_delivery WHERE order_id=? FOR UPDATE",id);
        if(!existing.isEmpty()){Map<String,Object> prior=existing.get(0);if(!key.equals(str(prior,"requestKey")))throw new BoostException(409,"此订单已有交付凭证");BoostIdempotency.same(prior,hash);return;}
        BoostException.require("WAIT_DELIVERY".equals(str(o,"status")),"仅已付款待交付订单可提交凭证");String note=text(body,"note",5,1000),proof=db.uploadedImages(body,member,"DELIVERY");BoostException.require(!"[]".equals(proof),"请上传道具交付凭证");
        db.run("INSERT INTO boost_item_delivery(order_id,seller_id,request_key,request_hash,note,images) VALUES (?,?,?,?,?,?)",id,member,key,hash,note,proof);db.run("UPDATE boost_order SET status='WAIT_CONFIRM' WHERE id=?",id);db.notify(num(o,"buyerId"),"卖家已提交道具交付","请核对实际收到的道具，再确认收货。",id);
    }
    @Transactional
    public void aftersale(long member,long id,Map<String,Object> b) {
        requirePayments();
        Map<String,Object> o=lock(id);BoostException.require(num(o,"buyerId")==member,"只能为自己的订单申请售后");
        String reason=text(b,"reason",2,100),description=text(b,"description",5,1000),imgs=images(b);
        List<Map<String,Object>> existing=db.list("SELECT reason,description,images FROM boost_aftersale WHERE order_id=? FOR UPDATE",id);
        if(!existing.isEmpty()){
            Map<String,Object> previous=existing.get(0);
            if(!reason.equals(str(previous,"reason"))||!description.equals(str(previous,"description"))||!imgs.equals(str(previous,"images")))
                throw new BoostException(409,"此订单已有不同内容的售后申请，请查看原申请及处理结果");
            return;
        }
        BoostException.require(Arrays.asList("WAIT_START","IN_PROGRESS","REVIEW","WAIT_DELIVERY","WAIT_CONFIRM").contains(str(o,"status")),"当前订单不可申请售后");
        db.uploadedImages(b,member,"AFTERSALE");
        db.run("INSERT INTO boost_aftersale(order_id,member_id,reason,description,images,amount_cents,previous_status) VALUES (?,?,?,?,?,?,?)",id,member,reason,description,imgs,num(o,"amountCents"),str(o,"status"));
        db.run("UPDATE boost_order SET status='AFTERSALE' WHERE id=?",id);db.notify(num(o,"workerId"),"订单申请售后","订单已暂停，请查看用户申请并联系平台。",id);
    }
    @Transactional
    public void review(long member,long id,Map<String,Object> b) {
        requirePayments();
        Map<String,Object> o=lock(id);BoostException.require(num(o,"buyerId")==member&&"COMPLETED".equals(str(o,"status")),"仅已验收订单可以评价");
        long stars=number(b,"stars",1,5);String content=text(b,"content",2,1000);
        List<Map<String,Object>> existing=db.list("SELECT stars,content FROM boost_review WHERE order_id=?",id);
        if(!existing.isEmpty()){
            if(num(existing.get(0),"stars")!=stars||!content.equals(str(existing.get(0),"content")))throw new BoostException(409,"此订单已有不同内容的评价，请查看原评价");
            return;
        }
        db.run("INSERT INTO boost_review(order_id,listing_id,member_id,stars,content) VALUES (?,?,?,?,?)",id,num(o,"listingId"),member,stars,content);
        db.notify(num(o,"workerId"),"收到新评价","用户完成了服务评价。",id);
    }
    public Map<String,Object> wallet(long member) {
        Map<String,Object> funds=db.one("SELECT balance_cents,deposit_cents FROM boost_member WHERE id=?",member);
        return map("balanceCents",num(funds,"balanceCents"),"depositCents",num(funds,"depositCents"),
            "pendingCents",db.count("SELECT COALESCE(SUM(original_cents),0) FROM boost_order WHERE worker_id=? AND status IN ('WAIT_START','IN_PROGRESS','REVIEW','WAIT_DELIVERY','WAIT_CONFIRM')",member),
            "totalCents",db.count("SELECT COALESCE(SUM(amount_cents),0) FROM boost_ledger WHERE member_id=? AND kind='SETTLEMENT'",member),
            "ledger",db.list("SELECT * FROM boost_ledger WHERE member_id=? ORDER BY id DESC LIMIT 100",member),
            "withdrawals",db.list("SELECT id,amount_cents,status,note,created_at FROM boost_withdrawal WHERE member_id=? ORDER BY id DESC LIMIT 100",member));
    }
    @Transactional
    public long withdraw(long member,Map<String,Object> b) {
        requirePayments();
        String key=text(b,"requestKey",8,64);Map<String,Object> m=db.one("SELECT * FROM boost_member WHERE id=? FOR UPDATE",member);
        long amount=number(b,"amountCents",100,10000000);String name=text(b,"accountName",2,100);
        String account=simulated?text(b,"accountNumber",4,100):"绑定微信余额";
        List<Map<String,Object>> repeated=db.list("SELECT * FROM boost_withdrawal WHERE member_id=? AND request_key=?",member,key);
        if(!repeated.isEmpty()){
            Map<String,Object> previous=repeated.get(0);
            if(num(previous,"amountCents")!=amount||!name.equals(str(previous,"accountName"))||!account.equals(str(previous,"accountNumber")))
                throw new BoostException(409,"该提现申请已提交，金额或收款信息不一致，请查看提现记录");
            return num(previous,"id");
        }
        BoostException.require(amount<=num(m,"balanceCents"),"可提现余额不足");
        if(!simulated)BoostException.require(!str(m,"openid").isEmpty(),"请在微信小程序绑定身份后申请提现");
        long id=db.insert("INSERT INTO boost_withdrawal(member_id,request_key,amount_cents,account_name,account_number) VALUES (?,?,?,?,?)",member,key,amount,name,account);
        db.run("UPDATE boost_member SET balance_cents=balance_cents-? WHERE id=?",amount,member);
        db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents) VALUES (?,?,'WITHDRAWAL','提现申请，等待审核',?,?)",member,"withdraw:"+id,-amount,num(m,"balanceCents")-amount);return id;
    }
}
