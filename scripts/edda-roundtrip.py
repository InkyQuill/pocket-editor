#!/usr/bin/env python3
"""Disposable real Edda server + Android round-trip. Requires an isolated running emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.error import URLError
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--edda-repo', type=Path, required=True)
parser.add_argument('--galley-repo', type=Path)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
server_url = 'http://127.0.0.1:4199'
email, password = 'pocket-fixture@example.invalid', 'pocket-fixture-password'
token = None

def api(path, payload=None, method=None, binary=False):
    data = payload if isinstance(payload, bytes) else json.dumps(payload).encode() if payload is not None else None
    headers = {'Content-Type': 'application/octet-stream' if isinstance(payload, bytes) else 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    with urlopen(Request(server_url + '/api/' + path, data=data, headers=headers, method=method), timeout=10) as response:
        body = response.read()
        return body if binary else json.loads(body) if body else None

with tempfile.TemporaryDirectory(prefix='pocket-edda-roundtrip-') as tmp:
    directory = Path(tmp)
    binary = directory / 'edda-server'
    subprocess.run(['go', 'build', '-tags', 'sqlite_fts5', '-o', str(binary), '.'], cwd=args.edda_repo, check=True)
    env = dict(os.environ, OPEN_EDDA_ADDR='127.0.0.1:4199', OPEN_EDDA_DB_PATH=str(directory / 'edda.db'),
               OPEN_EDDA_DATA_DIR=str(directory), OPEN_EDDA_MIGRATIONS_PATH=str(args.edda_repo / 'migrations'),
               OPEN_EDDA_STATIC_PATH=str(args.edda_repo / 'frontend/dist'),
               OPEN_EDDA_JWT_SECRET='pocket-test-signing-secret-at-least-32-bytes',
               OPEN_EDDA_API_KEY_ENCRYPTION_SECRET='pocket-test-encryption-secret-at-least-32-bytes',
               OPEN_EDDA_BOOTSTRAP_EMAIL=email, OPEN_EDDA_BOOTSTRAP_PASSWORD=password)
    with (directory / 'server.log').open('w') as log:
        server = subprocess.Popen([str(binary)], cwd=args.edda_repo, env=env, stdout=log, stderr=log)
        try:
            for attempt in range(100):
                try:
                    token = api('auth/login', {'email': email, 'password': password})['token']
                    break
                except URLError:
                    if server.poll() is not None:
                        raise RuntimeError((directory / 'server.log').read_text())
                    time.sleep(.1)
            assert token, 'Edda did not start'
            fixture = json.loads((root / 'app/src/test/resources/fixtures/desktop-exchange.json').read_text())['cases'][0]
            book_id = 'a7000000-0000-4000-8000-000000000001'
            chapter_id = fixture['review']['chapter_id']
            second_id = 'a7000000-0000-4000-8000-000000000002'
            project_ids = []
            sources = {}
            for title, folders in [('Series', ['book-01', 'book-02']), ('Other project', ['book-03'])]:
                project = api('projects', {'title': title, 'language': 'ru', 'storageMode': 'files'})
                project_id = project['id']
                project_ids.append(project_id)
                current = api(f'projects/{project_id}/files/versions/current')
                entries = []
                for folder in folders:
                    entries.append({'id': str(uuid.uuid4()), 'path': folder, 'kind': 'directory', 'bytes': 0})
                    manifest = {'schema_version': 2, 'book_id': book_id, 'title': folder,
                                'chapters': [{'id': chapter_id, 'path': 'chapter-01.md'}, {'id': second_id, 'path': 'chapter-02.md'}], 'ignored_files': []}
                    files = {'.pocket-editor.json': json.dumps(manifest).encode(),
                             'chapter-01.md': fixture['source'].encode(), 'chapter-02.md': b'# Second\nUnchanged source\n',
                             'chapter-01.review.json': json.dumps(fixture['review']).encode()}
                    for name, data in files.items():
                        digest = hashlib.sha256(data).hexdigest()
                        api(f'projects/{project_id}/files/objects/{digest}', data, method='PUT')
                        entries.append({'id': str(uuid.uuid4()), 'path': folder + '/' + name, 'kind': 'file', 'sha256': digest, 'bytes': len(data)})
                        if name.endswith('.md'):
                            sources[(project_id, folder + '/' + name)] = digest
                api(f'projects/{project_id}/files/versions', {'expectedVersion': current['id'], 'operationId': str(uuid.uuid4()), 'entries': entries})
            adb = [args.adb, '-s', args.serial]
            subprocess.run(adb + ['reverse', 'tcp:4199', 'tcp:4199'], check=True)
            subprocess.run(['./gradlew', 'connectedDebugAndroidTest',
                            '-Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.EddaRoundtripTest',
                            '-Pandroid.testInstrumentationRunnerArguments.eddaRoundtrip=true',
                            '-Pandroid.testInstrumentationRunnerArguments.eddaProjects=' + ','.join(project_ids), '--console=plain'],
                           cwd=root, env=dict(os.environ, ANDROID_SERIAL=args.serial), check=True)
            exported = directory / 'desktop-return'
            for project_id in project_ids:
                current = api(f'projects/{project_id}/files/versions/current')
                for entry in current['entries']:
                    if entry['kind'] != 'file':
                        continue
                    path = entry['path']
                    data = api(f'projects/{project_id}/files/versions/{current["id"]}/entries/{entry["id"]}', binary=True)
                    if path.endswith('.md'):
                        assert hashlib.sha256(data).hexdigest() == sources[(project_id, path)], path
                    if path.endswith('/.pocket-editor.json'):
                        assert json.loads(data)['book_id'] == book_id, 'wire identity changed'
                    if path.endswith('chapter-01.review.json'):
                        review = json.loads(data)
                        expected_note = 'Offline Edda review' if path.startswith('book-01/') else ''
                        assert review['chapter_note'] == expected_note, 'cross-book review leak'
                        assert review['signals'] == fixture['review']['signals']
                        assert sorted(review['edits'], key=lambda x: x['id']) == sorted(fixture['review']['edits'], key=lambda x: x['id'])
                    target = exported / project_id / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
            if args.galley_repo:
                cli = directory / 'edda'
                subprocess.run(['go', 'build', '-tags', 'sqlite_fts5', '-o', str(cli), './cmd/edda'], cwd=args.edda_repo, check=True)
                cli_env = dict(os.environ, OPEN_EDDA_URL=server_url, OPEN_EDDA_TOKEN=token, XDG_CONFIG_HOME=str(directory / 'config'))
                checkout = directory / 'desktop-checkout'
                subprocess.run([str(cli), 'get', str(checkout), '--project', project_ids[0]], env=cli_env, check=True)
                subprocess.run([str(args.galley_repo / 'node_modules/.bin/tsx'), str(root / 'scripts/edda-desktop-roundtrip.mjs'), str(args.galley_repo), str(checkout / 'book-01')], check=True)
                subprocess.run([str(cli), 'send', str(checkout)], env=cli_env, check=True)
                subprocess.run(['./gradlew', 'connectedDebugAndroidTest',
                                '-Pandroid.testInstrumentationRunnerArguments.class=net.inkyquill.pocketeditor.EddaRoundtripTest',
                                '-Pandroid.testInstrumentationRunnerArguments.eddaRoundtrip=true',
                                '-Pandroid.testInstrumentationRunnerArguments.eddaPhase=return',
                                '-Pandroid.testInstrumentationRunnerArguments.eddaProjects=' + ','.join(project_ids), '--console=plain'],
                               cwd=root, env=dict(os.environ, ANDROID_SERIAL=args.serial), check=True)
                for project_id in project_ids:
                    current = api(f'projects/{project_id}/files/versions/current')
                    for entry in current['entries']:
                        if entry['path'].endswith('.md'):
                            assert entry['sha256'] == sources[(project_id, entry['path'])]
                print('PASS: Edda CLI get → Galley Desk codecs → CLI send → Android comment/order return')
            print('PASS: three isolated Android books, offline note round-trip, source/wire IDs, edits, signals and anchors preserved')
        finally:
            server.terminate()
            server.wait(timeout=10)
            subprocess.run([args.adb, '-s', args.serial, 'reverse', '--remove', 'tcp:4199'], check=False)
