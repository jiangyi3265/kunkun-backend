package com.ruoyi.boost;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB snapshots are required: H2 does not reproduce these races. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostMediaRaceTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL",matches=".+")
class BoostMediaRaceTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
  @Bean PausingDb db(DataSource d){return new PausingDb(new JdbcTemplate(d));}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
  @Bean BoostMediaFiles media(BoostAuth a,BoostDb d){return new BoostMediaFiles(a,d,new BoostAccess(){@Override public boolean can(String permission){return false;}});}
 }
 static class PausingDb extends BoostDb {
  volatile Consumer<String> afterRead;
  PausingDb(JdbcTemplate jdbc){super(jdbc);}
  @Override public List<Map<String,Object>> list(String sql,Object... args){
   List<Map<String,Object>> result=super.list(sql,args);Consumer<String> gate=afterRead;if(gate!=null)gate.accept(sql);return result;
  }
 }
 @Autowired PausingDb db;@Autowired BoostAuth auth;@Autowired BoostMediaFiles media;@Autowired PlatformTransactionManager tx;
 @TempDir Path directory;
 long member;String token;byte[] png;
 @BeforeEach void setup()throws Exception{
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_media"),"Use an isolated database without media assets");
  ReflectionTestUtils.setField(db,"mediaDirectory",directory.toString());ReflectionTestUtils.setField(media,"directory",directory.toString());
  ReflectionTestUtils.setField(media,"quota",104857600L);ReflectionTestUtils.setField(media,"hourlyLimit",60);
  Map<String,Object> a=auth.register(map("username","qa_media_"+UUID.randomUUID().toString().replace("-","").substring(0,18),"password","MediaRace2026!","nickname","图片并发回归"));
  member=num((Map<String,Object>)a.get("user"),"id");token=str(a,"token");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,1),"png",bytes);png=bytes.toByteArray();
 }
 @AfterEach void cleanup(){
  db.afterRead=null;if(member==0)return;
  db.run("DELETE FROM boost_media WHERE owner_id=?",member);db.run("DELETE FROM boost_notification WHERE member_id=?",member);
  db.run("DELETE FROM boost_coupon WHERE member_id=?",member);db.run("DELETE FROM boost_session WHERE member_id=?",member);db.run("DELETE FROM boost_member WHERE id=?",member);
 }
 String upload()throws Exception{
  MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Boost-Token",token);
  return str((Map<String,Object>)media.upload(new MockMultipartFile("file","p.png","image/png",png),"CERTIFICATION",request),"url");
 }
 static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for race gate");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
 @Test void cleanupSeesEvidenceAttachedAfterItsCandidateSnapshot()throws Exception{
  String url=upload(),name=url.substring(13);
  db.run("UPDATE boost_media SET created_at=? WHERE name=?",Timestamp.from(Instant.now().minusSeconds(8*86400)),name);
  CountDownLatch scanned=new CountDownLatch(1),attached=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
  db.afterRead=sql->{if(sql.startsWith("SELECT * FROM boost_media WHERE created_at<")){scanned.countDown();await(attached);}};
  try{
   Future<Integer> cleanup=pool.submit(()->media.collectOrphans());await(scanned);
   new TransactionTemplate(tx).execute(s->{db.uploadedImage(url,member,"CERTIFICATION");db.run("UPDATE boost_member SET proof=? WHERE id=?",BoostInput.images(map("images",Collections.singletonList(url))),member);return null;});
   attached.countDown();assertEquals(0,cleanup.get(20,TimeUnit.SECONDS));
   assertTrue(Files.isRegularFile(directory.resolve(name)));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_media WHERE name=?",name));
  }finally{attached.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));db.afterRead=null;}
 }
 @Test void simultaneousUploadsCannotBypassHourlyLimit()throws Exception{ReflectionTestUtils.setField(media,"hourlyLimit",1);assertOnlyOneConcurrentUpload();}
 @Test void simultaneousUploadsCannotBypassStorageQuota()throws Exception{ReflectionTestUtils.setField(media,"quota",(long)png.length);assertOnlyOneConcurrentUpload();}
 void assertOnlyOneConcurrentUpload()throws Exception{
  CyclicBarrier readTogether=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
  db.afterRead=sql->{if(sql.startsWith("SELECT m.id FROM boost_session"))try{readTogether.await(15,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}};
  try{
   Callable<Integer> job=()->{try{upload();return 200;}catch(BoostException e){return e.code;}};
   List<Future<Integer>> jobs=Arrays.asList(pool.submit(job),pool.submit(job));List<Integer> codes=new ArrayList<>();for(Future<Integer> f:jobs)codes.add(f.get(25,TimeUnit.SECONDS));Collections.sort(codes);
   assertEquals(Arrays.asList(200,429),codes);assertEquals(1,db.count("SELECT COUNT(*) FROM boost_media WHERE owner_id=?",member));
   try(java.util.stream.Stream<Path> files=Files.list(directory)){assertEquals(1,files.count());}
  }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));db.afterRead=null;}
 }
}
