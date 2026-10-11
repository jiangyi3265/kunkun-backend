"""Read-only deployment inventory. Credentials arrive through stdin, never argv or logs."""
import base64
import hashlib
import json
from pathlib import Path
import sys

import paramiko

root = Path(__file__).resolve().parent.parent
credentials = json.load(sys.stdin)
hosts = root / '.runtime/server-known-hosts'
client = paramiko.SSHClient()
if hosts.exists():
    client.load_host_keys(str(hosts))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
else:
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

commands = {
    'system': 'uname -sr; cat /etc/os-release; df -h /; free -m',
    'tools': 'for t in java nginx mysql redis-cli docker node python3 certbot; do command -v "$t" || true; done; java -version 2>&1 | head -n 3; node --version 2>/dev/null || true',
    'listeners': 'ss -lntp',
    'services': "systemctl list-units --type=service --state=running --no-pager --no-legend | grep -Ei 'nginx|mysql|maria|redis|java|boost|ruoyi|docker|panel' || true",
    'layout': 'for d in /srv /opt /etc/nginx /etc/boost /www/wwwroot /www/server/panel/vhost/nginx; do if [ -d "$d" ]; then ls -ld "$d"; find "$d" -mindepth 1 -maxdepth 1 -printf "%f\\n" | head -n 45; fi; done',
    'target_sites': "for f in /www/server/panel/vhost/nginx/ceshisss.oksja.cn.conf /www/server/panel/vhost/nginx/ceshijjht.oksja.cn.conf; do if [ -f \"$f\" ]; then echo \"$f\"; grep -E '^[[:space:]]*(listen|server_name|root|proxy_pass|ssl_certificate|include|location|return|client_max_body_size)' \"$f\"; fi; done; for d in /www/wwwroot/ceshisss.oksja.cn /www/wwwroot/ceshijjht.oksja.cn; do if [ -d \"$d\" ]; then find \"$d\" -maxdepth 2 -type f -printf '%P %s bytes\\n' | head -n 30; fi; done",
    'available_runtimes': "find /usr/lib/jvm /www/server/java -maxdepth 3 -type f -name java 2>/dev/null; ls -ld /usr/lib/jvm/* /www/server/java/* 2>/dev/null; ls -ld /etc/letsencrypt/accounts /etc/letsencrypt/live/* /www/server/panel/vhost/cert/ceshisss.oksja.cn /www/server/panel/vhost/cert/ceshijjht.oksja.cn 2>/dev/null; date -u +%FT%TZ",
}
result = {'host': credentials['host'], 'readOnly': True, 'checks': {}}
try:
    client.connect(credentials['host'], username=credentials['user'], password=credentials['password'], timeout=15, auth_timeout=15, banner_timeout=15, allow_agent=False, look_for_keys=False)
    if not hosts.exists():
        client.save_host_keys(str(hosts))
    key = client.get_transport().get_remote_server_key()
    result['hostKeySha256'] = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
    for name, command in commands.items():
        _, stdout, stderr = client.exec_command(command, timeout=20)
        result['checks'][name] = {'stdout': stdout.read().decode('utf-8', 'replace')[:16000], 'stderr': stderr.read().decode('utf-8', 'replace')[:1500], 'exitCode': stdout.channel.recv_exit_status()}
    result['connected'] = True
except Exception as exc:
    result['connected'] = False
    result['error'] = type(exc).__name__ + ': ' + str(exc).split('\n')[0]
finally:
    client.close()
    target = root / 'test-results/deployment-preflight-20261002/server-inventory.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
if not result['connected']:
    sys.exit(1)
