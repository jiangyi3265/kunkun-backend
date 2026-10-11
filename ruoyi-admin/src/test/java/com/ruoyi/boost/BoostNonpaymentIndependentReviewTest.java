package com.ruoyi.boost;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

/** Independent review: real isolated H2 writes/transactions; merchant access is a fail-fast sentinel.
 * ProductionGuard metadata is explicitly simulated, not a real production startup certification. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostNonpaymentIndependentReviewTest.Config.class)
@TestPropertySource(properties={"boost.payments-enabled=false","boost.simulated-payment=false"})
@Transactional
class BoostNonpaymentIndependentReviewTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:nonpayment_independent_review;MODE=MySQL;DB_CLOSE_DELAY=-1");new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(d);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(d)));return d;}
  @Bean BoostDb db(DataSource d){return new BoostDb(new JdbcTemplate(d));}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
  @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
  @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
  @Bean SentinelGateway gateway(){return new SentinelGateway();}
  @Bean BoostPayments payments(BoostDb d,BoostWechatGateway w,BoostTrade t){return new BoostPayments(d,w,t);}
  @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
  @Bean BoostCampaign campaign(BoostDb d){return new BoostCampaign(d);}
 }
 static class SentinelGateway extends BoostWechatGateway {
  int calls;
  private AssertionError forbidden(){calls++;return new AssertionError("Merchant channel must not be touched in disabled mode");}
  @Override public void ensureConfigured(){throw forbidden();}
  @Override public Map<String,Object> call(String path,HttpMethod method,Map<String,Object> payload){throw forbidden();}
  @Override public Map<String,Object> callback(HttpHeaders headers,String raw){throw forbidden();}
  @Override public Object transferReports(){throw forbidden();}
 }
 @Autowired BoostDb db; @Autowired BoostAuth auth; @Autowired BoostMarket market;
 @Autowired BoostTrade trade; @Autowired BoostPayments payments; @Autowired BoostAdmin admin;
 @Autowired SentinelGateway gateway;
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 long buyer,worker,listing;
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(db,"mediaDirectory",directory.toString());
  ReflectionTestUtils.setField(auth,"live",false);ReflectionTestUtils.setField(auth,"demoCoupons",false);
  gateway.calls=0;buyer=register();worker=register();
  db.run("UPDATE boost_member SET certification='APPROVED',openid='review-worker' WHERE id=?",worker);
  listing=market.publish(worker,map("requestKey",UUID.randomUUID().toString(),"kind","SERVICE","title","独立非支付边界复核服务","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀","targetRank","王者","durationHours",24,"priceCents",12000,"description","独立隔离数据库不执行真实资金动作","images",Collections.emptyList()),null);
  db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",listing);
 }
 long register(){return num((Map<String,Object>)auth.register(map("username","npr_"+UUID.randomUUID().toString().replace("-","").substring(0,20),"password","Independent2026!","nickname","独立复核")).get("user"),"id");}
 long order(){return num(trade.create(buyer,map("listingId",listing,"requestKey",UUID.randomUUID().toString()),false),"id");}
 long withdrawal(String state){return db.insert("INSERT INTO boost_withdrawal(member_id,request_key,amount_cents,status,account_name,account_number) VALUES (?,?,1000,?,'隔离夹具','fixture')",worker,UUID.randomUUID().toString(),state);}
 void blocked(Executable action){assertEquals(503,assertThrows(BoostException.class,action).code);}
 Map<String,Object> snapshot(){Map<String,Object> result=new LinkedHashMap<>();for(String table:new String[]{"boost_member","boost_order","boost_listing","boost_aftersale","boost_withdrawal","boost_coupon","boost_ledger","boost_provider_event","boost_audit","boost_notification"})result.put(table,db.list("SELECT * FROM "+table+" ORDER BY id"));return result;}

 @Test void adminApproveAndRejectCannotChangeMoneyOrAuditEvenWithHistoricalFixtures(){
  long id=order();db.run("UPDATE boost_order SET status='AFTERSALE',payment_mode='WECHAT' WHERE id=?",id);
  long aftersale=db.insert("INSERT INTO boost_aftersale(order_id,member_id,reason,description,amount_cents,previous_status) VALUES (?,?,'隔离','历史售后夹具',12000,'WAIT_START')",id,buyer);
  long withdrawal=withdrawal("PENDING");Map<String,Object> before=snapshot();
  for(String action:Arrays.asList("approve","reject")){
   blocked(()->admin.aftersale(1,aftersale,map("action",action,"note","独立复核")));
   blocked(()->admin.withdrawal(1,withdrawal,map("action",action,"note","独立复核")));
  }
  assertEquals(before,snapshot());assertEquals(0,gateway.calls);
 }

 @Test void callbackEndpointsRejectBeforeSignatureParsingOrMerchantConfiguration(){
  BoostWechatController controller=new BoostWechatController(auth,gateway,payments);Map<String,Object> before=snapshot();
  blocked(()->controller.payment(new HttpHeaders(),"not-json"));
  blocked(()->controller.refund(new HttpHeaders(),"not-json"));
  blocked(()->controller.transfer(new HttpHeaders(),"not-json"));
  assertEquals(0,gateway.calls);assertEquals(before,snapshot());
 }

 @Test void schedulerExpiresLocalUnpaidButDoesNotTouchAnyRemoteObligation(){
  long local=order(),remote=order(),refund=order(),exception=order();
  db.run("UPDATE boost_order SET expires_at=? WHERE id IN (?,?)",java.sql.Timestamp.from(Instant.now().minusSeconds(60)),local,remote);
  db.run("UPDATE boost_order SET payment_mode='WECHAT' WHERE id=?",remote);
  for(long id:new long[]{refund,exception}){
   db.run("UPDATE boost_order SET status=?,payment_mode='WECHAT' WHERE id=?",id==refund?"REFUNDING":"REFUND_EXCEPTION",id);
   db.insert("INSERT INTO boost_aftersale(order_id,member_id,reason,description,amount_cents,previous_status) VALUES (?,?,'隔离','退款夹具',12000,'WAIT_START')",id,buyer);
  }
  withdrawal("PROCESSING");withdrawal("WAIT_USER_CONFIRM");
  List<Map<String,Object>> beforeOrders=db.list("SELECT * FROM boost_order WHERE id<>? ORDER BY id",local);
  List<Map<String,Object>> beforeWithdrawals=db.list("SELECT * FROM boost_withdrawal ORDER BY id");
  payments.reconcile();
  assertEquals("CANCELLED",str(db.one("SELECT * FROM boost_order WHERE id=?",local),"status"));
  assertEquals(beforeOrders,db.list("SELECT * FROM boost_order WHERE id<>? ORDER BY id",local));
  assertEquals(beforeWithdrawals,db.list("SELECT * FROM boost_withdrawal ORDER BY id"));
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));assertEquals(0,gateway.calls);
  assertEquals(1,db.count("SELECT COUNT(*) FROM boost_provider_event WHERE order_id=? AND event_type='PAYMENT_CLOSED'",local));
  payments.reconcile();assertEquals(1,db.count("SELECT COUNT(*) FROM boost_provider_event WHERE order_id=?",local));
 }

 @Test void directCloseAndReconcileCannotForgetAnExistingWechatPaymentIntent(){
  long id=order();db.run("UPDATE boost_order SET payment_mode='WECHAT' WHERE id=?",id);
  for(boolean expired:new boolean[]{false,true}){
   if(expired)db.run("UPDATE boost_order SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(60)),id);
   Map<String,Object> before=snapshot();blocked(()->payments.closeUnpaid(buyer,id));blocked(()->payments.reconcileUnpaid(id));
   assertThrows(BoostException.class,()->trade.action(buyer,id,"cancel"));
   assertEquals(before,snapshot());assertEquals(0,gateway.calls);
  }
 }

 @Test void productionCannotDisableWhileAnOrderWithdrawalOrBalanceObligationExists(){
  long id=order();assertDoesNotThrow(()->BoostProductionGuard.verifyNoFinancialObligations(db));
  for(String status:Arrays.asList("WAIT_DELIVERY","WAIT_CONFIRM","WAIT_START","IN_PROGRESS","REVIEW","AFTERSALE","REFUNDING","REFUND_EXCEPTION")){
   db.run("UPDATE boost_order SET status=? WHERE id=?",status,id);
   assertThrows(IllegalStateException.class,()->BoostProductionGuard.verifyNoFinancialObligations(db),status);
  }
  db.run("UPDATE boost_order SET status='WAIT_PAY',payment_mode='WECHAT' WHERE id=?",id);
  assertThrows(IllegalStateException.class,()->BoostProductionGuard.verifyNoFinancialObligations(db));
  db.run("UPDATE boost_order SET status='CANCELLED',payment_mode=NULL WHERE id=?",id);
  for(String status:Arrays.asList("PENDING","PROCESSING","WAIT_USER_CONFIRM")){
   long w=withdrawal(status);assertThrows(IllegalStateException.class,()->BoostProductionGuard.verifyNoFinancialObligations(db),status);db.run("UPDATE boost_withdrawal SET status='REJECTED' WHERE id=?",w);
  }
  for(long balance:new long[]{1,-1}){
   db.run("UPDATE boost_member SET balance_cents=? WHERE id=?",balance,worker);
   assertThrows(IllegalStateException.class,()->BoostProductionGuard.verifyNoFinancialObligations(db));
  }
  db.run("UPDATE boost_member SET balance_cents=0 WHERE id=?",worker);
  for(String status:Arrays.asList("CANCELLED","REFUNDED","COMPLETED")){
   db.run("UPDATE boost_order SET status=? WHERE id=?",status,id);assertDoesNotThrow(()->BoostProductionGuard.verifyNoFinancialObligations(db),status);
  }
 }

 /** MySQL metadata only is simulated here; migrations/obligation SQL still reads actual isolated H2. */
 static class GuardDb extends BoostDb {
  String database="boost_review_prod",grant="GRANT SELECT, INSERT, UPDATE, DELETE ON `boost_review_prod`.* TO `review`@`localhost`";
  GuardDb(BoostDb real){super(real.template());}
  @Override public long count(String sql,Object... args){if(sql.contains("TIMESTAMPDIFF"))return 0;return super.count(sql,args);}
  @Override public List<Map<String,Object>> list(String sql,Object... args){
   if(sql.equals("SELECT DATABASE() AS name"))return Collections.singletonList(map("name",database));
   if(sql.equals("SHOW GRANTS FOR CURRENT_USER"))return Collections.singletonList(map("grant",grant));
   if(sql.startsWith("SELECT password FROM sys_user"))return Collections.emptyList();
   return super.list(sql,args);
  }
 }
 MockEnvironment production(){
  MockEnvironment env=new MockEnvironment();env.setActiveProfiles("prod");
  for(Map.Entry<String,Object> e:map("boost.live","true","boost.payments-enabled","false","boost.simulated-payment","false",
   "spring.datasource.druid.master.username","review_app","spring.datasource.druid.master.url","jdbc:mysql://127.0.0.1/boost_review_prod?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
   "spring.datasource.druid.master.password","fixture-db-password-2026","token.secret",String.join("",Collections.nCopies(64,"x")),"boost.media-signing-key",String.join("",Collections.nCopies(48,"y")),
   "spring.redis.password","fixture-redis-password","boost.wechat.notify-base","https://api.example.com","boost.wechat.app-id","wx0123456789abcdef","boost.wechat.app-secret","isolated-fixture-secret",
   "boost.policy.contact","privacy@example.com","boost.policy.effective-date","2026-10-04","boost.media-dir",directory.resolve("private").toString(),"ruoyi.profile",directory.resolve("public").toString(),"boost.web-origins","https://admin.example.com").entrySet())env.setProperty(e.getKey(),e.getValue().toString());
  return env;
 }
 BoostPolicy policy(){BoostPolicy p=new BoostPolicy();for(String field:new String[]{"operator","contact","retention","version","effectiveDate"})ReflectionTestUtils.setField(p,field,"independent-fixture");return p;}
 @Test void disablingMerchantSetupDoesNotDisableOtherProductionControls()throws Exception{
  GuardDb guardDb=new GuardDb(db);new BoostProductionGuard(production(),guardDb,gateway,policy()).validate();assertEquals(0,gateway.calls);
  Map<String,Object> unsafe=map("boost.live","false","boost.demo","true","boost.simulated-payment","true","boost.initialize-schema","true","swagger.enabled","true","spring.datasource.druid.statViewServlet.enabled","true",
   "spring.datasource.druid.master.username","root","spring.datasource.druid.master.url","jdbc:mysql://localhost/boost_review_prod","spring.datasource.druid.master.password","short","token.secret","short","boost.media-signing-key","short","spring.redis.password","",
   "boost.wechat.notify-base","http://api.example.com","boost.policy.contact","invalid","boost.policy.effective-date","not-a-date","boost.wechat.app-id","not-an-appid","boost.wechat.app-secret","","boost.web-origins","https://*.example.com");
  for(Map.Entry<String,Object> entry:unsafe.entrySet()){
   MockEnvironment bad=production().withProperty(entry.getKey(),entry.getValue().toString());
   assertThrows(Exception.class,()->new BoostProductionGuard(bad,guardDb,gateway,policy()).validate(),entry.getKey());
  }
  guardDb.database="ha";assertThrows(IllegalStateException.class,()->new BoostProductionGuard(production(),guardDb,gateway,policy()).validate());guardDb.database="boost_review_prod";
  guardDb.grant="GRANT ALL PRIVILEGES ON *.* TO `review`@`localhost`";assertThrows(IllegalStateException.class,()->new BoostProductionGuard(production(),guardDb,gateway,policy()).validate());
  assertEquals(0,gateway.calls);
 }
 @Test void enablingPaymentsStillRequiresMerchantConfiguration(){
  MockEnvironment enabled=production().withProperty("boost.payments-enabled","true");
  assertThrows(AssertionError.class,()->new BoostProductionGuard(enabled,new GuardDb(db),gateway,policy()).validate());assertEquals(1,gateway.calls);
 }
 @Test void productionStartupChecksMigrationAndActualOutstandingBalance(){
  GuardDb guardDb=new GuardDb(db);db.run("UPDATE boost_member SET balance_cents=1 WHERE id=?",worker);
  IllegalStateException balance=assertThrows(IllegalStateException.class,()->new BoostProductionGuard(production(),guardDb,gateway,policy()).validate());assertTrue(balance.getMessage().contains("financial obligations"));
  db.run("UPDATE boost_member SET balance_cents=0 WHERE id=?",worker);
  db.run("UPDATE boost_config SET config_value='incorrect-fixture-checksum' WHERE config_key=?","schema:"+BoostMigrations.VERSION);
  IllegalStateException migration=assertThrows(IllegalStateException.class,()->new BoostProductionGuard(production(),guardDb,gateway,policy()).validate());assertTrue(migration.getMessage().contains("migration"));assertEquals(0,gateway.calls);
 }
}
