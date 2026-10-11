-- No automatic role grants.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87030,'会员及成长管理',87003,3,'#',1,0,'F','0','0','boost:benefits:manage','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87030) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87003 AND path='members' AND parent_id=87000);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87031,'配置等级阈值',87003,4,'#',1,0,'F','0','0','boost:benefits:rules','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87031) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87003 AND path='members' AND parent_id=87000);
