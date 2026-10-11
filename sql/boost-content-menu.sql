-- Add owned routes without granting new permissions to existing operator roles.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87010,'内容管理',87000,10,'content','boost/content',1,1,'C','0','0','boost:content:read','edit','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87010) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87000 AND path='boost' AND menu_type='M');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87011,'图文活动',87000,11,'activities','boost/content',1,1,'C','0','0','boost:content:read','documentation','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87011) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87000 AND path='boost' AND menu_type='M');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87032,'编辑页面和图文活动',87010,1,'#',1,0,'F','0','0','boost:content:manage','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87032) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87010 AND component='boost/content');
