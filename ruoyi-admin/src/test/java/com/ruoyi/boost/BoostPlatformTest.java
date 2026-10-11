package com.ruoyi.boost;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import static org.junit.jupiter.api.Assertions.*;

class BoostPlatformTest {
 @Test void realMoneyModeCannotSkipProductionGuard(){
  org.springframework.mock.env.MockEnvironment env=new org.springframework.mock.env.MockEnvironment().withProperty("boost.simulated-payment","false").withProperty("boost.live","false");
  IllegalStateException error=assertThrows(IllegalStateException.class,()->new BoostProductionGuard(env,null,null,null).validate());
  assertTrue(error.getMessage().contains("production validation"));
 }
 @Test void operatorAuthenticationSupportsNonNullAuthorities(){
  BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();SysUser user=new SysUser();user.setUserName("operator");user.setPassword(encoder.encode("PrivateTest2026!"));
  LoginUser principal=new LoginUser(2L,1L,user,Set.of("boost:orders:read"));
  DaoAuthenticationProvider provider=new DaoAuthenticationProvider(name->principal);provider.setPasswordEncoder(encoder);
  assertTrue(provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("operator","PrivateTest2026!")).isAuthenticated());
  assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("operator","wrong")));
 }
 @Test void runtimeDatabaseGrantsAreStrictlyScoped(){
  assertTrue(BoostProductionGuard.scopedGrant("GRANT USAGE ON *.* TO `app`@`localhost`","boost_prod"));
  assertTrue(BoostProductionGuard.scopedGrant("GRANT SELECT, INSERT, UPDATE, DELETE ON `boost_prod`.* TO `app`@`localhost`","boost_prod"));
  for(String grant:new String[]{"GRANT SELECT ON *.* TO `app`@`localhost`","GRANT SELECT ON `other`.* TO `app`@`localhost`","GRANT ALL PRIVILEGES ON `boost_prod`.* TO `app`@`localhost`","GRANT SELECT, CREATE ON `boost_prod`.* TO `app`@`localhost`","GRANT SELECT ON `boost_prod`.* TO `app`@`localhost` WITH GRANT OPTION","GRANT `powerful_role`@`%` TO `app`@`localhost`"})assertFalse(BoostProductionGuard.scopedGrant(grant,"boost_prod"),grant);
 }
 @Test void productionOriginsAreExactHttpsDomains(){
  assertTrue(BoostProductionGuard.validOrigin("https://app.example.com"));
  for(String origin:new String[]{"*","https://*.example.com","http://app.example.com","https://localhost","https://127.0.0.1","https://app.example.com/path","https://app.example.com?x=1","https://user@app.example.com"})assertFalse(BoostProductionGuard.validOrigin(origin),origin);
 }
}
