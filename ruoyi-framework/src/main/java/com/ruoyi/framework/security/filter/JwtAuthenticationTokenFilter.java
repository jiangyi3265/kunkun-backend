package com.ruoyi.framework.security.filter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * token过滤器 验证token有效性
 * 
 * @author ruoyi
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationTokenFilter.class);

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysPermissionService permissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication()))
        {
            // A cached session proves identity, not continuing authorization. Refresh roles and
            // data scope before @ss or data-scope aspects can consume a revoked cached grant.
            try
            {
                SysUser current = userService.selectUserById(loginUser.getUserId());
                if (current == null || !UserConstants.NORMAL.equals(current.getStatus())
                        || !"0".equals(current.getDelFlag()))
                {
                    tokenService.delLoginUser(loginUser.getToken());
                    chain.doFilter(request, response);
                    return;
                }
                Map<Long, Set<String>> sessionRolePermissions = new HashMap<>();
                if (!current.isAdmin())
                {
                    Set<Long> sessionRoles = new HashSet<>();
                    if (loginUser.getUser() != null && loginUser.getUser().getRoles() != null)
                    {
                        for (SysRole role : loginUser.getUser().getRoles())
                        {
                            if (UserConstants.ROLE_NORMAL.equals(role.getStatus())
                                    && "0".equals(role.getDelFlag()) && role.getPermissions() != null)
                            {
                                sessionRoles.add(role.getRoleId());
                                sessionRolePermissions.put(role.getRoleId(), new HashSet<>(role.getPermissions()));
                            }
                        }
                    }
                    List<SysRole> activeRoles = new ArrayList<>();
                    if (current.getRoles() != null)
                    {
                        for (SysRole role : current.getRoles())
                        {
                            if (sessionRoles.contains(role.getRoleId())
                                    && UserConstants.ROLE_NORMAL.equals(role.getStatus())
                                    && "0".equals(role.getDelFlag()))
                            {
                                activeRoles.add(role);
                            }
                        }
                    }
                    current.setRoles(activeRoles);
                }
                Set<String> live = new HashSet<>(permissionService.getMenuPermission(current));
                if (!current.isAdmin())
                {
                    // Global permission intersection alone is insufficient: another retained role
                    // could newly acquire an existing permission and silently widen its data scope.
                    live.clear();
                    for (SysRole role : current.getRoles())
                    {
                        Set<String> rolePermissions = new HashSet<>(role.getPermissions());
                        rolePermissions.retainAll(sessionRolePermissions.get(role.getRoleId()));
                        role.setPermissions(rolePermissions);
                        live.addAll(rolePermissions);
                    }
                }
                Set<String> cached = loginUser.getPermissions();
                if (cached == null)
                {
                    live.clear();
                }
                else if (!cached.contains(Constants.ALL_PERMISSION))
                {
                    if (live.contains(Constants.ALL_PERMISSION))
                    {
                        live = new HashSet<>(cached);
                    }
                    else
                    {
                        live.retainAll(cached);
                    }
                }
                loginUser.setUser(current);
                loginUser.setDeptId(current.getDeptId());
                loginUser.setPermissions(live);
                if (!tokenService.verifyToken(loginUser))
                {
                    chain.doFilter(request, response);
                    return;
                }
            }
            catch (RuntimeException e)
            {
                // No cached-authorization fallback when the authoritative store is unavailable.
                log.warn("Operator authorization unavailable ({})", e.getClass().getSimpleName());
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":503,\"msg\":\"权限校验暂不可用，请稍后重试\"}");
                return;
            }
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }
        chain.doFilter(request, response);
    }
}
