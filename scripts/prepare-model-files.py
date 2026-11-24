"""Fetch the eight model files named in the pinned release manifest and verify bytes."""
from argparse import ArgumentParser
from hashlib import file_digest
import json
from pathlib import Path
import shutil
import subprocess

MANIFEST = Path(__file__).with_name("bge-m3-manifest.json")


def verify(path, record):
    if path.stat().st_size != record["bytes"]:
        return False
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest() == record["sha256"]


def prepare(destination, offline):
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    destination.mkdir(parents=True, exist_ok=True)
    for name, record in manifest["files"].items():
        target = destination / name
        if target.exists():
            if not verify(target, record):
                raise ValueError(f"Existing model file has wrong checksum: {name}")
            continue
        if offline:
            raise FileNotFoundError(f"Missing offline model file: {name}")
        partial = target.with_suffix(target.suffix + ".part")
        url = f'https://huggingface.co/{manifest["repo"]}/resolve/{manifest["revision"]}/{name}'
        subprocess.run(["curl.exe", "--fail", "--location", "--retry", "3", "--output", str(partial), url], check=True)
        if not verify(partial, record):
            raise ValueError(f"Downloaded model checksum mismatch: {name}")
        partial.replace(target)
    shutil.copyfile(MANIFEST, destination / "snapshot-manifest.json")
    print(f"Verified BGE-M3 in {destination}")


if __name__ == "__main__":
    args = ArgumentParser()
    args.add_argument("directory", type=Path)
    args.add_argument("--offline", action="store_true")
    config = args.parse_args()
    prepare(config.directory, config.offline)
