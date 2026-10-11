"""Create an ephemeral localhost certificate for native-body-preserving UI test relay."""
import sys,argparse
from pathlib import Path
from datetime import datetime,timedelta,timezone
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
import ipaddress
p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];folder=Path(a.output).resolve()
assert folder.is_relative_to(root/'.runtime') and folder.name.startswith('profile-ui-tls-')
folder.mkdir(parents=True,exist_ok=False)
key=rsa.generate_private_key(public_exponent=65537,key_size=2048);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'localhost UI test')]);now=datetime.now(timezone.utc)
certificate=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=1)).add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1')),x509.DNSName('localhost')]),critical=False).sign(key,hashes.SHA256())
(folder/'key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
(folder/'cert.pem').write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
print('Ephemeral owned localhost TLS relay certificate created; key remains private.')
