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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=BoostHardeningTest.Config.class) @Transactional
class BoostHardeningTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:hardening;MODE=MySQL;DB_CLOSE_DELAY=-1");new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(d);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(d)));return d;}
  @Bean BoostDb db(DataSource d){return new BoostDb(new JdbcTemplate(d));}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
  @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
  @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
  @Bean BoostMessages messages(BoostDb d){return new BoostMessages(d);}
  @Bean FakeGateway gateway(){return new FakeGateway();}
  @Bean BoostPayments payments(BoostDb d,BoostWechatGateway g,BoostTrade t){return new BoostPayments(d,g,t);}
  @Bean BoostCampaign campaign(BoostDb d){return new BoostCampaign(d);}
  @Bean BoostMediaFiles media(BoostAuth a,BoostDb d){return new BoostMediaFiles(a,d,new BoostAccess(){@Override public boolean can(String p){return false;}});}
 }
 static class FakeGateway extends BoostWechatGateway {
  BoostException error;String state="CLOSED";
  @Override public void ensureConfigured(){}
  @Override public Map<String,Object> call(String path,org.springframework.http.HttpMethod method,Map<String,Object> payload){if(error!=null)throw error;return map("appid",appId,"mchid",merchantId,"out_trade_no",path.substring(path.indexOf("out-trade-no/")+13).split("\\?")[0],"trade_state",state);}
 }
 @Autowired BoostDb db;@Autowired BoostAuth auth;@Autowired BoostMarket market;@Autowired BoostTrade trade;@Autowired BoostMessages messages;@Autowired BoostPayments payments;@Autowired BoostCampaign campaign;@Autowired BoostMediaFiles media;@Autowired FakeGateway gateway;
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 long buyer,worker,other,listing;String token,workerToken;
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(db,"mediaDirectory",directory.toString());ReflectionTestUtils.setField(media,"directory",directory.toString());ReflectionTestUtils.setField(media,"quota",104857600L);
  ReflectionTestUtils.setField(auth,"demoCoupons",false);ReflectionTestUtils.setField(auth,"live",false);ReflectionTestUtils.setField(trade,"simulated",true);ReflectionTestUtils.setField(campaign,"dailyBudget",0L);ReflectionTestUtils.setField(campaign,"totalBudget",0L);
  Map<String,Object> a=register();buyer=num((Map<String,Object>)a.get("user"),"id");token=str(a,"token");a=register();worker=num((Map<String,Object>)a.get("user"),"id");workerToken=str(a,"token");other=num((Map<String,Object>)register().get("user"),"id");
  db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);listing=market.publish(worker,listingBody(),null);db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",listing);
  gateway.appId="test-app";gateway.merchantId="test-merchant";gateway.error=null;gateway.state="CLOSED";
 }
 Map<String,Object> register(){return auth.register(map("username","h_"+UUID.randomUUID().toString().replace("-","").substring(0,20),"password","Hardening2026!","nickname","回归测试"));}
 Map<String,Object> listingBody(){return map("requestKey",UUID.randomUUID().toString(),"kind","SERVICE","title","王者荣耀服务回归测试","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀","targetRank","王者","durationHours",24,"priceCents",12000,"description","确认需求后按约定交付成果","images",Collections.emptyList());}
 long order(){return num(trade.create(buyer,map("listingId",listing,"requestKey",UUID.randomUUID().toString()),false),"id");}
 MockHttpServletRequest request(String token){MockHttpServletRequest r=new MockHttpServletRequest();if(token!=null)r.addHeader("X-Boost-Token",token);return r;}
 String upload(String token,String purpose)throws Exception{java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,1),"png",b);return str((Map<String,Object>)media.upload(new MockMultipartFile("file","p.png","image/png",b.toByteArray()),purpose,request(token)),"url");}
 Map<String,Object> urls(String path,String token){return (Map<String,Object>)((Map<String,Object>)media.authorize(map("paths",Collections.singletonList(path)),request(token))).get("urls");}
 @Test void publishingAndMessagesRetryOnceAndRejectChangedPayload(){Map<String,Object>b=listingBody();long id=market.publish(worker,b,null);assertEquals(id,market.publish(worker,b,null));b.put("priceCents",15000);assertEquals(409,assertThrows(BoostException.class,()->market.publish(worker,b,null)).code);long c=messages.open(buyer,map("peerId",worker));Map<String,Object>m=map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","重复网络请求");messages.send(buyer,c,m);messages.send(buyer,c,m);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",c));m.put("content","不同内容");assertThrows(BoostException.class,()->messages.send(buyer,c,m));}
 @Test void favoriteAndFollowAreDesiredStateOperations(){assertTrue(market.favorite(buyer,listing,true));assertTrue(market.favorite(buyer,listing,true));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_favorite WHERE member_id=?",buyer));assertFalse(market.favorite(buyer,listing,false));assertFalse(market.favorite(buyer,listing,false));assertTrue(market.follow(buyer,worker,true));assertTrue(market.follow(buyer,worker,true));assertFalse(market.follow(buyer,worker,false));assertFalse(market.follow(buyer,worker,false));}
 @Test void privateMediaRequiresParticipationAndSignedGrant()throws Exception{String p=upload(workerToken,"CHAT"),n=p.substring(13);assertTrue(urls(p,null).isEmpty());assertTrue(urls(p,token).isEmpty());assertEquals(403,assertThrows(BoostException.class,()->media.media(n,0,"","")).code);long c=messages.open(worker,map("peerId",buyer));messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","IMAGE","content",p));String signed=(String)urls(p,token).get(p);assertNotNull(signed);Map<String,String> q=new HashMap<>();for(String part:signed.split("\\?")[1].split("&")){String[] pair=part.split("=",2);q.put(pair[0],pair[1]);}assertEquals(200,media.media(n,Long.parseLong(q.get("expires")),q.get("actor"),q.get("signature")).getStatusCode().value());assertEquals("no-store",media.media(n,Long.parseLong(q.get("expires")),q.get("actor"),q.get("signature")).getHeaders().getFirst("Cache-Control"));assertThrows(BoostException.class,()->media.media(n,Long.parseLong(q.get("expires")),q.get("actor"),"tampered"));assertThrows(BoostException.class,()->media.media(n,1,q.get("actor"),q.get("signature")));}
 @Test void uploadOwnershipAndPurposeCannotBeSubstituted()throws Exception{String p=upload(workerToken,"CERTIFICATION");assertThrows(BoostException.class,()->db.uploadedImage(p,buyer,"CERTIFICATION"));assertThrows(BoostException.class,()->db.uploadedImage(p,worker,"LISTING"));assertDoesNotThrow(()->db.uploadedImage(p,worker,"CERTIFICATION"));}
 @Test void publicListingImageIsPublicOnlyWhileActive()throws Exception{String p=upload(workerToken,"LISTING");Map<String,Object>b=listingBody();b.put("images",Collections.singletonList(p));long id=market.publish(worker,b,null);assertTrue(urls(p,null).isEmpty());db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",id);assertTrue(urls(p,null).containsKey(p));db.run("UPDATE boost_listing SET status='OFFLINE' WHERE id=?",id);assertTrue(urls(p,null).isEmpty());}
 @Test void legacyAvatarWorksWhilePrivateReferencesAndInactiveOwnersRemainProtected()throws Exception{
  String p=upload(workerToken,"AVATAR"),n=p.substring(13);db.run("DELETE FROM boost_media WHERE name=?",n);
  assertEquals(403,assertThrows(BoostException.class,()->media.media(n,0,"","")).code);
  db.run("UPDATE boost_member SET avatar=? WHERE id=?",p,worker);
  assertEquals(200,media.media(n,0,"","").getStatusCode().value());assertTrue(urls(p,null).containsKey(p));
  db.run("UPDATE boost_member SET status='DISABLED' WHERE id=?",worker);assertTrue(urls(p,null).isEmpty());
  db.run("UPDATE boost_member SET status='ACTIVE',proof=? WHERE id=?",BoostInput.images(map("images",Collections.singletonList(p))),worker);
  assertEquals(403,assertThrows(BoostException.class,()->media.media(n,0,"","")).code);assertTrue(urls(p,null).isEmpty());assertTrue(urls(p,workerToken).containsKey(p));
 }
 @Test void quotaAndOrphanCollectionPreserveLinkedEvidence()throws Exception{String orphan=upload(workerToken,"CHAT"),linked=upload(workerToken,"CHAT");long c=messages.open(worker,map("peerId",buyer));messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","IMAGE","content",linked));db.run("UPDATE boost_media SET created_at=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(8*86400)));assertEquals(1,media.collectOrphans());assertFalse(java.nio.file.Files.exists(directory.resolve(orphan.substring(13))));assertTrue(java.nio.file.Files.exists(directory.resolve(linked.substring(13))));ReflectionTestUtils.setField(media,"quota",1L);assertEquals(429,assertThrows(BoostException.class,()->upload(workerToken,"CHAT")).code);}
 @Test void campaignRequiresBudgetAndLimitsIdentityAndAggregate(){campaign.claim(buyer,"unique-openid");assertEquals(0,db.count("SELECT COUNT(*) FROM boost_coupon"));ReflectionTestUtils.setField(campaign,"dailyBudget",1000L);ReflectionTestUtils.setField(campaign,"totalBudget",1000L);campaign.claim(buyer,"unique-openid");campaign.claim(worker,"unique-openid");campaign.claim(other,"another-openid");assertEquals(1,db.count("SELECT COUNT(*) FROM boost_campaign_claim"));assertEquals(1000,db.count("SELECT SUM(used_cents) FROM boost_campaign_budget"));}
 @Test void expiredRequestReleasesReservationAndCoupon(){Map<String,Object>b=listingBody();b.put("kind","REQUEST");long id=market.publish(buyer,b,null);db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",id);long o=num(trade.create(worker,map("listingId",id,"requestKey",UUID.randomUUID().toString()),true),"id");db.run("UPDATE boost_order SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),o);payments.reconcileUnpaid(o);assertEquals("CANCELLED",str(trade.detail(o,buyer),"status"));assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",id),"status"));}
 @Test void onlyVerifiedMissingRemoteOrderCanReleaseReservation(){long id=order();db.run("UPDATE boost_order SET payment_mode='WECHAT' WHERE id=?",id);gateway.error=new BoostProviderException(404,"ORDER_NOT_EXIST",false);assertThrows(BoostException.class,()->payments.closeUnpaid(buyer,id));assertEquals("WAIT_PAY",str(trade.detail(id,buyer),"status"));gateway.error=new BoostProviderException(404,"ORDER_NOT_EXIST",true);payments.closeUnpaid(buyer,id);payments.closeUnpaid(buyer,id);assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_provider_event WHERE order_id=?",id));}
 @Test void abnormalRefundDoesNotSettleAndClosedRefundRetriesWithNewNumber(){long id=order();trade.simulatedPay(buyer,id);trade.aftersale(buyer,id,map("reason","未开始服务","description","确认无法完成本单约定","images",Collections.emptyList()));db.run("UPDATE boost_order SET status='REFUNDING',payment_mode='WECHAT' WHERE id=?",id);String no=str(trade.detail(id,buyer),"orderNo");Map<String,Object>b=map("mchid",gateway.merchantId,"out_trade_no",no,"out_refund_no","RF"+no,"amount",map("total",12000,"refund",12000),"refund_status","ABNORMAL");payments.refundNotify(b);payments.refundNotify(b);assertEquals("REFUND_EXCEPTION",str(trade.detail(id,buyer),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_provider_event WHERE order_id=?",id));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE event_key=?","refund:"+id));assertThrows(BoostException.class,()->payments.retryRefund(1,id,"核对重试"));b.put("refund_status","CLOSED");payments.refundNotify(b);payments.retryRefund(1,id,"渠道已确认关闭");assertThrows(BoostException.class,()->payments.retryRefund(1,id,"重复重试"));assertThrows(BoostException.class,()->payments.refundNotify(b));b.put("out_refund_no","RF"+no+"A2");b.put("refund_status","SUCCESS");payments.refundNotify(b);payments.refundNotify(b);assertEquals("REFUNDED",str(trade.detail(id,buyer),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_ledger WHERE event_key=?","refund:"+id));}
 @Test void paginatedNoticesAndHistorySearchStayWithinMembership(){for(int i=0;i<45;i++)db.notify(buyer,"分页通知","历史记录 "+i,null);Map<String,Object> page=messages.notifications(buyer,Collections.singletonMap("page","2"));assertEquals(20,((List<?>)page.get("items")).size());assertEquals(true,page.get("hasMore"));long c=messages.open(buyer,map("peerId",worker));messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","查找很早的暗号"));messages.send(worker,c,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","普通最新消息"));assertEquals(1,num(messages.conversations(buyer,Collections.singletonMap("keyword","暗号")),"total"));assertEquals(0,num(messages.conversations(other,Collections.singletonMap("keyword","暗号")),"total"));}
 @Test void liveConsentAndMultibytePasswordAreValidated(){ReflectionTestUtils.setField(auth,"live",true);ReflectionTestUtils.setField(auth,"policyVersion","2026-10-02");assertThrows(BoostException.class,()->auth.wechatWithPolicy("wx-unaccepted-new-user",map()));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member WHERE openid='wx-unaccepted-new-user'"));auth.wechatWithPolicy("wx-accepted-new-user",map("acceptedPolicy","2026-10-02"));assertEquals("2026-10-02",str(db.one("SELECT accepted_policy FROM boost_member WHERE openid='wx-accepted-new-user'"),"acceptedPolicy"));assertThrows(BoostException.class,()->auth.register(map("username","multibyte","nickname","测试用户","password",String.join("",Collections.nCopies(30,"密")),"acceptedPolicy","2026-10-02")));}
}
