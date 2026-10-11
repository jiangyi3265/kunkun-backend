"""Remove one stopped, owned local acceptance DB; retain evidence and private files."""
import argparse,json,re,subprocess
from pathlib import Path
from local_db import mysql,configuration
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('run_report');a=p.parse_args()
report_path=Path(a.run_report).resolve();assert report_path.is_relative_to(root/'test-results')
report=json.loads(report_path.read_text('utf-8'));base=report['base'];assert re.fullmatch(r'http://127\.0\.0\.1:\d+',base)
port=int(base.rsplit(':',1)[1]);assert 1<=port<=65535
command='$listeners=@(Get-NetTCPConnection -LocalPort '+str(port)+' -State Listen -ErrorAction SilentlyContinue); Write-Output $listeners.Count'
probe=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',command],capture_output=True,text=True,check=True,timeout=20)
assert probe.stdout.strip()=='0','Acceptance port has a listener or cannot be verified'
fixture_path=Path(report['fixtureFile']).resolve();assert fixture_path.is_relative_to(root/'.runtime')
fixture=json.loads(fixture_path.read_text('utf-8'));db=fixture['database'];assert db==report['database'] and re.fullmatch(r'boost_verify_ui_\d+_[a-f0-9]{6}',db)
assert configuration()['database']!=db
marker=mysql('SELECT marker FROM qa_ui_fixture_marker',db).strip().splitlines();assert marker==['marker',fixture['marker']]
mysql('DROP DATABASE `'+db+'`')
print('Removed only owned stopped acceptance database '+db+'; evidence and private media retained')
