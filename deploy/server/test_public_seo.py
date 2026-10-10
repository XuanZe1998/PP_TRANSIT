import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('install_seo', Path(__file__).with_name('install-public-seo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

FIXTURE = '''server {
    server_name linknux.com;
    root /var/www/linknux;
    listen 443 ssl;
    add_header Content-Security-Policy-Report-Only "default-src 'self'" always;
    add_header Strict-Transport-Security "max-age=86400" always;
    # { braces in a comment must not affect parsing }
    location / { try_files $uri $uri/ /index.html; }
}
server { server_name api.linknux.com; listen 443 ssl; location / { proxy_pass http://127.0.0.1:8089; } }
'''


class ConfigPatchTest(unittest.TestCase):
    def test_minimal_idempotent_patch_preserves_api_and_security(self):
        updated = module.patch_config(FIXTURE)
        self.assertIn('/app.html;', updated)
        self.assertEqual(updated.count(module.INCLUDE), 1)
        self.assertIn('max-age=86400', updated)
        self.assertIn('Content-Security-Policy-Report-Only', updated)
        self.assertEqual(updated.split('server { server_name api.')[1], FIXTURE.split('server { server_name api.')[1])
        self.assertEqual(module.patch_config(updated), updated)

    def test_unexpected_or_conflicting_config_refused(self):
        for source in (FIXTURE.replace('/var/www/linknux', '/different'), FIXTURE + FIXTURE,
                       FIXTURE.replace('/index.html', '/unknown.html'),
                       FIXTURE.replace('location / {', 'location = /docs { return 200; } location / {')):
            with self.subTest(source=source), self.assertRaises(ValueError):
                module.patch_config(source)


if __name__ == '__main__':
    unittest.main()
