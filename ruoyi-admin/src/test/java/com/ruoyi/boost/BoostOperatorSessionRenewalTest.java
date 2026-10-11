package com.ruoyi.boost;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import io.lettuce.core.resource.DefaultClientResources;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.RedisConfig;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.web.service.*;
import com.ruoyi.system.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual TokenService, RedisCache and production serializer on a newly owned Redis process.
 * SQL identity/menu boundaries use Mockito. No shared Redis, database, HTTP server or real login. */
class BoostOperatorSessionRenewalTest {
 static Process ownedRedis;static LettuceConnectionFactory connections;static DefaultClientResources resources;
 static RedisTemplate<Object,Object> template;static RedisCache cache;static Path directory,config;
 TokenService tokens;String bearer;LoginUser initial;

 @BeforeAll static void startOwnedRedis()throws Exception{
  int port;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
  assertNotEquals(6379,port,"Integration must never use shared Redis");
  directory=Files.createTempDirectory("boost-session-renewal-");config=directory.resolve("redis.conf");
  String password=UUID.randomUUID().toString();
  Files.writeString(config,"bind 127.0.0.1\nport "+port+"\nrequirepass "+password+"\nsave \"\"\nappendonly no\nmaxmemory 32mb\n");
  String executable=System.getenv("BOOST_TEST_REDIS_SERVER");
  if(executable==null||executable.isBlank())executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"C:/Program Files/Redis/redis-server.exe":"redis-server";
  try{
   ownedRedis=new ProcessBuilder(executable,config.toString()).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("owned-redis.log").toFile()).start();
   RedisStandaloneConfiguration standalone=new RedisStandaloneConfiguration("127.0.0.1",port);standalone.setPassword(password);
   resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
   connections=new LettuceConnectionFactory(standalone,LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(2)).build());connections.afterPropertiesSet();connections.start();
   template=new RedisConfig().redisTemplate(connections);cache=new RedisCache();cache.redisTemplate=template;
   boolean ready=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
   while(System.nanoTime()<deadline&&ownedRedis.isAlive()){
    try(var connection=connections.getConnection()){if("PONG".equals(connection.ping())){ready=true;break;}}catch(RuntimeException ignored){Thread.sleep(100);}
   }
   assertTrue(ready,"Owned Redis did not become ready; inspect "+directory.resolve("owned-redis.log"));
   Files.deleteIfExists(config);
   System.out.println("Owned renewal Redis ready on nondefault port "+port+"; pid="+ownedRedis.pid()+"; evidence="+directory.resolve("owned-redis.log"));
  }catch(Throwable failure){stopOwnedRedis();throw failure;}
 }
 @AfterAll static void stopOwnedRedis()throws Exception{
  if(connections!=null)connections.destroy();if(resources!=null)resources.shutdown().get(5,TimeUnit.SECONDS);
  if(ownedRedis!=null){ownedRedis.destroy();if(!ownedRedis.waitFor(5,TimeUnit.SECONDS)){ownedRedis.destroyForcibly();assertTrue(ownedRedis.waitFor(5,TimeUnit.SECONDS));}assertFalse(ownedRedis.isAlive());}
  if(config!=null)Files.deleteIfExists(config);
 }
 @BeforeEach void setup(){
  SecurityContextHolder.clearContext();MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("User-Agent","Isolated renewal regression");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  tokens=new TokenService();ReflectionTestUtils.setField(tokens,"redisCache",cache);ReflectionTestUtils.setField(tokens,"header","Authorization");ReflectionTestUtils.setField(tokens,"secret","isolated-test-signing-material-only-".repeat(3));ReflectionTestUtils.setField(tokens,"expireTime",30);
  initial=new LoginUser(2L,1L,currentUser(),new HashSet<>(Set.of("system:role:list")));bearer=tokens.createToken(initial);
 }
 @AfterEach void cleanup(){cache.deleteObject(key());SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
 static SysUser currentUser(){SysRole role=new SysRole(9L);role.setRoleKey("review_ops");role.setStatus("0");role.setDelFlag("0");role.setDataScope("5");role.setPermissions(new HashSet<>(Set.of("system:role:list")));SysUser user=new SysUser();user.setUserId(2L);user.setDeptId(1L);user.setUserName("isolated_renewal");user.setStatus("0");user.setDelFlag("0");user.setRoles(new ArrayList<>(List.of(role)));return user;}
 String key(){return CacheConstants.LOGIN_TOKEN_KEY+initial.getToken();}
 MockHttpServletRequest request(){MockHttpServletRequest request=new MockHttpServletRequest("GET","/system/role/list");request.addHeader("Authorization","Bearer "+bearer);return request;}
 LoginUser nearExpiry(){LoginUser snapshot=tokens.getLoginUser(request());assertNotNull(snapshot);snapshot.setExpireTime(System.currentTimeMillis()+60_000);cache.setCacheObject(key(),snapshot,30,TimeUnit.MINUTES);return tokens.getLoginUser(request());}
 JwtAuthenticationTokenFilter filter(ISysUserService users){ISysMenuService menus=mock(ISysMenuService.class);when(menus.selectMenuPermsByRoleId(9L)).thenReturn(Set.of("system:role:list"));SysPermissionService permissions=new SysPermissionService();ReflectionTestUtils.setField(permissions,"menuService",menus);JwtAuthenticationTokenFilter filter=new JwtAuthenticationTokenFilter();ReflectionTestUtils.setField(filter,"tokenService",tokens);ReflectionTestUtils.setField(filter,"userService",users);ReflectionTestUtils.setField(filter,"permissionService",permissions);return filter;}
 int filtered(JwtAuthenticationTokenFilter filter)throws Exception{
  SecurityContextHolder.clearContext();MockHttpServletRequest request=request();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));MockHttpServletResponse response=new MockHttpServletResponse();
  try{filter.doFilter(request,response,(req,res)->response.setStatus(SecurityContextHolder.getContext().getAuthentication()==null?401:200));return response.getStatus();}
  finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
 }

 @Test void newlyCreatedSessionAndNormalRenewalRemainUsable(){
  LoginUser snapshot=nearExpiry();tokens.verifyToken(snapshot);LoginUser renewed=tokens.getLoginUser(request());assertNotNull(renewed);assertTrue(renewed.getExpireTime()>System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(25));assertEquals(Set.of("system:role:list"),renewed.getPermissions());assertTrue(cache.getExpire(key())>TimeUnit.MINUTES.toSeconds(25));
 }
 @Test void snapshotReadBeforeDeleteCannotRecreateTheRevokedSession(){
  LoginUser snapshot=nearExpiry();tokens.delLoginUser(initial.getToken());assertNull(tokens.getLoginUser(request()));tokens.verifyToken(snapshot);assertNull(tokens.getLoginUser(request()),"A delayed renewal must not undo logout or forced logout");assertFalse(cache.hasKey(key()));
 }
 @Test void snapshotReadBeforeRealRedisExpiryCannotRecreateTheExpiredSession()throws Exception{
  LoginUser snapshot=nearExpiry();assertTrue(cache.expire(key(),80,TimeUnit.MILLISECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(Boolean.TRUE.equals(cache.hasKey(key()))&&System.nanoTime()<deadline)Thread.sleep(10);assertFalse(cache.hasKey(key()),"Actual owned Redis TTL must expire before renewal");tokens.verifyToken(snapshot);assertNull(tokens.getLoginUser(request()),"Expired session must not be created by renewal");
 }
 @Test void profileCacheUpdateCannotRecreateTheDeletedSession(){
  LoginUser snapshot=tokens.getLoginUser(request());tokens.delLoginUser(initial.getToken());snapshot.getUser().setNickName("new profile");tokens.setLoginUser(snapshot);assertNull(tokens.getLoginUser(request()),"setLoginUser must only update a still-live session");
 }
 @Test void profileCacheUpdateStillPersistsForAnExistingSession(){LoginUser snapshot=tokens.getLoginUser(request());snapshot.getUser().setNickName("persisted profile");tokens.setLoginUser(snapshot);assertEquals("persisted profile",tokens.getLoginUser(request()).getUser().getNickName());}
 @Test void concurrentDeleteBetweenTokenReadAndRenewalCannotAuthenticateOrRecreate()throws Exception{
  nearExpiry();CountDownLatch read=new CountDownLatch(1),continueRequest=new CountDownLatch(1);ISysUserService users=mock(ISysUserService.class);when(users.selectUserById(2L)).thenAnswer(invocation->{read.countDown();assertTrue(continueRequest.await(5,TimeUnit.SECONDS));return currentUser();});JwtAuthenticationTokenFilter filter=filter(users);ExecutorService executor=Executors.newSingleThreadExecutor();
  try{Future<Integer> response=executor.submit(()->filtered(filter));assertTrue(read.await(5,TimeUnit.SECONDS));tokens.delLoginUser(initial.getToken());assertFalse(cache.hasKey(key()));continueRequest.countDown();assertEquals(401,response.get(5,TimeUnit.SECONDS),"Filter must not authenticate after its conditional renewal lost to DEL");assertNull(tokens.getLoginUser(request()));}
  finally{continueRequest.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void redisFailureDuringRenewalFailsClosedWith503()throws Exception{
  nearExpiry();RedisCache unavailable=new RedisCache();unavailable.redisTemplate=mock(RedisTemplate.class);when(unavailable.redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("isolated renewal failure"));ISysUserService users=mock(ISysUserService.class);when(users.selectUserById(2L)).thenAnswer(invocation->{ReflectionTestUtils.setField(tokens,"redisCache",unavailable);return currentUser();});
  AtomicBoolean completed=new AtomicBoolean();int[] status={0};assertDoesNotThrow(()->{status[0]=filtered(filter(users));completed.set(true);},"Redis failure must become an explicit fail-closed HTTP response");assertTrue(completed.get());assertEquals(503,status[0]);
 }
 @Test void newlyGrantedPermissionOnExistingBroadRoleCannotExpandOldSessionsDataScope()throws Exception{
  String read="system:role:list",other="system:dept:list";
  SysRole cachedBroad=new SysRole(10L);cachedBroad.setRoleKey("other_department_ops");cachedBroad.setStatus("0");cachedBroad.setDelFlag("0");cachedBroad.setDataScope("1");cachedBroad.setPermissions(new HashSet<>(Set.of(other)));
  initial.getUser().getRoles().add(cachedBroad);initial.setPermissions(new HashSet<>(Set.of(read,other)));cache.setCacheObject(key(),initial,30,TimeUnit.MINUTES);
  ISysUserService users=mock(ISysUserService.class);when(users.selectUserById(2L)).thenAnswer(invocation->{SysUser live=currentUser();SysRole broad=new SysRole(10L);broad.setRoleKey("other_department_ops");broad.setStatus("0");broad.setDelFlag("0");broad.setDataScope("1");live.getRoles().add(broad);return live;});
  ISysMenuService menus=mock(ISysMenuService.class);when(menus.selectMenuPermsByRoleId(9L)).thenReturn(Set.of(read));when(menus.selectMenuPermsByRoleId(10L)).thenReturn(Set.of(read,other));SysPermissionService permissionService=new SysPermissionService();ReflectionTestUtils.setField(permissionService,"menuService",menus);JwtAuthenticationTokenFilter filter=filter(users);ReflectionTestUtils.setField(filter,"permissionService",permissionService);
  MockHttpServletRequest request=request();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));MockHttpServletResponse response=new MockHttpServletResponse();SysUser query=new SysUser();org.aspectj.lang.JoinPoint point=mock(org.aspectj.lang.JoinPoint.class);when(point.getArgs()).thenReturn(new Object[]{query});
  filter.doFilter(request,response,(req,res)->{assertNotNull(SecurityContextHolder.getContext().getAuthentication());LoginUser principal=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();assertTrue(new PermissionService().hasPermi(read),"The old SELF permission still works");DataScopeAspect.dataScopeFilter(point,principal.getUser(),"d","u",read);});
  String sql=String.valueOf(query.getParams().getOrDefault("dataScope",""));assertTrue(sql.contains("u.user_id = 2"),"New READ on old broad role must not erase SELF restriction: "+sql);
 }
}
