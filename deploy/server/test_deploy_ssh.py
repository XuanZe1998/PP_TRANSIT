import importlib.machinery
import importlib.util
from pathlib import Path
import unittest

loader = importlib.machinery.SourceFileLoader('deploy_ssh', str(Path(__file__).with_name('api-transit-deploy-ssh')))
spec = importlib.util.spec_from_loader(loader.name, loader)
guard = importlib.util.module_from_spec(spec)
loader.exec_module(guard)


class DeploymentCommandTests(unittest.TestCase):
    release = '20261102-100206-8ff9bfa'

    def test_accepts_only_the_release_protocol(self):
        destination = '/var/lib/api-transit-deploy/incoming/' + self.release
        self.assertEqual(guard.plan_command("install -d -m 0700 '" + destination + "'"), ('prepare', self.release))
        for prefix in ['sudo ', 'sudo -n ']:
            self.assertEqual(guard.plan_command(prefix + guard.RELEASE_SCRIPT + " '" + self.release + "'"), ('activate', self.release))
        for subsystem in [guard.SFTP_SERVER, 'internal-sftp', 'sftp']:
            self.assertEqual(guard.plan_command(subsystem), ('sftp', None))

    def test_refuses_arbitrary_commands_and_shell_injection(self):
        for command in ['', 'sh', 'id', 'sudo sh', 'sudo -E ' + guard.RELEASE_SCRIPT + ' ' + self.release,
                        'sftp -d /', guard.SFTP_SERVER + ' -d /',
                        'sudo ' + guard.RELEASE_SCRIPT + ' ' + self.release + '; id',
                        'sudo ' + guard.RELEASE_SCRIPT + ' ' + self.release + '\nid',
                        'sudo ' + guard.RELEASE_SCRIPT + ' ' + self.release + ' extra',
                        'sudo ' + guard.RELEASE_SCRIPT + ' "$(id)"',
                        'sudo ' + guard.RELEASE_SCRIPT + ' "`id`"',
                        'sudo /tmp/api-transit-release ' + self.release,
                        "install -d -m 0700 '/tmp/" + self.release + "'",
                        'install -d -m 0777 /var/lib/api-transit-deploy/incoming/' + self.release,
                        'install -d -m 0700 /var/lib/api-transit-deploy/incoming/../' + self.release,
                        'install -d -m 0700 /var/lib/api-transit-deploy/incoming/' + self.release + '/',
                        "install -d -m 0700 'unterminated",
                        'sudo ' + guard.RELEASE_SCRIPT + ' 20261102-100206-8FF9BFA']:
            with self.subTest(command=command), self.assertRaises(ValueError):
                guard.plan_command(command)


if __name__ == '__main__':
    unittest.main()
