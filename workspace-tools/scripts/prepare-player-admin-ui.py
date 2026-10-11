"""Apply the new menu only inside this owned local HTTP fixture."""
import argparse,json,re
from pathlib import Path
from local_db import ROOT,configuration,mysql
parser=argparse.ArgumentParser();parser.add_argument('--fixture',required=True);args=parser.parse_args()
path=Path(args.fixture).resolve();assert path.is_relative_to(ROOT/'.runtime')
fixture=json.loads(path.read_text(encoding='utf-8'));database=fixture['database']
assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',database) and database!=configuration()['database']
assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',database)
mysql((ROOT/'RuoYi-Vue/sql/boost-player-menu.sql').read_text(encoding='utf-8'),database)
assert '87008' in mysql("SELECT menu_id FROM sys_menu WHERE path='shops' AND component='boost/console' AND parent_id=87000",database)
print('Owned player shop review menu installed; no shared roles or production data changed')
