"""Generate an ignored local SQL seed; never print or store a cleartext password."""
from pathlib import Path
from getpass import getpass
import bcrypt
root=Path(__file__).resolve().parents[1]
password=getpass('New local administrator password (12-20 characters, matching Java UTF-16 length): ')
# Backend login uses Java String.length(): supplementary characters count as two.
java_length=len(password.encode('utf-16-le'))//2
if not 12 <= java_length <= 20 or len(password.encode('utf-8'))>72:
    raise SystemExit('Use 12-20 UTF-16 code units and at most 72 UTF-8 bytes.')
if password!=getpass('Repeat password: '):raise SystemExit('Passwords do not match.')
template=(root/'sql/ry_20250522.example.sql').read_text('utf-8')
assert template.count('__LOCAL_ADMIN_BCRYPT__')==1
target=root/'.runtime/local-schema.sql';target.parent.mkdir(exist_ok=True)
if target.exists():raise SystemExit('Local seed already exists; choose how to handle it before generating another.')
hashed=bcrypt.hashpw(password.encode('utf-8'),bcrypt.gensalt(rounds=12)).decode('ascii')
target.write_text(template.replace('__LOCAL_ADMIN_BCRYPT__',hashed),'utf-8')
print('Generated .runtime/local-schema.sql for an EMPTY LOCAL database. It includes destructive schema initialization; never import into shared/production databases.')
