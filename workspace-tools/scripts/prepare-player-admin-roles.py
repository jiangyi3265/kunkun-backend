"""Create/revoke restricted operators only in a marked localhost acceptance database."""
import argparse, json, re, secrets
from pathlib import Path
import bcrypt
from local_db import ROOT, configuration, mysql

parser = argparse.ArgumentParser()
parser.add_argument('--fixture', required=True)
parser.add_argument('--revoke-review', action='store_true')
args = parser.parse_args()
file = Path(args.fixture).resolve()
assert file.is_relative_to(ROOT / '.runtime')
fixture = json.loads(file.read_text(encoding='utf-8'))
database = fixture['database']
assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}', database)
assert database != configuration()['database']
assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker', database)
private = file.parent / 'restricted-operators.json'
quote = lambda value: 'CONVERT(0x' + str(value).encode().hex() + ' USING utf8mb4)'

if args.revoke_review:
    actors = json.loads(private.read_text(encoding='utf-8'))
    reviewer = next(x for x in actors if x['name'] == 'reviewer')
    assert int(mysql('SELECT COUNT(*) FROM sys_role WHERE role_id=' + str(reviewer['roleId']) +
                     ' AND role_key=' + quote(reviewer['roleKey']), database).strip().splitlines()[-1]) == 1
    mysql('DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=' +
          str(reviewer['roleId']) + " AND m.perms='boost:shops:review'", database)
    print('Owned reviewer shop-review permission revoked; shared roles unchanged')
else:
    assert not private.exists()
    actors = []
    for label, permissions in [('shop-reader', ['boost:shops:read']),
                               ('member-reader', ['boost:members:read']),
                               ('reviewer', ['boost:shops:read', 'boost:shops:review'])]:
        username = 'qa_role_' + secrets.token_hex(5)
        password = secrets.token_urlsafe(15)
        assert 5 <= len(password) <= 20  # Existing RuoYi login contract, same as root fixture.
        digest = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        role_key = 'qa_player_' + secrets.token_hex(8)
        mysql('INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_by,create_time) VALUES (' +
              quote(label) + ',' + quote(role_key) + ",99,'0','0','qa-owned',NOW());" +
              'INSERT INTO sys_user(user_name,nick_name,password,status,del_flag,create_by,create_time,pwd_update_date) VALUES (' +
              quote(username) + ',' + quote(label) + ',' + quote(digest) + ",'0','0','qa-owned',NOW(),NOW());", database)
        user_id = int(mysql('SELECT user_id FROM sys_user WHERE user_name=' + quote(username), database).strip().splitlines()[-1])
        role_id = int(mysql('SELECT role_id FROM sys_role WHERE role_key=' + quote(role_key), database).strip().splitlines()[-1])
        assert user_id != 1 and role_id != 1
        mysql('INSERT INTO sys_user_role(user_id,role_id) VALUES (' + str(user_id) + ',' + str(role_id) + ');', database)
        for permission in permissions:
            menu = mysql('SELECT menu_id FROM sys_menu WHERE perms=' + quote(permission) + " AND status='0'", database).strip().splitlines()[1:]
            assert len(menu) == 1, 'Exactly one active permission menu is required'
            mysql('INSERT INTO sys_role_menu(role_id,menu_id) VALUES (' + str(role_id) + ',' + str(int(menu[0])) + ');', database)
        actors.append({'name': label, 'credentials': {'username': username, 'password': password},
                       'userId': user_id, 'roleId': role_id, 'roleKey': role_key, 'permissions': permissions})
    private.write_text(json.dumps(actors), encoding='utf-8')
    print('Three restricted operators created in owned database; credentials retained privately')
