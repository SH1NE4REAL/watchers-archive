"""Download pinned official build tools into .tools; never alters machine settings.
Usage: python tools/bootstrap.py [--android]
"""
import argparse
import concurrent.futures
import hashlib
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch(url, target, checksum=None, algorithm='sha256'):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and (checksum is None or hashlib.new(algorithm, target.read_bytes()).hexdigest() == checksum):
        return target
    part = target.with_suffix(target.suffix + '.part')
    digest = hashlib.new(algorithm)
    with OPENER.open(url, timeout=60) as response, part.open('wb') as out:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            out.write(chunk)
    if checksum and digest.hexdigest() != checksum:
        part.unlink(missing_ok=True)
        raise ValueError(f'Checksum mismatch: {url}')
    part.replace(target)
    print(f'Downloaded {target.name}: {target.stat().st_size} bytes', flush=True)
    return target


def extract(archive, destination, strip_top=False):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            parts = pathlib.PurePosixPath(info.filename).parts
            if strip_top:
                parts = parts[1:]
            if not parts:
                continue
            target = destination.joinpath(*parts).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError('Archive path escapes tools directory')
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, target.open('wb') as out:
                    while chunk := src.read(1024 * 1024):
                        out.write(chunk)


def gradle():
    target = TOOLS / 'gradle-8.9'
    if not (target / 'bin/gradle.bat').exists():
        archive = fetch('https://services.gradle.org/distributions/gradle-8.9-bin.zip',
                        TOOLS / 'downloads/gradle-8.9-bin.zip',
                        'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab')
        extract(archive, target, True)
    wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
    if not wrapper.exists():
        fetch('https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar', wrapper)


def kotlin():
    artifacts = [
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.0.21'),
        ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4'),
        ('org.jetbrains', 'annotations', '13.0'),
    ]
    def one(item):
        group, name, version = item
        filename = f'{name}-{version}.jar'
        url = f'https://repo.maven.apache.org/maven2/{group.replace(".", "/")}/{name}/{version}/{filename}'
        checksum = OPENER.open(url + '.sha1', timeout=30).read().decode().split()[0]
        fetch(url, TOOLS / 'kotlin' / filename, checksum, 'sha1')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(one, artifacts))


def android():
    # Checksums from Google's repository2-1.xml; no licenses auto-accepted.
    packages = [
        ('platform-35_r02.zip', '0bb560a90a7a2cbd0dd8348224d518b638fe7949', 'platforms/android-35'),
        ('build-tools_r35_windows.zip', 'af059bb67cf7786f45ee0db85e2d24985df1b4b6', 'build-tools/35.0.0'),
        ('platform-tools_r37.0.1-win.zip', 'e03e78b1d80b396f1c3358e31251cb31740e1110', 'platform-tools'),
    ]
    def one(item):
        name, checksum, subdir = item
        destination = TOOLS / 'android-sdk' / subdir
        if not (destination / 'source.properties').exists():
            archive = fetch('https://dl.google.com/android/repository/' + name,
                            TOOLS / 'downloads' / name, checksum, 'sha1')
            extract(archive, destination, True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(one, packages))
    sdk = (TOOLS / 'android-sdk').as_posix()
    (ROOT / 'local.properties').write_text('sdk.dir=' + sdk.replace(':', '\\:') + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--android', action='store_true')
    args = parser.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        jobs = [pool.submit(gradle), pool.submit(kotlin)]
        if args.android:
            jobs.append(pool.submit(android))
        for job in jobs:
            job.result()
    print('Official tools ready inside .tools. No global settings changed.')
