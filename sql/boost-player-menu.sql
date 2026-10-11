-- Add player shop review permissions without granting any role new rights.
-- Execute only after backup and deployment approval. Existing menu IDs must belong to boost.
UPDATE sys_menu SET menu_name='玩家道具交易' WHERE menu_id=87000 AND path='boost' AND menu_type='M';
UPDATE sys_menu SET menu_name='玩家管理' WHERE menu_id=87003 AND parent_id=87000 AND component='boost/console' AND path='members';
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87008,'店铺认证审核',87000,8,'shops','boost/console',1,1,'C','0','0','boost:shops:read','shop','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87008) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87000 AND path='boost' AND menu_type='M');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 87026,'店铺材料审核',87008,1,'#',1,0,'F','0','0','boost:shops:review','#','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87026) AND EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=87008 AND parent_id=87000 AND component='boost/console' AND path='shops');
