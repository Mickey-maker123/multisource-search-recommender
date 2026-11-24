"""Package a reviewed commit and its tested JAR for Windows. Run after clean verify."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.2.0"
PUBLIC_DIRS = {".github", "config", "contracts", "docs", "engine", "examples", "fixtures",
               "model-service", "scripts", "third-party", "tools"}
PUBLIC_FILES = {".gitattributes", ".gitignore", "CONTRIBUTING.md", "LICENSE", "NOTICE", "README.md",
                "RELEASE.md", "SECURITY.md"}
FORBIDDEN = {".git", ".local", ".venv", "baseline", "research", "__pycache__", "target", "runtime",
             "credentials.json", "query-identities.json", "HANDOFF.md", "AGENTS.md"}


def git(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT)


def check_member(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or any(p in FORBIDDEN or p.startswith(".env") for p in path.parts):
        raise ValueError(f"Private path in source: {name}")
    if name not in PUBLIC_FILES and path.parts[0] not in PUBLIC_DIRS:
        raise ValueError(f"Unreviewed root entry: {name}")
    if name.endswith((".jar", ".exe", ".whl", ".db", ".log", ".pem", ".key", ".p12", ".zip")):
        raise ValueError(f"Unexpected source asset: {name}")


def add(archive, name, data):
    info = ZipInfo(name, (2026, 11, 24, 0, 0, 0))
    info.compress_type = ZIP_DEFLATED
    archive.writestr(info, data)


def package(output):
    if git("status", "--porcelain", "--untracked-files=normal").strip():
        raise ValueError("Build from a clean, reviewed source commit")
    jar = (ROOT / "engine/target/engine.jar").read_bytes()
    with ZipFile(io.BytesIO(jar)) as engine:
        metadata = engine.read("META-INF/maven/local.federated/engine/pom.properties").decode()
        if f"version={VERSION}" not in metadata.splitlines() or any(p.startswith("com/mysql/") for p in engine.namelist()):
            raise ValueError("Unexpected engine version or bundled optional driver")
    files = {}
    with ZipFile(io.BytesIO(git("archive", "--format=zip", "HEAD"))) as source:
        for member in source.infolist():
            if member.is_dir():
                continue
            check_member(member.filename)
            files[member.filename] = source.read(member)
    output.mkdir(exist_ok=True, parents=True)
    names = [output / f"multisource-search-recommender-{VERSION}-{kind}.zip" for kind in ("source", "windows")]
    sums = output / "SHA256SUMS.txt"
    if any(p.exists() for p in [*names, sums]):
        raise FileExistsError("Choose a fresh output directory")
    hashes = {}
    for archive_path, binary in zip(names, (False, True)):
        with ZipFile(archive_path, "x") as archive:
            for name in sorted(files):
                add(archive, name, files[name])
            if binary:
                add(archive, "lib/engine.jar", jar)
            record = {"version": VERSION, "commit": git("rev-parse", "HEAD").decode().strip(),
                      "jarSha256": hashlib.sha256(jar).hexdigest() if binary else None}
            add(archive, "release-manifest.json", json.dumps(record, indent=2).encode())
        hashes[archive_path.name] = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    sums.write_text("".join(f"{digest}  {name}\n" for name, digest in hashes.items()), encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    package(parser.parse_args().output.resolve())
