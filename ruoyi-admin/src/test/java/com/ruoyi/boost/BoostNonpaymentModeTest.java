package com.ruoyi.boost;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

/** Isolated H2 fixtures only. No actual merchant requests or shared-money mutations. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostHardeningTest.Config.class)
@TestPropertySource(properties="boost.payments-enabled=false")
@Transactional
class BoostNonpaymentModeTest {
 @Autowired BoostDb db; @Autowired BoostAuth auth; @Autowired BoostTrade trade;
 @Autowired BoostMarket market; @Autowired BoostPayments payments;
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 long buyer,worker,listing;
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(db,"mediaDirectory",directory.toString());
  ReflectionTestUtils.setField(auth,"live",false);ReflectionTestUtils.setField(auth,"demoCoupons",false);
  // Disabled mode must also defeat a mistakenly enabled simulation switch.
  ReflectionTestUtils.setField(trade,"simulated",true);
  buyer=register();worker=register();db.run("UPDATE boost_member SET certification='APPROVED',openid='isolated-fixture' WHERE id=?",worker);
  listing=market.publish(worker,map("requestKey",UUID.randomUUID().toString(),"kind","SERVICE","title","关闭支付隔离验证服务","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀","targetRank","王者","durationHours",24,"priceCents",12000,"description","隔离测试服务不执行真实交易","images",Collections.emptyList()),null);
  db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",listing);
 }
 long register(){return num((Map<String,Object>)auth.register(map("username","np_"+UUID.randomUUID().toString().replace("-","").substring(0,20),"password","Nonpayment2026!","nickname","关闭支付验收")).get("user"),"id");}
 long order(){return num(trade.create(buyer,map("listingId",listing,"requestKey",UUID.randomUUID().toString()),false),"id");}
 void blocked(Executable action){assertEquals(503,assertThrows(BoostException.class,action).code);}
 @Test void disabledSimulationCannotCreatePaidState(){
  long id=order();blocked(()->trade.simulatedPay(buyer,id));
  Map<String,Object> o=trade.detail(id,buyer);assertEquals("WAIT_PAY",str(o,"status"));assertNull(o.get("paidAt"));assertNull(o.get("paymentMode"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));
 }
 @Test void disabledWithdrawalCannotFreezeBalance(){
  db.run("UPDATE boost_member SET balance_cents=1000 WHERE id=?",worker);
  blocked(()->trade.withdraw(worker,map("requestKey",UUID.randomUUID().toString(),"amountCents",1000,"accountName","隔离验收","accountNumber","isolated-account")));
  assertEquals(1000,db.count("SELECT balance_cents FROM boost_member WHERE id=?",worker));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));
 }
 @Test void disabledChannelFunctionsRejectBeforeDataOrProviderUse(){
  long id=order();db.run("UPDATE boost_member SET openid='isolated-buyer' WHERE id=?",buyer);
  blocked(()->payments.prepay(buyer,id));blocked(()->payments.recordPaymentIntent(buyer,id));blocked(()->payments.requestPrepay(buyer,id));
  blocked(()->payments.paymentNotify(map()));blocked(()->trade.paymentConfirmed("none",1,"none"));
  blocked(()->payments.requestRefund(map(),"隔离"));blocked(()->payments.refundNotify(map()));blocked(()->payments.readyForRefund());
  blocked(()->payments.readyForTransfer(map()));blocked(()->payments.transfer(map()));blocked(()->payments.transferNotify(map()));
  blocked(()->payments.retryRefund(1,id,"隔离复核"));blocked(()->payments.refreshOrder(1,id,"隔离复核"));blocked(()->payments.confirmation(worker,1));
  assertNull(trade.detail(id,buyer).get("paymentMode"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_provider_event"));
 }
 @Test void disabledPaidMutationsDoNotWrite(){
  long id=order();db.run("UPDATE boost_order SET status='IN_PROGRESS' WHERE id=?",id);
  blocked(()->trade.progress(worker,id,map("percent",10,"note","隔离进度","images",Collections.emptyList())));
  blocked(()->trade.aftersale(buyer,id,map("reason","隔离验收","description","关闭模式不能提交","images",Collections.emptyList())));
  blocked(()->trade.review(buyer,id,map("rating",5,"comment","隔离验收")));
  db.run("UPDATE boost_order SET status='REVIEW' WHERE id=?",id);blocked(()->trade.action(buyer,id,"confirm"));
  db.run("UPDATE boost_order SET status='WAIT_START' WHERE id=?",id);blocked(()->trade.action(worker,id,"start"));
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_progress"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_aftersale"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_review"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));
 }
 @Test void unpaidCancelAndExpiryStillCompleteLocally(){
  long id=order();payments.closeUnpaid(buyer,id);assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));
  long expired=order();db.run("UPDATE boost_order SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),expired);
  payments.reconcile();assertEquals("CANCELLED",str(trade.detail(expired,buyer),"status"));
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE payment_mode IS NOT NULL"));
 }
}
