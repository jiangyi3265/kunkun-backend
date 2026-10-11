package com.ruoyi.boost;

import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=BoostFlowTest.Config.class) @Transactional
public class BoostFlowTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource dataSource(){JdbcDataSource s=new JdbcDataSource();s.setURL("jdbc:h2:mem:boost;MODE=MySQL;DB_CLOSE_DELAY=-1");ResourceDatabasePopulator p=new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql"));p.execute(s);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(s)));return s;}
  @Bean JdbcTemplate jdbcTemplate(DataSource d){return new JdbcTemplate(d);}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostDb db(JdbcTemplate j){return new BoostDb(j);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
  @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
  @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
  @Bean BoostMessages messages(BoostDb d){return new BoostMessages(d);}
  @Bean BoostWechatGateway wx(){return new BoostWechatGateway();}
  @Bean BoostPayments payments(BoostDb d,BoostWechatGateway w,BoostTrade t){return new BoostPayments(d,w,t);}
  @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
 }
 @Autowired BoostDb db;@Autowired BoostAuth auth;@Autowired BoostMarket market;@Autowired BoostTrade trade;@Autowired BoostMessages messages;@Autowired BoostAdmin admin;@Autowired BoostPayments payments;
 long buyer,worker,outsider,listing;String token;
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path mediaDir;
 @BeforeEach void setup()throws Exception{
  ReflectionTestUtils.setField(db,"mediaDirectory",mediaDir.toString());
  javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",mediaDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png").toFile());
  ReflectionTestUtils.setField(trade,"simulated",true);ReflectionTestUtils.setField(auth,"demoCoupons",true);
  Map<String,Object>b=auth.register(map("username","buyer_"+UUID.randomUUID().toString().substring(0,8),"password","StrongPass123!","nickname","测试买家"));buyer=num((Map<String,Object>)b.get("user"),"id");token=str(b,"token");
  worker=createMember("打手用户");outsider=createMember("无关用户");db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);
  db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES (?,?,?,?)","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",worker,"DELIVERY",1);
  java.nio.file.Files.copy(mediaDir.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"),mediaDir.resolve("cccccccccccccccccccccccccccccccc.png"));
  db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES (?,?,?,?)","cccccccccccccccccccccccccccccccc.png",worker,"LISTING",1);
  listing=market.publish(worker,listingBody("SERVICE"),null);admin.moderate(1,"listings",listing,map("action","approve","note","审核通过"));
 }
 long createMember(String nick){return num((Map<String,Object>)auth.register(map("username","user_"+UUID.randomUUID().toString().substring(0,8),"password","StrongPass123!","nickname",nick)).get("user"),"id");}
 Map<String,Object>listingBody(String kind){return map("requestKey",UUID.randomUUID().toString(),"kind",kind,"title","王者荣耀星耀冲分测试","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀 III","targetRank","星耀 I","durationHours",24,"priceCents",12000,"description","请同步游戏进度，文明沟通并按时完成","images",Collections.emptyList());}
 Map<String,Object> orderBody(){return map("listingId",listing,"requestKey",UUID.randomUUID().toString(),"note","晚上八点开始");}
 long order(){return num(trade.create(buyer,orderBody(),false),"id");}
 @Test void changedPriceRequiresBuyerToConfirmAgain(){
  Map<String,Object> b=orderBody();b.put("quoteKey",market.detail(listing,buyer).get("quoteKey"));
  Map<String,Object> edited=listingBody("SERVICE");edited.put("priceCents",24000);
  market.publish(worker,edited,listing);admin.moderate(1,"listings",listing,map("action","approve","note","变价审核通过"));
  assertThrows(BoostException.class,()->trade.create(buyer,b,false));
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=?",buyer));
 }
 @Test void changedTermsAndRequestBudgetRequireConfirmation(){
  Map<String,Object> b=orderBody();b.put("quoteKey",market.detail(listing,buyer).get("quoteKey"));
  Map<String,Object> edited=listingBody("SERVICE");edited.put("targetRank","荣耀王者");
  market.publish(worker,edited,listing);admin.moderate(1,"listings",listing,map("action","approve","note","新目标审核通过"));
  assertThrows(BoostException.class,()->trade.create(buyer,b,false));
  b.put("quoteKey",market.detail(listing,buyer).get("quoteKey"));long id=num(trade.create(buyer,b,false),"id");
  assertEquals("荣耀王者",str(trade.detail(id,buyer),"targetRank"));
  market.listingAction(worker,listing,"offline");assertEquals(id,num(trade.create(buyer,b,false),"id"));
  long request=market.publish(buyer,listingBody("REQUEST"),null);admin.moderate(1,"listings",request,map("action","approve","note","需求审核通过"));
  Map<String,Object> accept=map("listingId",request,"requestKey",UUID.randomUUID().toString(),"quoteKey",market.detail(request,worker).get("quoteKey"));
  edited=listingBody("REQUEST");edited.put("priceCents",1000);market.publish(buyer,edited,request);admin.moderate(1,"listings",request,map("action","approve","note","预算变更审核通过"));
  assertThrows(BoostException.class,()->trade.create(worker,accept,true));
  assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",request),"status"));
 }
 @Test void changedCouponTotalCannotCreateAnUnexpectedCharge(){
  Map<String,Object> b=orderBody();b.put("expectedAmountCents",11000);
  assertThrows(BoostException.class,()->trade.create(buyer,b,false));
  long coupon=db.count("SELECT MAX(id) FROM boost_coupon WHERE member_id=?",buyer);b.put("couponId",coupon);
  assertEquals(11000,num(trade.create(buyer,b,false),"amountCents"));
 }
 @Test void changedWithdrawalRetryCannotSilentlyUseOldAmountOrRecipient(){
  complete(order());Map<String,Object> b=map("amountCents",100,"accountName","测试打手","accountNumber","test-account","requestKey",UUID.randomUUID().toString());
  long id=trade.withdraw(worker,b);b.put("amountCents",200);
  assertThrows(BoostException.class,()->trade.withdraw(worker,b));
  b.put("amountCents",100);b.put("accountNumber","different-account");
  assertThrows(BoostException.class,()->trade.withdraw(worker,b));
  b.put("accountNumber","test-account");b.put("accountName","另一收款人");
  assertThrows(BoostException.class,()->trade.withdraw(worker,b));
  b.put("accountName","测试打手");assertEquals(id,trade.withdraw(worker,b));
  assertEquals(11900,num(auth.me(worker),"balanceCents"));
  assertEquals(1,db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE member_id=?",worker));
 }
 @Test void cancelledOrderCanRetryWithoutReleasingAReusedCoupon(){
  long coupon=db.count("SELECT MAX(id) FROM boost_coupon WHERE member_id=?",buyer);
  Map<String,Object> b=orderBody();b.put("couponId",coupon);long id=num(trade.create(buyer,b,false),"id");
  payments.closeUnpaid(buyer,id);
  Map<String,Object> next=orderBody();next.put("couponId",coupon);long nextId=num(trade.create(buyer,next,false),"id");
  assertDoesNotThrow(()->payments.closeUnpaid(buyer,id));
  assertEquals(nextId,num(db.one("SELECT used_order_id FROM boost_coupon WHERE id=?",coupon),"usedOrderId"));
  assertThrows(BoostException.class,()->payments.closeUnpaid(outsider,id));
 }
 void complete(long id){trade.simulatedPay(buyer,id);trade.action(worker,id,"start");trade.progress(worker,id,map("percent",100,"note","已完成，请验收","images",Arrays.asList("/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png")));trade.action(buyer,id,"confirm");}
 @Test void transferNotificationsValidateRecipientAndRestoreBalanceOnce(){
  complete(order());db.run("UPDATE boost_member SET openid='worker_openid' WHERE id=?",worker);
  BoostWechatGateway gateway=(BoostWechatGateway)ReflectionTestUtils.getField(payments,"wx");gateway.merchantId="test_merchant";
  long id=trade.withdraw(worker,map("amountCents",3000,"accountName","测试打手","accountNumber","test-account","requestKey",UUID.randomUUID().toString()));
  db.run("UPDATE boost_withdrawal SET status='PROCESSING' WHERE id=?",id);
  Map<String,Object> callback=map("mch_id","test_merchant","out_bill_no","WD"+id,"transfer_amount",3000,"openid","wrong_recipient","state","FAIL");
  assertThrows(BoostException.class,()->payments.transferNotify(callback));assertEquals(9000,num(auth.me(worker),"balanceCents"));
  callback.put("openid","worker_openid");callback.put("transfer_amount",1);assertThrows(BoostException.class,()->payments.transferNotify(callback));
  callback.put("transfer_amount",3000);payments.transferNotify(callback);payments.transferNotify(callback);
  assertEquals(12000,num(auth.me(worker),"balanceCents"));assertEquals("FAILED",str(db.one("SELECT status FROM boost_withdrawal WHERE id=?",id),"status"));
  assertEquals(1,db.count("SELECT COUNT(*) FROM boost_ledger WHERE event_key=?","withdraw-return:"+id));
 }
 @Test void longProgressKeepsFullDetailsAndTruncatesNotification(){
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");
  String note=String.join("",Collections.nCopies(1000,"进"));
  trade.progress(worker,id,map("percent",60,"note",note,"images",Collections.emptyList()));
  assertEquals(note,str(db.one("SELECT note FROM boost_progress WHERE order_id=? AND percent=60",id),"note"));
  String preview=str(db.one("SELECT content FROM boost_notification WHERE order_id=? AND title='订单进度更新'",id),"content");
  assertEquals(500,preview.length());assertTrue(preview.endsWith("…"));
 }
 @Test void editingListingDoesNotChangePurchasedImagesOrTerms(){
  long id=order();Map<String,Object> changed=listingBody("SERVICE");
  changed.put("title","修改后的王者荣耀服务");changed.put("priceCents",99900);
  changed.put("images",Arrays.asList("/boost-media/cccccccccccccccccccccccccccccccc.png"));
  market.publish(worker,changed,listing);Map<String,Object> purchased=trade.detail(id,buyer);
  assertEquals("[]",str(purchased,"images"));assertEquals(12000,num(purchased,"amountCents"));
  assertEquals("王者荣耀星耀冲分测试",str(purchased,"title"));
 }
 @Test void pendingRefundReconciliationDoesNotFloodNotifications(){
  long id=order();trade.simulatedPay(buyer,id);db.run("UPDATE boost_order SET status='REFUNDING' WHERE id=?",id);
  BoostWechatGateway gateway=(BoostWechatGateway)ReflectionTestUtils.getField(payments,"wx");gateway.merchantId="test_merchant";
  String no=str(trade.detail(id,buyer),"orderNo");
  Map<String,Object> callback=map("mchid","test_merchant","out_trade_no",no,"out_refund_no","RF"+no,"amount",map("refund",12000,"total",12000),"refund_status","PROCESSING");
  long count=db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=?",buyer);
  payments.refundNotify(callback);payments.refundNotify(callback);
  assertEquals(count,db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=?",buyer));
  callback.put("refund_status","SUCCESS");payments.refundNotify(callback);payments.refundNotify(callback);
  assertEquals(count+1,db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=?",buyer));
  assertEquals(1,db.count("SELECT COUNT(*) FROM boost_ledger WHERE event_key=?","refund:"+id));
 }
 @Test void conversationsCatchUpWithoutSkippingMessagesAndTrackReadState(){
  long c=messages.open(buyer,map("peerId",worker));messages.send(buyer,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","第一条"));
  long cursor=db.count("SELECT MAX(id) FROM boost_message WHERE conversation_id=?",c);
  for(int i=0;i<65;i++)messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","连续消息 "+i));
  Map<String,Object> first=messages.messages(buyer,c,0,cursor);List<Map<String,Object>> rows=(List<Map<String,Object>>)first.get("items");
  assertEquals(50,rows.size());assertEquals(true,first.get("hasNewer"));assertEquals("连续消息 0",str(rows.get(0),"content"));
  Map<String,Object> second=messages.messages(buyer,c,0,num(rows.get(49),"id"));
  assertEquals(15,((List<?>)second.get("items")).size());assertEquals(false,second.get("hasNewer"));
  messages.read(worker,c);assertEquals(cursor,num(messages.messages(buyer,c,0,cursor),"readThrough"));
  assertThrows(BoostException.class,()->messages.messages(outsider,c,0,cursor));
 }
 @Test void missingUploadsCannotBeUsedAsDeliveryEvidenceOrCertification(){
  String missing="/boost-media/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png";
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");
  assertThrows(BoostException.class,()->trade.progress(worker,id,map("percent",100,"note","伪造不存在的成果","images",Arrays.asList(missing))));
  assertEquals("IN_PROGRESS",str(trade.detail(id,buyer),"status"));
  assertThrows(BoostException.class,()->auth.apply(outsider,map("skill","王者荣耀段位证明","images",Arrays.asList(missing))));
  long c=messages.open(buyer,map("peerId",worker));
  assertThrows(BoostException.class,()->messages.send(buyer,c,map("requestKey",UUID.randomUUID().toString(),"kind","IMAGE","content",missing)));
 }
 @Test void orderSearchIsScopedAndAftersaleIncludesRefundStates(){
  long id=order();Map<String,Object> o=trade.detail(id,buyer);
  Map<String,String> q=new HashMap<>();q.put("keyword",str(o,"orderNo"));
  assertEquals(1,num(trade.orders(buyer,q),"total"));assertEquals(0,num(trade.orders(outsider,q),"total"));
  q.put("role","worker");assertEquals(1,num(trade.orders(worker,q),"total"));q.put("status","AFTERSALE");
  for(String state:Arrays.asList("AFTERSALE","REFUNDING","REFUNDED")){db.run("UPDATE boost_order SET status=? WHERE id=?",state,id);assertEquals(1,num(trade.orders(worker,q),"total"));}
  q.put("status","CANCELLED");assertEquals(0,num(trade.orders(worker,q),"total"));
 }
 @Test void blockedWorkerCannotReceiveNewPaymentButCanBeRefunded(){
  long id=order();admin.moderate(1,"members",worker,map("action","block","note","测试暂停接单"));
  assertThrows(BoostException.class,()->trade.simulatedPay(buyer,id));assertEquals("WAIT_PAY",str(trade.detail(id,buyer),"status"));
  trade.action(buyer,id,"cancel");assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));
 }
 @Test void passwordChangeVerifiesOldPasswordAndRevokesEverySession(){
  MockHttpServletRequest first=new MockHttpServletRequest();first.addHeader("X-Boost-Token",token);
  MockHttpServletRequest second=new MockHttpServletRequest();second.addHeader("X-Boost-Token",str(auth.session(buyer),"token"));
  assertThrows(BoostException.class,()->auth.changePassword(buyer,map("oldPassword","incorrect","newPassword","ChangedPass2026!")));
  assertEquals(buyer,auth.member(first));assertEquals(buyer,auth.member(second));
  auth.changePassword(buyer,map("oldPassword","StrongPass123!","newPassword","ChangedPass2026!"));
  assertThrows(BoostException.class,()->auth.member(first));assertThrows(BoostException.class,()->auth.member(second));
  String username=str(auth.me(buyer),"username");assertThrows(BoostException.class,()->auth.login(map("username",username,"password","StrongPass123!"),"test"));
  assertEquals(buyer,num((Map<String,Object>)auth.login(map("username",username,"password","ChangedPass2026!"),"test").get("user"),"id"));
 }
 @Test void readingOneNoticeDoesNotMarkOthersOrOtherMembersNoticesRead(){
  db.notify(buyer,"第一条","通知内容",null);long first=db.count("SELECT MAX(id) FROM boost_notification WHERE member_id=?",buyer);
  db.notify(buyer,"第二条","通知内容",null);long second=db.count("SELECT MAX(id) FROM boost_notification WHERE member_id=?",buyer);
  assertThrows(BoostException.class,()->messages.readNotice(outsider,first));messages.readNotice(buyer,first);messages.readNotice(buyer,first);
  assertEquals(1,db.count("SELECT COUNT(*) FROM boost_notification WHERE id=? AND is_read=TRUE",first));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_notification WHERE id=? AND is_read=FALSE",second));
 }
 @Test void chatReadCursorDoesNotMarkUnfetchedArrivalsRead(){
  long c=messages.open(buyer,map("peerId",worker));messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","已经加载的消息"));
  long seen=db.count("SELECT MAX(id) FROM boost_message WHERE conversation_id=?",c);messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","请求之后新到的消息"));
  messages.read(buyer,c,seen);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND is_read=FALSE",c));
  assertThrows(BoostException.class,()->messages.read(outsider,c,Long.MAX_VALUE));
 }
 @Test void reviewsBeyondTheFirstTenAreReachableWithoutLeakingPrivateListings(){
  for(int i=0;i<12;i++){long id=order();db.run("UPDATE boost_order SET status='COMPLETED' WHERE id=?",id);trade.review(buyer,id,map("stars",5,"content","验收评价 "+i));}
  Map<String,Object> first=market.reviews(listing,buyer,Collections.singletonMap("page","1"));Map<String,Object> second=market.reviews(listing,buyer,Collections.singletonMap("page","2"));
  assertEquals(12,num(first,"total"));assertEquals(10,((List<?>)first.get("items")).size());assertEquals(2,((List<?>)second.get("items")).size());
  assertEquals("验收评价 0",str(((List<Map<String,Object>>)second.get("items")).get(1),"content"));assertEquals(12,num(market.detail(listing,buyer),"reviewTotal"));
  market.listingAction(worker,listing,"offline");assertThrows(BoostException.class,()->market.reviews(listing,buyer,Collections.emptyMap()));assertEquals(12,num(market.reviews(listing,worker,Collections.emptyMap()),"total"));
 }
 @Test void rejectionAtAcceptanceStageRestoresDeliveryAndSettlesOnce(){
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");trade.progress(worker,id,map("percent",100,"note","成果完成，请验收","images",Arrays.asList("/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png")));
  trade.aftersale(buyer,id,map("reason","成果有误","description","申请核对提交的成果截图"));long claim=db.count("SELECT id FROM boost_aftersale WHERE order_id=?",id);
  assertThrows(BoostException.class,()->trade.action(buyer,id,"confirm"));admin.aftersale(1,claim,map("action","reject","note","核对后成果已满足约定"));
  assertEquals("REVIEW",str(trade.detail(id,buyer),"status"));trade.action(buyer,id,"confirm");trade.action(buyer,id,"confirm");assertEquals(12000,num(auth.me(worker),"balanceCents"));
 }
 @Test void approvedSimulatedWithdrawalCannotBeApprovedOrReturnedTwice(){
  complete(order());long id=trade.withdraw(worker,map("amountCents",100,"accountName","测试打手","accountNumber","test-account","requestKey",UUID.randomUUID().toString()));
  admin.withdrawal(1,id,map("action","approve","note","测试批准提现"));assertEquals("SIMULATED_PAID",str(db.one("SELECT status FROM boost_withdrawal WHERE id=?",id),"status"));
  assertThrows(BoostException.class,()->admin.withdrawal(1,id,map("action","approve","note","重复批准提现")));assertThrows(BoostException.class,()->admin.withdrawal(1,id,map("action","reject","note","重复退回提现")));assertEquals(11900,num(auth.me(worker),"balanceCents"));
 }
 @Test void realWithdrawalsCannotUseAnUnboundRecipient(){
  complete(order());ReflectionTestUtils.setField(trade,"simulated",false);
  assertThrows(BoostException.class,()->trade.withdraw(worker,map("amountCents",100,"accountName","测试打手","accountNumber","arbitrary-account","requestKey",UUID.randomUUID().toString())));
  assertEquals(12000,num(auth.me(worker),"balanceCents"));
 }
 @Test void memberSessionsAreRevocableAndNoPasswordsLeak(){
  MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("X-Boost-Token",token);assertEquals(buyer,auth.member(r));assertFalse(auth.me(buyer).containsKey("passwordHash"));auth.logout(r);assertThrows(BoostException.class,()->auth.member(r));
 }
 @Test void usernameCaseChangesCannotBypassLoginAttemptLimit(){
  String username=str(auth.me(buyer),"username"),ip="limit-test";
  for(int i=0;i<5;i++)assertEquals(400,assertThrows(BoostException.class,()->auth.login(map("username",username,"password","wrong-password"),ip)).code);
  assertEquals(429,assertThrows(BoostException.class,()->auth.login(map("username",username.toUpperCase(Locale.ROOT),"password","wrong-password"),ip)).code);
  assertEquals(429,assertThrows(BoostException.class,()->auth.login(map("username",username,"password","StrongPass123!"),ip)).code);
  assertEquals(buyer,num((Map<String,Object>)auth.login(map("username",username,"password","StrongPass123!"),"another-client").get("user"),"id"));
 }
 @Test void strangersCannotAccessOrdersOrConversations(){
  long id=order();assertThrows(BoostException.class,()->trade.detail(id,outsider));long c=messages.open(buyer,map("peerId",worker));assertThrows(BoostException.class,()->messages.messages(outsider,c,0));assertThrows(BoostException.class,()->messages.send(outsider,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","伪造消息")));
 }
 @Test void chatCannotRevealAnUnrelatedPendingListing(){
  long pending=market.publish(worker,listingBody("SERVICE"),null);
  assertThrows(BoostException.class,()->market.detail(pending,outsider));
  long conversation=messages.open(outsider,map("peerId",buyer,"listingId",pending));
  assertNull(messages.messages(outsider,conversation,0).get("listing"));
  assertNull(messages.messages(buyer,conversation,0).get("listing"));
 }
 @Test void chatAndSavedListsDoNotRevealUnreviewedEdits(){
  market.detail(listing,buyer);market.favorite(buyer,listing,true);
  long conversation=messages.open(buyer,map("peerId",worker,"listingId",listing));
  assertNotNull(messages.messages(buyer,conversation,0).get("listing"));
  Map<String,Object> edit=listingBody("SERVICE");edit.put("title","尚未通过审核的私有修改");
  market.publish(worker,edit,listing);
  assertNull(messages.messages(buyer,conversation,0).get("listing"));
  assertNotNull(messages.messages(worker,conversation,0).get("listing"));
  assertEquals(0,((List<?>)market.saved(buyer,"favorites").get("items")).size());
  assertEquals(0,((List<?>)market.saved(buyer,"history").get("items")).size());
  assertEquals(0,num(auth.me(buyer),"favorites"));assertEquals(0,num(auth.me(buyer),"history"));
  admin.moderate(1,"listings",listing,map("action","approve","note","重新审核通过"));
  assertNotNull(messages.messages(buyer,conversation,0).get("listing"));
  assertEquals(1,((List<?>)market.saved(buyer,"favorites").get("items")).size());
  assertEquals(1,num(auth.me(buyer),"favorites"));
 }
 @Test void paymentAndAcceptanceCannotCreditTwice(){
  Map<String,Object>b=orderBody();long id=num(trade.create(buyer,b,false),"id");assertEquals(id,num(trade.create(buyer,b,false),"id"));complete(id);trade.simulatedPay(buyer,id);trade.action(buyer,id,"confirm");assertEquals(12000,num(auth.me(worker),"balanceCents"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_ledger WHERE event_key=?","settle:"+id));
 }
 @Test void unauthorizedTransitionsAndRegressiveProgressAreRejected(){
  long id=order();assertThrows(BoostException.class,()->trade.simulatedPay(worker,id));assertThrows(BoostException.class,()->trade.action(buyer,id,"confirm"));trade.simulatedPay(buyer,id);assertThrows(BoostException.class,()->trade.action(buyer,id,"start"));trade.action(worker,id,"start");trade.progress(worker,id,map("percent",70,"note","升到星耀 II","images",Collections.emptyList()));assertThrows(BoostException.class,()->trade.progress(worker,id,map("percent",20,"note","降低进度")));assertThrows(BoostException.class,()->trade.progress(worker,id,map("percent",100,"note","没有成果图","images",Collections.emptyList())));
 }
 @Test void partialProgressRetryDoesNotDuplicateTimelineOrNotification(){
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");
  Map<String,Object> b=map("percent",50,"note","已经完成一半约定进度","images",Collections.emptyList());
  trade.progress(worker,id,b);long notices=db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id);
  trade.progress(worker,id,b);
  assertEquals(2,db.count("SELECT COUNT(*) FROM boost_progress WHERE order_id=?",id));
  assertEquals(notices,db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id));
  assertThrows(BoostException.class,()->trade.progress(outsider,id,b));
  b.put("note","补充同一进度的新说明");trade.progress(worker,id,b);assertEquals(3,db.count("SELECT COUNT(*) FROM boost_progress WHERE order_id=?",id));
 }
 @Test void completedDeliveryRetryRecoversWithoutRepeatingNotifications(){
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");
  Map<String,Object> b=map("percent",100,"note","已完成约定目标，请核验","images",Collections.singletonList("/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"));
  trade.progress(worker,id,b);long notices=db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id);
  assertDoesNotThrow(()->trade.progress(worker,id,b));trade.action(buyer,id,"confirm");assertDoesNotThrow(()->trade.progress(worker,id,b));
  assertEquals(2,db.count("SELECT COUNT(*) FROM boost_progress WHERE order_id=?",id));
  assertEquals(notices+1,db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id));
  assertEquals(12000,num(auth.me(worker),"balanceCents"));
  b.put("note","不同的交付描述");assertThrows(BoostException.class,()->trade.progress(worker,id,b));
 }
 @Test void aftersaleRetryRecoversOriginalApplicationWithoutOverwritingIt(){
  long id=order();trade.simulatedPay(buyer,id);
  Map<String,Object> b=map("reason","进度不符","description","服务未按约定时间开始","images",Collections.emptyList());
  trade.aftersale(buyer,id,b);long notices=db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id);
  assertDoesNotThrow(()->trade.aftersale(buyer,id,b));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_aftersale WHERE order_id=?",id));assertEquals(notices,db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id));
  assertThrows(BoostException.class,()->trade.aftersale(outsider,id,b));
  long claim=db.count("SELECT id FROM boost_aftersale WHERE order_id=?",id);admin.aftersale(1,claim,map("action","reject","note","继续按约定交付"));
  assertDoesNotThrow(()->trade.aftersale(buyer,id,b));assertEquals("WAIT_START",str(trade.detail(id,buyer),"status"));
  b.put("description","新的申请不能覆盖之前的处理结果");assertEquals(409,assertThrows(BoostException.class,()->trade.aftersale(buyer,id,b)).code);
 }
 @Test void reviewRetryRecoversOriginalRatingWithoutOverwritingIt(){
  long id=order();complete(id);Map<String,Object> b=map("stars",5,"content","服务按时完成，沟通顺畅");
  trade.review(buyer,id,b);long notices=db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id);
  assertDoesNotThrow(()->trade.review(buyer,id,b));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_review WHERE order_id=?",id));assertEquals(notices,db.count("SELECT COUNT(*) FROM boost_notification WHERE order_id=?",id));
  assertThrows(BoostException.class,()->trade.review(outsider,id,b));b.put("stars",1);assertEquals(409,assertThrows(BoostException.class,()->trade.review(buyer,id,b)).code);
 }
 @Test void cancelledOrdersReleaseCoupons(){
  long coupon=db.count("SELECT MAX(id) FROM boost_coupon WHERE member_id=?",buyer);Map<String,Object>b=orderBody();b.put("couponId",coupon);long id=num(trade.create(buyer,b,false),"id");assertEquals(11000,num(trade.detail(id,buyer),"amountCents"));assertThrows(BoostException.class,()->trade.create(buyer,new HashMap<String,Object>(b){{put("requestKey",UUID.randomUUID().toString());}},false));trade.action(buyer,id,"cancel");assertEquals(1,db.count("SELECT COUNT(*) FROM boost_coupon WHERE id=? AND used_order_id IS NULL",coupon));
 }
 @Test void requestCannotBeTakenTwiceAndCancelReopensIt(){
  long req=market.publish(buyer,listingBody("REQUEST"),null);admin.moderate(1,"listings",req,map("action","approve","note","需求通过"));long id=num(trade.create(worker,map("listingId",req,"requestKey",UUID.randomUUID().toString()),true),"id");assertThrows(BoostException.class,()->trade.create(worker,map("listingId",req,"requestKey",UUID.randomUUID().toString()),true));trade.action(worker,id,"cancel");assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",req),"status"));
 }
 @Test void aftersaleFreezesDeliveryAndRefundsExactlyOnce(){
  long id=order();trade.simulatedPay(buyer,id);trade.action(worker,id,"start");trade.aftersale(buyer,id,map("reason","进度不符","description","未在约定时间开始游戏服务","images",Collections.emptyList()));assertThrows(BoostException.class,()->trade.progress(worker,id,map("percent",50,"note","继续上分","images",Collections.emptyList())));long c=db.count("SELECT id FROM boost_aftersale WHERE order_id=?",id);admin.aftersale(1,c,map("action","approve","note","根据记录同意退款"));assertEquals("REFUNDED",str(trade.detail(id,buyer),"status"));assertThrows(BoostException.class,()->admin.aftersale(1,c,map("action","approve","note","重复退款")));assertEquals(0,num(auth.me(worker),"balanceCents"));
 }
 @Test void rejectedAftersaleResumesPreviousStatus(){long id=order();trade.simulatedPay(buyer,id);trade.aftersale(buyer,id,map("reason","协商退款","description","测试申请售后的处理流程"));long c=db.count("SELECT id FROM boost_aftersale WHERE order_id=?",id);admin.aftersale(1,c,map("action","reject","note","证据不充分，请继续沟通"));assertEquals("WAIT_START",str(trade.detail(id,buyer),"status"));}
 @Test void withdrawalsFreezeAndRestoreExactlyOnce(){
  complete(order());Map<String,Object>b=map("amountCents",3000,"accountName","测试打手","accountNumber","test-account","requestKey",UUID.randomUUID().toString());long id=trade.withdraw(worker,b);assertEquals(id,trade.withdraw(worker,b));assertEquals(9000,num(auth.me(worker),"balanceCents"));admin.withdrawal(1,id,map("action","reject","note","信息不完整，退回余额"));assertEquals(12000,num(auth.me(worker),"balanceCents"));assertThrows(BoostException.class,()->admin.withdrawal(1,id,map("action","reject","note","重复退回")));
 }
 @Test void productionModeRefusesSimulatedPayments(){long id=order();ReflectionTestUtils.setField(trade,"simulated",false);assertThrows(BoostException.class,()->trade.simulatedPay(buyer,id));assertEquals("WAIT_PAY",str(trade.detail(id,buyer),"status"));}
 @Test void unverifiedWorkersCannotPublishOrAccept(){assertThrows(BoostException.class,()->market.publish(outsider,listingBody("SERVICE"),null));}
 @Test void messagesPersistAndOnlyReceiverMarksThemRead(){long id=messages.open(buyer,map("peerId",worker));messages.send(buyer,id,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","今晚八点可以开始吗？"));messages.read(buyer,id);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND is_read=FALSE",id));messages.read(worker,id);assertEquals(0,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND is_read=FALSE",id));}
 @Test void paymentCallbackChecksAmountAndIsIdempotent(){long id=order();Map<String,Object>o=trade.detail(id,buyer);assertThrows(BoostException.class,()->trade.paymentConfirmed(str(o,"orderNo"),1,"WX_TX_1"));trade.paymentConfirmed(str(o,"orderNo"),12000,"WX_TX_1");trade.paymentConfirmed(str(o,"orderNo"),12000,"WX_TX_1");assertEquals("WAIT_START",str(trade.detail(id,buyer),"status"));}
}
