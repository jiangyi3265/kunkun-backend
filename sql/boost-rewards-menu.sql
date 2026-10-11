-- Add menus only. Never grant a role new permissions automatically.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87009,'积分与活动',87000,9,'rewards','boost/rewards',1,1,'C','0','0','boost:rewards:read','gift','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87009) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87000 AND path='boost' AND menu_type='M');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87027,'管理积分商品与活动',87009,1,'#',1,0,'F','0','0','boost:rewards:manage','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87027) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87009 AND component='boost/rewards');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87028,'免费积分发放',87009,2,'#',1,0,'F','0','0','boost:rewards:grant','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87028) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87009 AND component='boost/rewards');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87029,'兑换订单发货',87009,3,'#',1,0,'F','0','0','boost:rewards:fulfill','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87029) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87009 AND component='boost/rewards');
