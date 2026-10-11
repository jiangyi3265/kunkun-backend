package com.ruoyi.boost;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;
class BoostPlayerMigrationTest {
 @Test void realV002DataSurvivesAdditiveUpgradeAndOldReadersStillWork()throws Exception{
  JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:migrateplayer"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  upgrade(source);
 }
 @Test @EnabledIfEnvironmentVariable(named="BOOST_MIGRATION_TEST_DB_URL",matches=".+") void mysqlV002DataSurvivesAdditiveUpgradeAndOldReadersStillWork()throws Exception{
  String url=System.getenv("BOOST_MIGRATION_TEST_DB_URL");if(!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/boost_verify_[0-9]+\\?.*"))throw new IllegalArgumentException("Only an isolated local migration database is allowed");
  upgrade(new DriverManagerDataSource(url,System.getenv("BOOST_MIGRATION_TEST_DB_USER"),System.getenv("BOOST_MIGRATION_TEST_DB_PASSWORD")));
 }
 private void upgrade(DataSource source)throws Exception{
  new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql"),new ClassPathResource("boost/migrations/V002__commercial_hardening.sql")).execute(source);
  BoostDb db=new BoostDb(new JdbcTemplate(source));String v2=org.springframework.util.StreamUtils.copyToString(new ClassPathResource("boost/migrations/V002__commercial_hardening.sql").getInputStream(),java.nio.charset.StandardCharsets.UTF_8);
  db.run("INSERT INTO boost_config(config_key,config_value) VALUES (?,?)","schema:V002__commercial_hardening",Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(v2.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  long buyer=db.insert("INSERT INTO boost_member(username,password_hash,nickname) VALUES ('oldbuyer','fixture-only','旧买家')"),seller=db.insert("INSERT INTO boost_member(username,password_hash,nickname,certification,proof,balance_cents) VALUES ('oldseller','fixture-only','旧打手','APPROVED','[\"/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png\"]',12300)");
  long listing=db.insert("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,status) VALUES (?,'SERVICE','旧代练服务','王者荣耀','微信区','安卓','星耀','王者',24,1000,'原始说明','[]','ACTIVE')",seller);
  List<Long> ids=new ArrayList<>();for(String status:List.of("WAIT_PAY","IN_PROGRESS","AFTERSALE")){long order=db.insert("INSERT INTO boost_order(order_no,request_key,buyer_id,worker_id,listing_id,title,game,zone,platform,current_rank,target_rank,duration_hours,original_cents,amount_cents,status,progress) VALUES (?,?,?,?,?,'旧代练服务','王者荣耀','微信区','安卓','星耀','王者',24,1000,1000,?,60)","OLD-"+status,UUID.randomUUID().toString(),buyer,seller,listing,status);ids.add(order);db.run("INSERT INTO boost_progress(order_id,member_id,percent,note,images) VALUES (?,?,60,'旧进度原样保存','[\"/boost-media/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png\"]')",order,seller);}
  db.run("INSERT INTO boost_aftersale(order_id,member_id,reason,description,images,amount_cents,previous_status) VALUES (?,?,'旧售后原因','旧售后说明','[]',1000,'IN_PROGRESS')",ids.get(2),buyer);
  List<Map<String,Object>> before=db.list("SELECT id,title,game,current_rank,target_rank,duration_hours,progress,status,amount_cents FROM boost_order ORDER BY id");
  BoostMigrations.migrate(db);BoostMigrations.verifyApplied(db);BoostMigrations.migrate(db);
  assertEquals(before,db.list("SELECT id,title,game,current_rank,target_rank,duration_hours,progress,status,amount_cents FROM boost_order ORDER BY id"));assertEquals(3,db.count("SELECT COUNT(*) FROM boost_order WHERE business_type='LEGACY_BOOST'"));assertEquals(3,db.count("SELECT COUNT(*) FROM boost_progress"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_aftersale"));assertEquals(12300,db.count("SELECT balance_cents FROM boost_member WHERE id=?",seller));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_player_profile"));
  BoostTrade historical=new BoostTrade(db,new BoostAuth(db));assertEquals("IN_PROGRESS",str(historical.detail(ids.get(1),buyer),"status"));assertEquals(1,((List<?>)historical.detail(ids.get(1),buyer).get("updates")).size());assertNotNull(historical.detail(ids.get(2),buyer).get("aftersale"));
  assertEquals(0,db.count("SELECT deposit_cents FROM boost_member WHERE id=?",seller));
  db.run("UPDATE boost_member SET deposit_cents=4567 WHERE id=?",seller);BoostMigrations.migrate(db);BoostMigrations.verifyApplied(db);
  assertEquals(4567,db.count("SELECT deposit_cents FROM boost_member WHERE id=?",seller));assertEquals(12300,db.count("SELECT balance_cents FROM boost_member WHERE id=?",seller));
  db.run("UPDATE boost_config SET config_value='corrupt-checksum' WHERE config_key=?","schema:"+BoostMigrations.VERSION);assertThrows(IllegalStateException.class,()->BoostMigrations.verifyApplied(db));assertThrows(IllegalStateException.class,()->BoostMigrations.migrate(db));
 }
}
