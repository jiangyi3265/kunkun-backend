package com.ruoyi.boost;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controller-equivalent authentication precedes a committed suspension, then delayed service writes.
 * Real Spring transactions/MySQL, isolated files and identities; no channel/financial operations. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostMemberWriteBoundaryTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL", matches=".+")
class BoostMemberWriteBoundaryTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean BoostDb db(DataSource d){return new BoostDb(new JdbcTemplate(d));}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
        @Bean BoostMessages messages(BoostDb d){return new BoostMessages(d);}
        @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
        @Bean BoostWechatGateway gateway(){return new BoostWechatGateway();}
        @Bean BoostPayments payments(BoostDb d,BoostWechatGateway g,BoostTrade t){return new BoostPayments(d,g,t);}
        @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
    }
    @Autowired BoostDb db;
    @Autowired BoostAuth auth;
    @Autowired BoostMarket market;
    @Autowired BoostMessages messages;
    @Autowired BoostTrade trade;
    @Autowired BoostAdmin admin;
    @org.junit.jupiter.api.io.TempDir Path media;
    long actor,peer,ownListing,peerListing,conversation;
    String token,peerToken,imagePath;
    static final String PASSWORD="MemberWrite2026!";
    @BeforeEach void setup()throws Exception{
        ReflectionTestUtils.setField(trade,"paymentsEnabled",false);ReflectionTestUtils.setField(trade,"simulated",false);
        ReflectionTestUtils.setField(db,"mediaDirectory",media.toString());
        Map<String,Object> registered=auth.register(map("username","qa_mw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password",PASSWORD,"nickname","迟到操作测试会员"));
        actor=num((Map<String,Object>)registered.get("user"),"id");token=str(registered,"token");
        Map<String,Object> registeredPeer=auth.register(map("username","qa_mw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password",PASSWORD,"nickname","正常对照会员"));
        peer=num((Map<String,Object>)registeredPeer.get("user"),"id");peerToken=str(registeredPeer,"token");
        db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",peer);
        ownListing=market.publish(actor,listing("REQUEST"),null);peerListing=market.publish(peer,listing("SERVICE"),null);
        for(long listing:new long[]{ownListing,peerListing})admin.moderate(1,"listings",listing,map("action","approve","note","隔离测试审核通过"));
        conversation=messages.open(actor,map("peerId",peer));
        messages.send(peer,conversation,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","停用前未读消息"));
        String name=UUID.randomUUID().toString().replace("-", "")+".png";
        assertTrue(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",media.resolve(name).toFile()));
        db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES (?,?,?,?)",name,actor,"CERTIFICATION",Files.size(media.resolve(name)));
        imagePath="/boost-media/"+name;
    }
    Map<String,Object> listing(String kind){return map("requestKey",UUID.randomUUID().toString(),"kind",kind,"title","隔离迟到写入测试发布","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀","targetRank","王者","durationHours",24,"priceCents",12000,"description","只验证停用后的迟到非支付操作是否拒绝","images",Collections.emptyList());}
    String digestState(){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String table:Arrays.asList("boost_member","boost_session","boost_listing","boost_media","boost_favorite","boost_history","boost_follow","boost_conversation","boost_message","boost_notification","boost_coupon","boost_order","boost_audit"))
                digest.update((table+db.list("SELECT * FROM "+table+" ORDER BY 1,2")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    void delayedAuthenticatedWrite(LongConsumer write){
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Boost-Token",token);
        long authenticated=auth.member(request);assertEquals(actor,authenticated,"Capture the normal authenticated controller identity before suspension");
        admin.moderate(1,"members",actor,map("action","block","note","真实提交停用后才继续服务写入"));
        assertEquals("BLOCKED",str(db.one("SELECT status FROM boost_member WHERE id=?",actor),"status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_session WHERE member_id=?",actor));
        String before=digestState();
        BoostException denied=assertThrows(BoostException.class,()->write.accept(authenticated),"A controller already holding the identity must not authorize a write after suspension commits");
        assertEquals(401,denied.code);
        assertEquals(before,digestState(),"Rejected delayed operation must not mutate business state");
    }
    @AfterEach void cleanup(){
        db.run("DELETE FROM boost_message WHERE conversation_id IN (SELECT id FROM boost_conversation WHERE member_a IN (?,?) OR member_b IN (?,?))",actor,peer,actor,peer);
        db.run("DELETE FROM boost_conversation WHERE member_a IN (?,?) OR member_b IN (?,?)",actor,peer,actor,peer);
        for(String table:Arrays.asList("boost_favorite","boost_history"))db.run("DELETE FROM "+table+" WHERE member_id IN (?,?) OR listing_id IN (?,?)",actor,peer,ownListing,peerListing);
        db.run("DELETE FROM boost_follow WHERE member_id IN (?,?) OR seller_id IN (?,?)",actor,peer,actor,peer);
        db.run("DELETE FROM boost_media WHERE owner_id IN (?,?)",actor,peer);
        db.run("DELETE FROM boost_audit WHERE (action LIKE 'members:%' AND target_id IN (?,?)) OR (action LIKE 'listings:%' AND target_id IN (?,?))",actor,peer,ownListing,peerListing);
        db.run("DELETE FROM boost_listing WHERE owner_id IN (?,?)",actor,peer);
        for(long id:new long[]{actor,peer})if(id>0){
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=? OR worker_id=?",id,id));
            assertEquals(0,db.count("SELECT balance_cents FROM boost_member WHERE id=?",id));
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id=?",id));
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE member_id=?",id));
            db.run("DELETE FROM boost_notification WHERE member_id=?",id);db.run("DELETE FROM boost_coupon WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);
        }
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member WHERE id IN (?,?)",actor,peer));
    }
    @Test void delayedProfileCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->auth.profile(id,map("nickname","禁止写入的新昵称","avatar","hero","notifyEnabled",true)));}
    @Test void delayedCertificationCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->auth.apply(id,map("skill","迟到认证不得进入审核","images",Collections.singletonList(imagePath))));}
    @Test void delayedPasswordChangeCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->auth.changePassword(id,map("oldPassword",PASSWORD,"newPassword","RejectedWrite2026!")));}
    @Test void delayedRequestPublishCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->market.publish(id,listing("REQUEST"),null));}
    @Test void delayedListingEditCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->market.publish(id,listing("REQUEST"),ownListing));}
    @Test void delayedAuthenticatedDetailCannotWriteHistoryAfterActorIsBlocked(){delayedAuthenticatedWrite(id->market.detail(peerListing,id));}
    @Test void delayedListingActionCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->market.listingAction(id,ownListing,"resubmit"));}
    @Test void delayedFavoriteCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->market.favorite(id,peerListing,true));}
    @Test void delayedFollowCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->market.follow(id,peer,true));}
    @Test void delayedConversationOpenMustRejectBlockedAuthenticatedActor(){delayedAuthenticatedWrite(id->messages.open(id,map("peerId",peer)));}
    @Test void delayedMessageSendCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->messages.send(id,conversation,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content","停用后禁止提交的消息")));}
    @Test void delayedMessageReadCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->messages.read(id,conversation));}
    @Test void delayedAllReadCannotWriteAfterAuthenticatedActorIsBlocked(){delayedAuthenticatedWrite(id->messages.readAll(id));}
    @Test void delayedNoticeReadCannotWriteAfterAuthenticatedActorIsBlocked(){long notice=db.count("SELECT MAX(id) FROM boost_notification WHERE member_id=?",actor);delayedAuthenticatedWrite(id->messages.readNotice(id,notice));}
    @Test void reciprocalAuthenticatedConversationOpenRetainsSortedMemberLocks()throws Exception{
        db.run("DELETE FROM boost_message WHERE conversation_id=?",conversation);db.run("DELETE FROM boost_conversation WHERE id=?",conversation);
        MockHttpServletRequest actorRequest=new MockHttpServletRequest(),peerRequest=new MockHttpServletRequest();
        actorRequest.addHeader("X-Boost-Token",token);peerRequest.addHeader("X-Boost-Token",peerToken);
        long actorId=auth.member(actorRequest),peerId=auth.member(peerRequest);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try{
            Future<Long> fromActor=pool.submit(()->{ready.countDown();assertTrue(start.await(12,TimeUnit.SECONDS));return messages.open(actorId,map("peerId",peerId));});
            Future<Long> fromPeer=pool.submit(()->{ready.countDown();assertTrue(start.await(12,TimeUnit.SECONDS));return messages.open(peerId,map("peerId",actorId));});
            assertTrue(ready.await(12,TimeUnit.SECONDS));start.countDown();
            long id=assertDoesNotThrow(()->fromActor.get(15,TimeUnit.SECONDS));assertEquals(id,(long)assertDoesNotThrow(()->fromPeer.get(15,TimeUnit.SECONDS)));
            assertEquals(1,db.count("SELECT COUNT(*) FROM boost_conversation WHERE member_a=? AND member_b=?",Math.min(actor,peer),Math.max(actor,peer)));
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
    }
    @Test void reciprocalAuthenticatedFollowRetainsSortedMemberLocks()throws Exception{
        MockHttpServletRequest actorRequest=new MockHttpServletRequest(),peerRequest=new MockHttpServletRequest();
        actorRequest.addHeader("X-Boost-Token",token);peerRequest.addHeader("X-Boost-Token",peerToken);
        long actorId=auth.member(actorRequest),peerId=auth.member(peerRequest);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try{
            Future<Boolean> fromActor=pool.submit(()->{ready.countDown();assertTrue(start.await(12,TimeUnit.SECONDS));return market.follow(actorId,peerId,true);});
            Future<Boolean> fromPeer=pool.submit(()->{ready.countDown();assertTrue(start.await(12,TimeUnit.SECONDS));return market.follow(peerId,actorId,true);});
            assertTrue(ready.await(12,TimeUnit.SECONDS));start.countDown();
            assertTrue(assertDoesNotThrow(()->fromActor.get(15,TimeUnit.SECONDS)));assertTrue(assertDoesNotThrow(()->fromPeer.get(15,TimeUnit.SECONDS)));
            assertEquals(2,db.count("SELECT COUNT(*) FROM boost_follow WHERE (member_id=? AND seller_id=?) OR (member_id=? AND seller_id=?)",actor,peer,peer,actor));
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
    }
}
