package com.ruoyi.boost;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.*;
import com.ruoyi.system.domain.vo.RouterVo;
import com.ruoyi.web.controller.system.SysLoginController;
import com.ruoyi.web.controller.system.SysRoleController;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real filter, controllers and permission evaluator. Token/cache and SQL services are explicit
 * in-memory substitutes; these tests do not claim HTTP, Redis or MyBatis integration. */
class BoostPermissionRefreshBoundaryTest {
    static final String READ="system:role:list", EDIT="system:user:edit", MANAGE="system:role:edit";
    static final String TOKEN="unchanged-refresh-boundary-session";
    JwtAuthenticationTokenFilter filter;
    PermissionService access;
    TokenService tokens;
    ISysUserService users;
    ISysRoleService roleService;
    SysLoginController info;
    SysRoleController roleController;
    Set<String> storedPermissions, livePermissions;
    List<Long> storedRoles, liveRoles;

    @BeforeEach void setup() {
        storedPermissions=new HashSet<>(Set.of(READ, MANAGE));
        livePermissions=new HashSet<>(storedPermissions);
        storedRoles=new ArrayList<>(List.of(9L));liveRoles=new ArrayList<>(storedRoles);
        tokens=mock(TokenService.class);users=mock(ISysUserService.class);
        when(tokens.verifyToken(any())).thenReturn(true);
        roleService=mock(ISysRoleService.class);
        ISysMenuService menus=mock(ISysMenuService.class);
        ISysConfigService config=mock(ISysConfigService.class);
        SysPermissionService permissions=new SysPermissionService();
        ReflectionTestUtils.setField(permissions,"menuService",menus);
        when(menus.selectMenuPermsByRoleId(anyLong())).thenAnswer(i->new HashSet<>(livePermissions));
        when(users.selectUserById(2L)).thenAnswer(i->user(liveRoles));
        when(users.selectUserByUserName(anyString())).thenAnswer(i->user(liveRoles));
        when(tokens.getLoginUser(any())).thenAnswer(i->{
            MockHttpServletRequest request=i.getArgument(0);
            if (!("Bearer "+TOKEN).equals(request.getHeader("Authorization"))) return null;
            return login(storedRoles,storedPermissions);
        });
        doAnswer(i->{persist(i.getArgument(0));return true;}).when(tokens).refreshToken(any());
        doAnswer(i->{persist(i.getArgument(0));return null;}).when(tokens).setLoginUser(any());
        filter=new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter,"tokenService",tokens);
        ReflectionTestUtils.setField(filter,"userService",users);
        ReflectionTestUtils.setField(filter,"permissionService",permissions);
        access=new PermissionService();info=new SysLoginController();
        ReflectionTestUtils.setField(info,"menuService",menus);
        ReflectionTestUtils.setField(info,"permissionService",permissions);
        ReflectionTestUtils.setField(info,"configService",config);
        if (org.springframework.util.ReflectionUtils.findField(info.getClass(),"tokenService")!=null)
            ReflectionTestUtils.setField(info,"tokenService",tokens);
        roleController=new SysRoleController();
        ReflectionTestUtils.setField(roleController,"roleService",roleService);
        ReflectionTestUtils.setField(roleController,"userService",users);
        if (org.springframework.util.ReflectionUtils.findField(roleController.getClass(),"tokenService")!=null)
            ReflectionTestUtils.setField(roleController,"tokenService",tokens);
        if (org.springframework.util.ReflectionUtils.findField(roleController.getClass(),"permissionService")!=null)
            ReflectionTestUtils.setField(roleController,"permissionService",permissions);
        when(roleService.checkRoleNameUnique(any())).thenReturn(true);
        when(roleService.checkRoleKeyUnique(any())).thenReturn(true);
        when(roleService.updateRole(any())).thenAnswer(i->{livePermissions.add(EDIT);return 1;});
        when(menus.selectMenuTreeByUserId(2L)).thenAnswer(i->{
            List<SysMenu> tree=new ArrayList<>();
            for(String permission:List.of(READ,EDIT)){
                SysMenu menu=new SysMenu();menu.setMenuType("C");menu.setComponent("boost/console");menu.setPerms(permission);menu.setMenuName(permission);tree.add(menu);
            }
            return tree;
        });
        when(menus.buildMenus(anyList())).thenAnswer(i->{
            List<RouterVo> routers=new ArrayList<>();
            for(SysMenu menu:(List<SysMenu>)i.getArgument(0)){RouterVo router=new RouterVo();router.setName(menu.getMenuName());routers.add(router);}
            return routers;
        });
    }
    static SysUser user(List<Long> ids) {
        SysUser u=new SysUser();u.setUserId(2L);u.setDeptId(1L);u.setUserName("isolated_refresh_review");
        u.setStatus("0");u.setDelFlag("0");List<SysRole> roles=new ArrayList<>();
        for(Long id:ids){SysRole r=new SysRole(id);r.setRoleKey("review_role_"+id);r.setStatus("0");r.setDelFlag("0");r.setDataScope("1");roles.add(r);}
        u.setRoles(roles);return u;
    }
    static LoginUser login(List<Long> ids,Set<String> perms) {
        SysUser user=user(ids);user.getRoles().forEach(role->role.setPermissions(new HashSet<>(perms)));
        LoginUser login=new LoginUser(2L,1L,user,new HashSet<>(perms));login.setToken(TOKEN);return login;
    }
    void persist(LoginUser login) {
        storedPermissions=new HashSet<>(login.getPermissions());
        storedRoles=new ArrayList<>(login.getUser().getRoles().stream().map(SysRole::getRoleId).toList());
    }
    int request(String route,Consumer<MockHttpServletResponse> action)throws Exception {
        SecurityContextHolder.clearContext();MockHttpServletRequest req=new MockHttpServletRequest("GET",route);
        req.addHeader("Authorization","Bearer "+TOKEN);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        MockHttpServletResponse res=new MockHttpServletResponse();
        filter.doFilter(req,res,(request,response)->{
            if(SecurityContextHolder.getContext().getAuthentication()==null){res.setStatus(401);return;}
            action.accept(res);
        });
        return res.getStatus();
    }
    int editRequest()throws Exception{return request("/system/user",res->res.setStatus(access.hasPermi(EDIT)?200:403));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}

    @Test void getInfoCannotPersistNewPermissionIntoTheSameSession()throws Exception {
        livePermissions.add(EDIT);assertEquals(403,editRequest());
        request("/getInfo",res->{AjaxResult result=info.getInfo();
            assertEquals(200,result.get("code"));
            assertFalse(((Set<?>)result.get("permissions")).contains(EDIT),"Metadata must use the filtered session permissions");
            assertTrue(((Set<?>)result.get("permissions")).contains(READ));
        });
        assertFalse(storedPermissions.contains(EDIT));assertEquals(403,editRequest());
        // A new authenticated login may acquire the now-authorized grant.
        storedPermissions=new HashSet<>(livePermissions);assertEquals(200,editRequest());
    }
    @Test void getInfoCannotRestoreARevokedMenuPermission()throws Exception {
        livePermissions.remove(READ);
        request("/getInfo",res->assertFalse(((Set<?>)info.getInfo().get("permissions")).contains(READ)));
        assertEquals(403,request("/system/role/list",res->res.setStatus(access.hasPermi(READ)?200:403)));
    }
    @Test void roleEditCannotPersistNewPermissionsIntoTheCurrentSession()throws Exception {
        assertEquals(403,editRequest());SysRole role=new SysRole(9L);role.setRoleName("Isolated role");role.setRoleKey("review_role_9");
        assertEquals(200,request("/system/role",res->{assertTrue(access.hasPermi(MANAGE));assertEquals(200,roleController.edit(role).get("code"));}));
        assertTrue(livePermissions.contains(EDIT),"The authorized database change should succeed");
        assertFalse(storedPermissions.contains(EDIT),"Role editing must not refresh broader grants into the old session");
        assertEquals(403,editRequest());storedPermissions=new HashSet<>(livePermissions);assertEquals(200,editRequest());
    }
    @Test void roleEditCannotPullNewlyAssignedRolesIntoTheCurrentSession()throws Exception {
        liveRoles.add(10L);SysRole role=new SysRole(9L);role.setRoleName("Isolated role");role.setRoleKey("review_role_9");
        request("/system/role",res->assertEquals(200,roleController.edit(role).get("code")));
        assertEquals(List.of(9L),storedRoles,"Editing a role must preserve the login role boundary");
        request("/getInfo",res->assertFalse(((Set<?>)info.getInfo().get("roles")).contains("review_role_10")));
        storedRoles=new ArrayList<>(liveRoles);storedPermissions=new HashSet<>(livePermissions);
        request("/getInfo",res->assertTrue(((Set<?>)info.getInfo().get("roles")).contains("review_role_10")));
    }
    @Test void routerMetadataCannotDisplayMenusOutsideExistingSessionPermissions()throws Exception {
        livePermissions.add(EDIT);
        request("/getRouters",res->{
            List<RouterVo> routers=(List<RouterVo>)info.getRouters().get("data");
            assertEquals(List.of(READ),routers.stream().map(RouterVo::getName).toList(),"New database menus must not become unusable old-session navigation");
        });
        storedPermissions=new HashSet<>(livePermissions);
        request("/getRouters",res->{
            List<RouterVo> routers=(List<RouterVo>)info.getRouters().get("data");
            assertTrue(routers.stream().anyMatch(router->EDIT.equals(router.getName())),"Fresh login must receive its newly authorized menu");
        });
    }
}
