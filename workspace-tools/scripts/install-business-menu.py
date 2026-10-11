"""Install or upgrade only project-owned menus; preserve unrelated project data."""
from local_db import mysql,ROOT
mysql((ROOT/'RuoYi-Vue/sql/boost-menu.sql').read_text(encoding='utf-8'))
print('Business read permissions upgraded; assign new action permissions explicitly to operator roles.')
