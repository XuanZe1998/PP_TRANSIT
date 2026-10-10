#!/usr/bin/env python3
"""Install the SEO routes without replacing the effective TLS/security configuration."""
import argparse
import datetime
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

INCLUDE = "include /etc/nginx/snippets/linknux-public-seo.conf;"


def server_blocks(source):
    # Ignore braces inside comments/quoted values when finding top-level server blocks.
    tokens = list(re.finditer(r"#[^\n]*|'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|\bserver\s*\{|[{}]", source))
    start, depth = None, 0
    for token in tokens:
        value = token.group()
        if value.startswith(("#", "'", '"')):
            continue
        if value.startswith('server') and depth == 0:
            start = token.start()
            depth = 1
        elif start is not None:
            depth += 1 if value.endswith('{') else -1
            if depth == 0:
                yield start, token.end()
                start = None


def patch_config(source):
    candidates = []
    for start, end in server_blocks(source):
        block = source[start:end]
        visible = re.sub(r'#[^\n]*', '', block)
        if re.search(r'\bserver_name\s+linknux\.com\s*;', visible) and re.search(r'\blisten\s+[^;]*\b443\b', visible):
            candidates.append((start, end, block))
    if len(candidates) != 1:
        raise ValueError('Expected exactly one linknux.com HTTPS server; no changes made')
    start, end, block = candidates[0]
    if not re.search(r'\broot\s+/var/www/linknux\s*;', block):
        raise ValueError('Unexpected frontend root; no changes made')
    fallback = r'(location\s+/\s*\{\s*try_files\s+\$uri\s+\$uri/\s+)/index\.html(\s*;\s*\})'
    if INCLUDE not in block:
        # Refuse to overwrite manually defined routes.
        if re.search(r'location\s+=\s+/(?:docs|pricing|robots\.txt|sitemap\.xml)(?:/)?\s*\{', block):
            raise ValueError('Custom SEO locations exist; review them manually')
        block = block[:-1] + '    ' + INCLUDE + '\n' + block[-1:]
    block, count = re.subn(fallback, r'\1/app.html\2', block)
    if count != 1 and not re.search(r'location\s+/\s*\{\s*try_files\s+\$uri\s+\$uri/\s+/app\.html\s*;\s*\}', block):
        raise ValueError('Unexpected SPA fallback; no changes made')
    return source[:start] + block + source[end:]


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(content)
    os.chmod(temporary, 0o644)
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', default='/etc/nginx/sites-available/linknux')
    parser.add_argument('--snippet', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Run with sudo after the application release is live')
    for filename in ('app.html', 'docs/index.html', 'pricing/index.html', 'robots.txt', 'sitemap.xml'):
        if not (Path('/var/www/linknux') / filename).is_file():
            raise SystemExit('New frontend artifacts are missing; deploy the application first')
    config = Path(args.config).resolve(strict=True)
    snippet = Path('/etc/nginx/snippets/linknux-public-seo.conf')
    before_config = config.read_bytes()
    before_snippet = snippet.read_bytes() if snippet.exists() else None
    updated = patch_config(before_config.decode()).encode()
    snippet_source = Path(args.snippet).read_bytes()
    if updated == before_config and snippet_source == before_snippet:
        subprocess.run(['nginx', '-t'], check=True)
        print('SEO routes already installed; configuration valid')
        return
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    backup = config.with_name(config.name + '.pre-seo-' + stamp)
    shutil.copy2(config, backup)
    if before_snippet is not None:
        shutil.copy2(snippet, snippet.with_name(snippet.name + '.pre-seo-' + stamp))
    try:
        atomic_write(snippet, snippet_source)
        atomic_write(config, updated)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except BaseException:
        atomic_write(config, before_config)
        if before_snippet is None:
            snippet.unlink(missing_ok=True)
        else:
            atomic_write(snippet, before_snippet)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
        raise
    print('SEO routes installed; backup: ' + str(backup))


if __name__ == '__main__':
    main()
