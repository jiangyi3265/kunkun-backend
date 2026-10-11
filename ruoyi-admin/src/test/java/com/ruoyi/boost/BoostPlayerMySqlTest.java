package com.ruoyi.boost;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Repeat the actual player business assertions on isolated InnoDB, with provider/payment substitutes. */
@EnabledIfEnvironmentVariable(named="BOOST_PLAYER_TEST_DB_URL",matches=".+")
@ContextConfiguration(classes=BoostPlayerMySqlTest.MySqlConfig.class,inheritLocations=false)
class BoostPlayerMySqlTest extends BoostPlayerTradeTest {
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void smsAttemptsCommitOnFailureAndCodeCanBeConsumedOnlyOnce()throws Exception{
  Map<String,Object> c=phone.send(seller,map("phone",String.format(Locale.ROOT,"135%08d",seller)),"MEMBER","concurrent-phone-"+seller);String id=str(c,"challengeId"),code=sms.codes.get(id),wrong="000000".equals(code)?"111111":"000000";
  assertThrows(BoostException.class,()->phone.verify(seller,map("challengeId",id,"code",wrong),"MEMBER"));assertEquals(1,db.count("SELECT attempts FROM boost_phone_challenge WHERE id=?",id));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{List<Future<Boolean>> calls=new ArrayList<>();for(int i=0;i<2;i++)calls.add(pool.submit(()->{start.await();try{phone.verify(seller,map("challengeId",id,"code",code),"MEMBER");return true;}catch(BoostException e){return false;}}));start.countDown();int ok=0;for(var call:calls)if(call.get(15,TimeUnit.SECONDS))ok++;assertEquals(1,ok);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_member_phone WHERE member_id=?",seller));assertEquals("USED",str(db.one("SELECT status FROM boost_phone_challenge WHERE id=?",id),"status"));}finally{pool.shutdownNow();}
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void concurrentShopSubmissionsSaveExactlyOneImmutableVersion()throws Exception{
  bindPhone(seller);Map<String,Object>b=map("shopName","唯一提交店铺","shopIntro","并发提交只保存一份材料","images",List.of(shopImage));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{List<Future<Boolean>> calls=new ArrayList<>();for(int i=0;i<2;i++)calls.add(pool.submit(()->{start.await();try{player.applyShop(seller,b);return true;}catch(BoostException e){return false;}}));start.countDown();int ok=0;for(var call:calls)if(call.get(15,TimeUnit.SECONDS))ok++;assertEquals(1,ok);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_shop_revision WHERE member_id=?",seller));assertEquals(1,num(player.profile(seller),"shopRevision"));}finally{pool.shutdownNow();}
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void failedSmsIsNeverVerifiableAndStillThrottled(){
  sms.fail=true;Map<String,Object>b=map("phone",String.format(Locale.ROOT,"134%08d",seller));assertThrows(BoostException.class,()->phone.send(seller,b,"MEMBER","sms-failure-"+seller));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_phone_challenge WHERE member_id=? AND status='FAILED'",seller));sms.fail=false;assertThrows(BoostException.class,()->phone.send(seller,b,"MEMBER","sms-failure-"+seller));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member_phone WHERE member_id=?",seller));
 }
 private TransactionTemplate rr(){TransactionTemplate template=new TransactionTemplate(tx);template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return template;}
 private static void await(CountDownLatch gate){try{assertTrue(gate.await(12,TimeUnit.SECONDS),"Controlled race timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void suspensionCommittedAfterCancellationSnapshotCannotRepublishItem()throws Exception{
  long id=active(),order=num(trade.create(buyer,checkout(id),false),"id");
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),snapshot=new CountDownLatch(1),release=new CountDownLatch(1);
  try{
   Future<?> block=pool.submit(()->rr().execute(s->{db.one("SELECT id FROM boost_member WHERE id=? FOR UPDATE",seller);db.run("UPDATE boost_member SET status='BLOCKED' WHERE id=?",seller);locked.countDown();await(release);return null;}));await(locked);
   Future<?> cancel=pool.submit(()->rr().execute(s->{assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_member WHERE id=?",seller),"status"));snapshot.countDown();payments.closeUnpaid(buyer,order);return null;}));await(snapshot);assertThrows(TimeoutException.class,()->cancel.get(300,TimeUnit.MILLISECONDS));release.countDown();block.get(15,TimeUnit.SECONDS);cancel.get(15,TimeUnit.SECONDS);
   assertEquals("CANCELLED",str(trade.detail(order,buyer),"status"));assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",id),"status"));db.run("UPDATE boost_member SET status='ACTIVE' WHERE id=?",seller);assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",id),"status"));
  }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void sameDeliveryRetryAfterOldSnapshotReturnsPersistedResult()throws Exception{
  long id=active(),order=num(trade.create(buyer,checkout(id),false),"id");trade.simulatedPay(buyer,order);
  Map<String,Object> payload=map("requestKey",UUID.randomUUID().toString(),"note","两件护甲已经游戏内交付","images",List.of(deliveryImage));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch written=new CountDownLatch(1),snapshot=new CountDownLatch(1),release=new CountDownLatch(1);
  try{
   Future<?> first=pool.submit(()->rr().execute(s->{trade.delivery(seller,order,payload);written.countDown();await(release);return null;}));await(written);
   Future<?> retry=pool.submit(()->rr().execute(s->{assertEquals(0,db.count("SELECT COUNT(*) FROM boost_item_delivery WHERE order_id=?",order));snapshot.countDown();trade.delivery(seller,order,payload);return null;}));await(snapshot);assertThrows(TimeoutException.class,()->retry.get(300,TimeUnit.MILLISECONDS));release.countDown();first.get(15,TimeUnit.SECONDS);assertDoesNotThrow(()->retry.get(15,TimeUnit.SECONDS));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_item_delivery WHERE order_id=?",order));assertEquals("WAIT_CONFIRM",str(trade.detail(order,buyer),"status"));
   Map<String,Object> changed=new LinkedHashMap<>(payload);changed.put("note","篡改已提交的交付内容");assertThrows(BoostException.class,()->trade.delivery(seller,order,changed));
  }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
 }
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) void sameAftersaleRetryAfterOldSnapshotDoesNotCreateAnotherCase()throws Exception{
  long id=active(),order=num(trade.create(buyer,checkout(id),false),"id");trade.simulatedPay(buyer,order);
  Map<String,Object> payload=map("reason","道具规格不符","description","实际道具与商品约定规格不一致","images",List.of(asset(buyer,"AFTERSALE")));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch written=new CountDownLatch(1),snapshot=new CountDownLatch(1),release=new CountDownLatch(1);
  try{
   Future<?> first=pool.submit(()->rr().execute(s->{trade.aftersale(buyer,order,payload);written.countDown();await(release);return null;}));await(written);
   Future<?> retry=pool.submit(()->rr().execute(s->{assertEquals(0,db.count("SELECT COUNT(*) FROM boost_aftersale WHERE order_id=?",order));snapshot.countDown();trade.aftersale(buyer,order,payload);return null;}));await(snapshot);assertThrows(TimeoutException.class,()->retry.get(300,TimeUnit.MILLISECONDS));release.countDown();first.get(15,TimeUnit.SECONDS);assertDoesNotThrow(()->retry.get(15,TimeUnit.SECONDS));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_aftersale WHERE order_id=?",order));assertEquals("AFTERSALE",str(trade.detail(order,buyer),"status"));assertEquals("WAIT_DELIVERY",str(db.one("SELECT previous_status FROM boost_aftersale WHERE order_id=?",order),"previousStatus"));
  }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
 }
 @Configuration @EnableTransactionManagement static class MySqlConfig extends BoostPlayerTradeTest.Config {
  @Override @Bean DataSource dataSource(){
   String url=System.getenv("BOOST_PLAYER_TEST_DB_URL");
   if(url==null||!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/boost_verify_[0-9]+\\?.*"))throw new IllegalArgumentException("Only an isolated local player test database is allowed");
   DataSource source=new DriverManagerDataSource(url,System.getenv("BOOST_PLAYER_TEST_DB_USER"),System.getenv("BOOST_PLAYER_TEST_DB_PASSWORD"));
   new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(source);
   BoostMigrations.migrate(new BoostDb(new JdbcTemplate(source)));return source;
  }
 }
}
