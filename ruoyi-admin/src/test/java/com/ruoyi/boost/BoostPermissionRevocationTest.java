package com.ruoyi.boost;

import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BoostPermissionRevocationTest {
 BoostDb db;BoostAccess access;
 @BeforeEach void setup(){
  JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:permission_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new BoostDb(new JdbcTemplate(d));
  for(String sql:new String[]{"CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))","CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))","CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)","CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,status VARCHAR(1),perms VARCHAR(100))","CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)"})db.run(sql);
  db.run("INSERT INTO sys_user VALUES(2,'0','0')");db.run("INSERT INTO sys_role VALUES(9,'0','0')");db.run("INSERT INTO sys_user_role VALUES(2,9)");db.run("INSERT INTO sys_menu VALUES(8,'0','boost:members:read')");db.run("INSERT INTO sys_role_menu VALUES(9,8)");
  access=new BoostAccess();
  // Allow the same regression file to run against the original class before its live datasource field exists.
  if(org.springframework.util.ReflectionUtils.findField(BoostAccess.class,"db")!=null)ReflectionTestUtils.setField(access,"db",db);
  login(2,Set.of("boost:members:read"));
 }
 void login(long id,Set<String> p){SysUser user=new SysUser();user.setUserId(id);user.setUserName("isolated_operator");LoginUser principal=new LoginUser(id,1L,user,p);SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",principal.getAuthorities()));}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();db.run("SHUTDOWN");}
 @Test void roleRemovalRevokesNewBusinessAccessWithoutRefresh(){assertTrue(access.can("boost:members:read"));db.run("DELETE FROM sys_role_menu");assertFalse(access.can("boost:members:read"));assertFalse(access.media("CERTIFICATION"));}
 @Test void roleAndUserSuspensionCannotUseCachedPermission(){assertTrue(access.can("boost:members:read"));db.run("UPDATE sys_role SET status='1'");assertFalse(access.can("boost:members:read"));db.run("UPDATE sys_role SET status='0'");db.run("UPDATE sys_user SET status='1'");assertFalse(access.can("boost:members:read"));}
 @Test void deletedOperatorAndMissingPrincipalRemainDenied(){db.run("UPDATE sys_user SET del_flag='2'");assertFalse(access.can("boost:members:read"));SecurityContextHolder.clearContext();assertFalse(access.can("boost:members:read"));}
 @Test void liveGrantCannotExpandCachedSessionAndSuperAdminMustBeActive(){login(2,Set.of());assertFalse(access.can("boost:members:read"));db.run("INSERT INTO sys_user VALUES(1,'1','0')");login(1,Set.of("*:*:*"));assertFalse(access.can("boost:members:read"));db.run("UPDATE sys_user SET status='0' WHERE user_id=1");assertTrue(access.can("boost:members:read"));}
}
