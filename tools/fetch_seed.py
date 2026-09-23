#!/usr/bin/env python3
"""Download a stock Galaxy Fit 3 (SM-R390) watch-face seed from the Samsung store.

The official fitface-studio companion mirrors the stock Fit3 plugin's calls to
the Galaxy Store API. The store does not hand out .bin files directly: it serves
APK packages whose assets contain the OPPO container (SM-R390_<id>_256x402.bin)
plus per-style PNG previews. This script replicates that flow:

    catalogue  -> vas.samsungapps.com/vas/product/getContentCategoryProductList.as
    download   -> vas.samsungapps.com/vas/stub/gearAppDownload.as  (hashValue)
    package    -> follow downloadURI, unzip, keep the single SM-R390_*_256x402.bin

Usage:
    python3 tools/fetch_seed.py                      # interactive pickup list
    python3 tools/fetch_seed.py 00031               # fetch a specific face id
    python3 tools/fetch_seed.py --list              # just print the catalogue
    python3 tools/fetch_seed.py --out /tmp/seed.bin 00031

Output defaults to Desktop/GalaxyFit3_seed_<id>_<name>.bin inside the current
catalogue directory. Uses only the standard library.
"""

import argparse
import base64
import hashlib
import io
import os
import re
import subprocess
import sys
import urllib.parse
import zipfile
import xml.etree.ElementTree as ET

BASE = "https://vas.samsungapps.com/vas"
HASH_SUFFIX = "GALAXYAPPSAPI"
USER_AGENT = "Mozilla/5.0"

CATALOG_PARAMS = {
    "imgWidth": "216", "imgHeight": "432",
    "startNum": "1", "endNum": "100",
    "status": "1", "cc": "KOR", "extraInfo": "screenshot",
    "callerId": "com.samsung.wearable.fit3plugin", "locale": "en_US",
    "alignOrder": "recent", "contentCategoryID": "0000004252",
    "mcc": "450", "mnc": "10", "csc": "NONE",
    "deviceId": "SM-R390", "sdkVer": "34", "pd": "0",
}

STUB_PARAMS = {
    "callerId": "com.samsung.wearable.fit3plugin",
    "versionCode": "126071051", "extuk": "83a1c4f7", "systemId": "1730000000000",
    "abiType": "64", "cc": "KOR", "deviceId": "SM-R390", "locale": "en_US",
    "mcc": "450", "mnc": "10", "csc": "NONE", "loginType": "N", "pd": "0",
    "oneUiVersion": "0", "contentCategoryID": "0000004252", "sdkVer": "34",
}

FACE_BIN_PATTERN = re.compile(r"SM-R390_(\d{5})_256x402\.bin$")
FACE_ID_PATTERN = re.compile(r"sm_r390_(\d{4,5})$")


def http_get(url: str) -> bytes:
    # Use curl: it honours the macOS system keychain where Python's urllib
    # fails with CERTIFICATE_VERIFY_FAILED on self-signed root chains.
    proc = subprocess.run(
        ["curl", "-sS", "-L", "--fail", "--max-time", "120", "-H", f"User-Agent: {USER_AGENT}", url],
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"curl failed ({proc.returncode}): {proc.stderr.decode(errors='replace').strip() or url}")
    return proc.stdout


def store_hash(text: str) -> str:
    digest = hashlib.sha1((text + HASH_SUFFIX).encode("iso-8859-1")).digest()
    return base64.b64encode(digest).decode()


def fetch_catalogue() -> list[dict]:
    qs = urllib.parse.urlencode(CATALOG_PARAMS)
    xml = http_get(f"{BASE}/product/getContentCategoryProductList.as?{qs}")
    root = ET.fromstring(xml)
    if root.findtext("resultCode") != "0":
        raise RuntimeError(f"Catalogue failed: {root.findtext('resultMsg')}")
    faces = []
    for app in root.findall(".//appInfo"):
        app_id = app.findtext("appId", "")
        m = FACE_ID_PATTERN.search(app_id or "")
        if not m:
            continue
        face_id = m.group(1).zfill(5)
        size = int(app.findtext("realContentSize", "0") or 0)
        faces.append({
            "id": face_id,
            "appId": app_id,
            "versionCode": app.findtext("versionCode", ""),
            "name": app.findtext("productName", "").strip(),
            "bytes": size,
        })
    return faces


def resolve_download(app_id: str) -> str:
    params = dict(STUB_PARAMS)
    params["appInfo"] = app_id
    params["hashValue"] = store_hash(app_id)
    qs = urllib.parse.urlencode(params)
    xml = http_get(f"{BASE}/stub/gearAppDownload.as?{qs}")
    root = ET.fromstring(xml)
    app = root.find("appInfo")
    code = (app or root).findtext("resultCode")
    if code != "1":
        raise RuntimeError(f"Download resolution failed (code {code}) for {app_id}")
    uri_el = (app or root).find("downloadURI")
    return uri_el.text.strip() if uri_el is not None and uri_el.text else None


def grab_seed(app_id: str) -> bytes:
    uri = resolve_download(app_id)
    print(f"  downloading {uri}")
    apk = http_get(uri)
    member = None
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        for name in z.namelist():
            if FACE_BIN_PATTERN.search(name):
                member = z.read(name)
    if member is None:
        raise RuntimeError(f"No SM-R390_*_256x402.bin member found in {app_id} package")
    return member


def agree_interactive(faces: list[dict], out_dir: str) -> None:
    print(f"{len(faces)} faces in the SM-R390 catalogue:\n")
    for i, f in enumerate(faces):
        size_mb = f["bytes"] / 1024 / 1024
        print(f"  {i:>3}  {f['id']}  {f['name'][:44]:<44} {size_mb:5.2f} MB")
    print()
    pick = input("face index: ").strip()
    face = faces[int(pick)]
    print(f"fetching {face['id']} ({face['name']})")
    seed = grab_seed(face["appId"])
    write_seed(seed, face, out_dir)


def write_seed(seed: bytes, face: dict, out_dir: str) -> None:
    safe = re.sub(r"[^\w\-]+", "_", face["name"]).strip("_") or "face"
    path = os.path.join(out_dir, f"GalaxyFit3_seed_{face['id']}_{safe}.bin")
    with open(path, "wb") as fh:
        fh.write(seed)
    print(f"\nseed written: {path} ({len(seed):,} bytes)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("face_id", nargs="?", help="6-digit face id, e.g. 00031")
    ap.add_argument("--list", action="store_true", help="print the catalogue only")
    ap.add_argument("--out", default=os.path.join(os.path.expanduser("~"), "Desktop"),
                    help="output directory (default: ~/Desktop)")
    args = ap.parse_args()

    print("fetching catalogue …")
    faces = fetch_catalogue()
    faces.sort(key=lambda f: f["bytes"])

    if args.list:
        for f in faces:
            print(f"{f['id']}  {f['name'][:44]:<44} {f['bytes']/1024/1024:5.2f} MB")
        return 0

    if args.face_id:
        face_id = args.face_id.zfill(5)
        face = next((f for f in faces if f["id"] == face_id), None)
        if not face:
            print(f"face {face_id} not in catalogue", file=sys.stderr)
            return 1
        print(f"fetching {face['id']} ({face['name']})")
        seed = grab_seed(face["appId"])
        write_seed(seed, face, args.out)
        return 0

    agree_interactive(faces, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())