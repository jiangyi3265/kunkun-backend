package com.ruoyi.boost;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real filter + real @ss/SysPermissionService, mocked token storage and database service boundary.
 * The identical bearer token always returns the original cached login snapshot. No refresh endpoint,
 * HTTP server, Redis or SQL mapper integration is claimed by these deterministic tests. */
class BoostSystemPermissionRevocationTest {
 static final String READ="system:role:list",EDIT="system:user:edit",TOKEN="unchanged-isolated-session";
 JwtAuthenticationTokenFilter filter;PermissionService access;SysPermissionService permissions;
 TokenService tokens;ISysUserService users;ISysMenuService menus;ISysRoleService roles;
 Set<String> currentPermissions,cachedPermissions;
 String userStatus,userDeleted,roleStatus,roleDeleted,roleKey,dataScope;
 boolean userExists,roleAssigned;long userId;
 @BeforeEach void setup(){
  SecurityContextHolder.clearContext();userId=2;userExists=true;roleAssigned=true;
  userStatus="0";userDeleted="0";roleStatus="0";roleDeleted="0";roleKey="review_ops";dataScope="1";
  currentPermissions=new HashSet<>(Set.of(READ));cachedPermissions=new HashSet<>(Set.of(READ));
  tokens=mock(TokenService.class);users=mock(ISysUserService.class);menus=mock(ISysMenuService.class);roles=mock(ISysRoleService.class);
  when(tokens.verifyToken(any())).thenReturn(true);
  permissions=new SysPermissionService();ReflectionTestUtils.setField(permissions,"menuService",menus);injectIfPresent(permissions,"roleService",roles);
  filter=new JwtAuthenticationTokenFilter();injectIfPresent(filter,"tokenService",tokens);injectIfPresent(filter,"userService",users);injectIfPresent(filter,"permissionService",permissions);
  access=new PermissionService();
  when(tokens.getLoginUser(any())).thenAnswer(invocation->{
   MockHttpServletRequest request=invocation.getArgument(0);
   if(!("Bearer "+TOKEN).equals(request.getHeader("Authorization")))return null;
   // Model a fresh Redis deserialization each request; business permission changes never rewrite it.
   SysUser user=user(userId,"0","0",List.of(role("0","0","review_ops","1")));
   LoginUser login=new LoginUser(userId,1L,user,new HashSet<>(cachedPermissions));login.setToken(TOKEN);return login;
  });
  when(users.selectUserById(anyLong())).thenAnswer(invocation->userExists?currentUser():null);
  when(menus.selectMenuPermsByRoleId(anyLong())).thenAnswer(invocation->new HashSet<>(currentPermissions));
  // Mirrors the original mapper boundary: role status is checked, role del_flag is not.
  // The production implementation must not revive deleted roles through its empty-role fallback.
  when(menus.selectMenuPermsByUserId(anyLong())).thenAnswer(invocation->roleAssigned&&"0".equals(roleStatus)?new HashSet<>(currentPermissions):Collections.emptySet());
  when(roles.selectRolePermissionByUserId(anyLong())).thenAnswer(invocation->roleAssigned?Set.of(roleKey):Collections.emptySet());
 }
 static void injectIfPresent(Object bean,String name,Object value){if(ReflectionUtils.findField(bean.getClass(),name)!=null)ReflectionTestUtils.setField(bean,name,value);}
 static SysRole role(String status,String deleted,String key,String scope){SysRole r=new SysRole(9L);r.setRoleKey(key);r.setRoleName("Isolated review role");r.setStatus(status);r.setDelFlag(deleted);r.setDataScope(scope);r.setPermissions(new HashSet<>(Set.of(READ)));return r;}
 static SysUser user(long id,String status,String deleted,List<SysRole> roles){SysUser u=new SysUser();u.setUserId(id);u.setUserName("isolated_system_review");u.setStatus(status);u.setDelFlag(deleted);u.setRoles(new ArrayList<>(roles));return u;}
 SysUser currentUser(){return user(userId,userStatus,userDeleted,roleAssigned?List.of(role(roleStatus,roleDeleted,roleKey,dataScope)):Collections.emptyList());}
 static class Outcome {int status;boolean hasRole,hasAnyRole;LoginUser principal;}
 Outcome request(String permission)throws Exception{
  SecurityContextHolder.clearContext();MockHttpServletRequest request=new MockHttpServletRequest("GET","/system/role/list");request.addHeader("Authorization","Bearer "+TOKEN);
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  MockHttpServletResponse response=new MockHttpServletResponse();Outcome out=new Outcome();AtomicBoolean reached=new AtomicBoolean();
  filter.doFilter(request,response,(req,res)->{
   reached.set(true);if(SecurityContextHolder.getContext().getAuthentication()==null){response.setStatus(401);return;}
   out.principal=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
   out.hasRole=access.hasRole("review_ops");out.hasAnyRole=access.hasAnyRoles("other,review_ops");
   response.setStatus(access.hasPermi(permission)&&access.hasAnyPermi("unrelated:permission,"+permission)?200:403);
  });
  out.status=response.getStatus();if(!reached.get())assertTrue(out.status>=400,"An aborted filter must not return a false HTTP200");return out;
 }
 void denied(Outcome out){assertTrue(out.status==401||out.status==403||out.status==503,"Expected explicit fail-closed response, got "+out.status);}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}

 @Test void unchangedActiveSessionRetainsItsPermission()throws Exception{Outcome out=request(READ);assertEquals(200,out.status);assertTrue(out.hasRole);assertTrue(out.hasAnyRole);}
 @Test void sameTokenMenuRevocationDeniesWithoutGetInfoRefresh()throws Exception{assertEquals(200,request(READ).status);currentPermissions.clear();denied(request(READ));}
 @Test void sameTokenRoleAssignmentRemovalRevokesMenuAndRole()throws Exception{assertEquals(200,request(READ).status);roleAssigned=false;Outcome out=request(READ);denied(out);assertFalse(out.hasRole);assertFalse(out.hasAnyRole);}
 @ParameterizedTest @ValueSource(strings={"disabled","deleted"})
 void sameTokenRoleStatusOrDeletionCannotRetainPrivileges(String change)throws Exception{
  assertEquals(200,request(READ).status);if("disabled".equals(change))roleStatus="1";else roleDeleted="2";
  Outcome out=request(READ);denied(out);assertFalse(out.hasRole);assertFalse(out.hasAnyRole);
 }
 @ParameterizedTest @ValueSource(strings={"disabled","deleted","missing"})
 void sameTokenUserStatusDeletionOrRemovalDenies(String change)throws Exception{
  assertEquals(200,request(READ).status);if("disabled".equals(change))userStatus="1";else if("deleted".equals(change))userDeleted="2";else userExists=false;
  denied(request(READ));
 }
 @Test void superAdministratorMustStillBeActive()throws Exception{
  userId=1;cachedPermissions=Set.of("*:*:*");assertEquals(200,request(READ).status);userStatus="1";denied(request(READ));
 }
 @ParameterizedTest @ValueSource(strings={"user","permission"})
 void currentAuthorizationReadFailureCannotFallBackToCachedSuccess(String boundary)throws Exception{
  assertEquals(200,request(READ).status);
  if("user".equals(boundary))when(users.selectUserById(anyLong())).thenThrow(new IllegalStateException("isolated identity read failure"));
  else when(menus.selectMenuPermsByRoleId(anyLong())).thenThrow(new IllegalStateException("isolated permission read failure"));
  denied(request(READ));
 }
 @Test void newlyGrantedPermissionCannotExpandExistingCachedSession()throws Exception{
  assertEquals(200,request(READ).status);currentPermissions.add(EDIT);denied(request(EDIT));assertEquals(200,request(READ).status);
 }
 @Test void newlyAssignedRoleCannotExpandUnchangedCachedSessionOrReviveEmptyRoleIntersection()throws Exception{
  assertEquals(200,request(READ).status);assertFalse(access.hasRole("newly_assigned_role"));
  SysRole added=role("0","0","newly_assigned_role","1");added.setRoleId(10L);
  // Redis still deserializes only role9 above; role10 exists only in the current database result.
  when(users.selectUserById(anyLong())).thenAnswer(invocation->user(2,"0","0",List.of(role("0","0","review_ops","1"),added)));
  Outcome retained=request(READ);assertEquals(200,retained.status,"Existing role9 permission must keep working");
  assertFalse(access.hasRole("newly_assigned_role"));assertFalse(access.hasAnyRoles("other,newly_assigned_role"));
  assertTrue(retained.principal.getUser().getRoles().stream().noneMatch(r->Long.valueOf(10).equals(r.getRoleId())));
  // Removing role9 leaves no cached/live role intersection. A per-user fallback must not revive role10's READ.
  when(users.selectUserById(anyLong())).thenAnswer(invocation->user(2,"0","0",List.of(added)));
  denied(request(READ));
 }
 @Test void liveRoleDataScopeReplacesStaleBroadCachedScope()throws Exception{
  assertEquals("1",request(READ).principal.getUser().getRoles().get(0).getDataScope());dataScope="5";
  Outcome out=request(READ);assertEquals(200,out.status);assertEquals("5",out.principal.getUser().getRoles().get(0).getDataScope());
 }
 @ParameterizedTest @ValueSource(strings={"disabled","deleted"})
 void permissionServiceRoleChecksRejectInactiveRoleEvenOnAnExistingPrincipal(String change){
  SysRole r=role("disabled".equals(change)?"1":"0","deleted".equals(change)?"2":"0","review_ops","1");
  LoginUser login=new LoginUser(2L,1L,user(2,"0","0",List.of(r)),Set.of(READ));
  SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(login,"",login.getAuthorities()));
  assertFalse(access.hasRole("review_ops"));assertFalse(access.hasAnyRoles("unrelated,review_ops"));
 }
 @Test void nonSuperAdministratorCannotGainWildcardRoleFromAdminNamedRole(){
  LoginUser login=new LoginUser(2L,1L,user(2,"0","0",List.of(role("0","0","admin","1"))),Set.of(READ));
  SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(login,"",login.getAuthorities()));
  assertFalse(access.hasRole("unrelated_protected_role"));assertFalse(access.hasRole("admin"));
 }
 @Test void roleMetadataForGetInfoExcludesInactiveDeletedAndAdministratorRoles(){
  SysRole disabled=role("1","0","disabled_role","1");disabled.setRoleId(10L);
  SysRole deleted=role("0","2","deleted_role","1");deleted.setRoleId(11L);
  SysRole adminNamed=role("0","0","admin","1");adminNamed.setRoleId(12L);
  SysRole superRole=role("0","0","other_super_key","1");superRole.setRoleId(1L);
  // Original role-service query filtered del_flag but included disabled roles and admin role keys.
  when(roles.selectRolePermissionByUserId(2L)).thenReturn(Set.of("review_ops","disabled_role","admin","other_super_key"));
  SysUser current=user(2,"0","0",List.of(role("0","0","review_ops","1"),disabled,deleted,adminNamed,superRole));
  assertEquals(Set.of("review_ops"),permissions.getRolePermission(current));
  assertEquals(Set.of("admin"),permissions.getRolePermission(user(1,"0","0",Collections.emptyList())));
 }
}
