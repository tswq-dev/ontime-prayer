#!/usr/bin/env python3
"""Signing for the iPhone build on CI, through the App Store Connect API (no Mac, no stored certificates).

  setup    makes a fresh "Apple Distribution" certificate (new key + CSR), and an App Store provisioning
           profile for each bundle id with it; writes cert.p12 and the profiles, and adds to $GITHUB_ENV:
           ASC_CERT_ID, P12_PASS, and PROFILE_<n> = the profile name per bundle id, in order.
  cleanup  revokes that certificate and deletes the profiles (run it always, so certificates never pile up;
           builds already uploaded to TestFlight / the App Store are not affected).

Needs ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 (the .p8 text) in the environment, and `pip install pyjwt cryptography`.
"""
import base64, json, os, secrets, sys, time, urllib.error, urllib.request

import jwt
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

API = "https://api.appstoreconnect.apple.com/v1"
PROFILE_PREFIX = "CI "


def token():
    now = int(time.time())
    return jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"},
                      os.environ["ASC_KEY_P8"], algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        sys.exit(f"App Store Connect API {method} {path}: HTTP {e.code}\n{detail}")


def bundle_id(identifier):
    res = call("GET", f"/bundleIds?filter[identifier]={identifier}&limit=200")
    for b in res.get("data", []):
        if b["attributes"]["identifier"] == identifier:
            return b["id"]
    sys.exit(f"Bundle ID {identifier} is not registered: add it in Certificates, Identifiers & Profiles → Identifiers.")


def delete_profiles(identifier):
    name = PROFILE_PREFIX + identifier
    for p in call("GET", f"/profiles?filter[name]={urllib.request.quote(name)}&limit=200").get("data", []):
        if p["attributes"]["name"] == name:
            call("DELETE", f"/profiles/{p['id']}")


def emit(line):
    """KEY=VALUE into $GITHUB_ENV right away (so cleanup knows the certificate even if a later step fails)."""
    path = os.environ.get("GITHUB_ENV")
    if path:
        with open(path, "a") as f:
            f.write(line + "\n")
    else:
        print(line)


def setup(identifiers):
    bids = [bundle_id(i) for i in identifiers]  # fail early, before making a certificate
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    csr = (x509.CertificateSigningRequestBuilder()
           .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "CI Distribution"),
                                    x509.NameAttribute(NameOID.COUNTRY_NAME, "SA")]))
           .sign(key, hashes.SHA256()))
    csr_pem = csr.public_bytes(serialization.Encoding.PEM).decode()
    cert = call("POST", "/certificates", {"data": {"type": "certificates", "attributes": {
        "certificateType": "DISTRIBUTION", "csrContent": csr_pem}}})["data"]
    cert_id = cert["id"]
    emit(f"ASC_CERT_ID={cert_id}")
    der = base64.b64decode(cert["attributes"]["certificateContent"])
    pw = secrets.token_hex(16)
    print(f"::add-mask::{pw}")
    # legacy p12 encryption, which the macOS keychain imports
    enc = (serialization.PrivateFormat.PKCS12.encryption_builder().kdf_rounds(50000)
           .key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC).hmac_hash(hashes.SHA1()).build(pw.encode()))
    with open("cert.p12", "wb") as f:
        f.write(pkcs12.serialize_key_and_certificates(b"CI Distribution", key, x509.load_der_x509_certificate(der), None, enc))

    emit(f"P12_PASS={pw}")
    pdir = os.path.expanduser("~/Library/MobileDevice/Provisioning Profiles")
    os.makedirs(pdir, exist_ok=True)
    for n, (ident, bid) in enumerate(zip(identifiers, bids)):
        delete_profiles(ident)
        name = PROFILE_PREFIX + ident
        prof = call("POST", "/profiles", {"data": {"type": "profiles",
            "attributes": {"name": name, "profileType": "IOS_APP_STORE"},
            "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bid}},
                              "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}})["data"]
        a = prof["attributes"]
        with open(os.path.join(pdir, a["uuid"] + ".mobileprovision"), "wb") as f:
            f.write(base64.b64decode(a["profileContent"]))
        emit(f"PROFILE_{n}={name}")


def cleanup(cert_id, identifiers):
    for ident in identifiers:
        delete_profiles(ident)
    if cert_id:
        call("DELETE", f"/certificates/{cert_id}")


if __name__ == "__main__":
    cmd, ids = sys.argv[1], sys.argv[2:]
    if cmd == "setup":
        setup(ids)
    elif cmd == "cleanup":
        cleanup(os.environ.get("ASC_CERT_ID", ""), ids)
    else:
        sys.exit("usage: asc.py setup|cleanup <bundle id>...")
